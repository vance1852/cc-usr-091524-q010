package com.admin.equipment.service.inspection;

import com.admin.equipment.model.Equipment;
import com.admin.equipment.model.inspection.InspectionRecord;
import com.admin.equipment.model.inspection.InspectionTaskPoint;
import com.admin.equipment.model.inspection.TrendAlert;
import com.admin.equipment.model.inspection.TrendAlertAction;
import com.admin.equipment.model.inspection.TrendAlertEvidence;
import com.admin.equipment.model.inspection.TrendEvaluationLog;
import com.admin.equipment.model.inspection.TrendRule;
import com.admin.equipment.repo.EquipmentRepository;
import com.admin.equipment.repo.inspection.InspectionRecordRepository;
import com.admin.equipment.repo.inspection.InspectionTaskPointRepository;
import com.admin.equipment.repo.inspection.TrendAlertActionRepository;
import com.admin.equipment.repo.inspection.TrendAlertEvidenceRepository;
import com.admin.equipment.repo.inspection.TrendAlertRepository;
import com.admin.equipment.repo.inspection.TrendEvaluationLogRepository;
import com.admin.equipment.repo.inspection.TrendRuleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 趋势预警评估服务。
 *
 * <p>记录写入事务提交后异步评估相关规则。同一 (设备, 项目) 的评估固定落到同一条
 * 单线程通道串行执行，不同序列之间可以并行；配合预警去重键唯一约束与乐观锁重试，
 * 保证并发评估不会改变可复现的结论。</p>
 *
 * <p>迟到数据：按采样时间定位新记录在序列中的位置后，重算其后的所有受影响窗口；
 * 同一窗口的重算命中同一个预警（去重键），结论一致时证据幂等不重复追加。</p>
 */
@Service
public class TrendEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(TrendEvaluationService.class);
    private static final int LANE_COUNT = 4;
    private static final int MAX_RETRY = 3;

    private final InspectionRecordRepository recordRepo;
    private final InspectionTaskPointRepository taskPointRepo;
    private final EquipmentRepository equipmentRepo;
    private final TrendRuleRepository ruleRepo;
    private final TrendAlertRepository alertRepo;
    private final TrendAlertEvidenceRepository evidenceRepo;
    private final TrendAlertActionRepository actionRepo;
    private final TrendEvaluationLogRepository evalLogRepo;
    private final PlatformTransactionManager txManager;
    private final ObjectMapper objectMapper;

    private TransactionTemplate txTemplate;
    private List<ExecutorService> lanes = List.of();

    public TrendEvaluationService(InspectionRecordRepository recordRepo,
                                  InspectionTaskPointRepository taskPointRepo,
                                  EquipmentRepository equipmentRepo,
                                  TrendRuleRepository ruleRepo,
                                  TrendAlertRepository alertRepo,
                                  TrendAlertEvidenceRepository evidenceRepo,
                                  TrendAlertActionRepository actionRepo,
                                  TrendEvaluationLogRepository evalLogRepo,
                                  PlatformTransactionManager txManager,
                                  ObjectMapper objectMapper) {
        this.recordRepo = recordRepo;
        this.taskPointRepo = taskPointRepo;
        this.equipmentRepo = equipmentRepo;
        this.ruleRepo = ruleRepo;
        this.alertRepo = alertRepo;
        this.evidenceRepo = evidenceRepo;
        this.actionRepo = actionRepo;
        this.evalLogRepo = evalLogRepo;
        this.txManager = txManager;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void init() {
        txTemplate = new TransactionTemplate(txManager);
        List<ExecutorService> list = new ArrayList<>();
        for (int i = 0; i < LANE_COUNT; i++) {
            final int laneNo = i;
            list.add(Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "trend-eval-" + laneNo);
                t.setDaemon(true);
                return t;
            }));
        }
        lanes = list;
    }

    @PreDestroy
    void shutdown() {
        for (ExecutorService lane : lanes) {
            lane.shutdown();
        }
    }

    /** 记录写入后调用：事务提交后再异步评估，避免异步线程读到未提交数据。 */
    public void scheduleEvaluationForRecords(List<InspectionRecord> records) {
        if (records == null || records.isEmpty()) return;
        List<Long> recordIds = records.stream()
                .map(InspectionRecord::getId)
                .filter(Objects::nonNull)
                .toList();
        if (recordIds.isEmpty()) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit(recordIds);
                }
            });
        } else {
            submit(recordIds);
        }
    }

    private void submit(List<Long> recordIds) {
        for (Long recordId : recordIds) {
            try {
                InspectionRecord rec = recordRepo.findById(recordId).orElse(null);
                if (rec == null || rec.getCheckNumeric() == null || !"numeric".equals(rec.getItemType())) {
                    continue;
                }
                InspectionTaskPoint tp = rec.getTaskPointId() == null ? null
                        : taskPointRepo.findById(rec.getTaskPointId()).orElse(null);
                List<Long> equipIds = TrendSupport.parseIds(tp == null ? "" : tp.getEquipmentIds());
                if (equipIds.isEmpty()) continue;
                List<TrendRule> rules = ruleRepo.findByTemplateItemIdAndStatus(rec.getTemplateItemId(), "published");
                if (rules.isEmpty()) continue;
                for (Long equipId : equipIds) {
                    Equipment eq = equipmentRepo.findById(equipId).orElse(null);
                    if (eq == null) continue;
                    for (TrendRule rule : rules) {
                        if (!TrendSupport.typeMatches(rule.getEquipmentType(), eq.getType())) continue;
                        final Long eqId = equipId;
                        final Long ruleId = rule.getId();
                        laneFor(eqId, rec.getTemplateItemId())
                                .submit(() -> evaluateWithRetry(recordId, eqId, ruleId));
                    }
                }
            } catch (Exception e) {
                log.warn("趋势评估任务提交失败 recordId={}: {}", recordId, e.getMessage());
            }
        }
    }

    /** 同一 (设备, 项目) 的评估始终进入同一通道，串行执行。 */
    private ExecutorService laneFor(Long equipmentId, Long itemId) {
        return lanes.get(Math.floorMod(Objects.hash(equipmentId, itemId), LANE_COUNT));
    }

    private void evaluateWithRetry(Long recordId, Long equipmentId, Long ruleId) {
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                txTemplate.executeWithoutResult(tx -> doEvaluate(recordId, equipmentId, ruleId));
                return;
            } catch (ObjectOptimisticLockingFailureException | DataIntegrityViolationException e) {
                log.warn("趋势评估并发冲突，重试 {}/{} recordId={} ruleId={}", attempt, MAX_RETRY, recordId, ruleId);
            } catch (Exception e) {
                log.error("趋势评估失败 recordId={} equipmentId={} ruleId={}: {}",
                        recordId, equipmentId, ruleId, e.getMessage(), e);
                return;
            }
        }
    }

    void doEvaluate(Long recordId, Long equipmentId, Long ruleId) {
        InspectionRecord rec = recordRepo.findById(recordId).orElse(null);
        TrendRule rule = ruleRepo.findById(ruleId).orElse(null);
        Equipment eq = equipmentRepo.findById(equipmentId).orElse(null);
        if (rec == null || rule == null || eq == null) return;
        Long itemId = rec.getTemplateItemId();
        if (!"published".equals(rule.getStatus())) {
            // 规则在提交评估后被停用/回退：留痕解释规则失效
            saveEvalLog(rule, equipmentId, itemId, recordId, "", "rule_inactive",
                    null, rule.getWindowSize(), rule.getMinSamples(),
                    "规则未发布或已停用（当前状态：" + rule.getStatus() + "），本次不评估", false);
            return;
        }
        if (rec.getCheckNumeric() == null || rec.getRecordedAt() == null) return;
        List<InspectionRecord> series = recordRepo.findTrendSeries(String.valueOf(equipmentId), itemId);
        int idx = -1;
        for (int i = 0; i < series.size(); i++) {
            if (series.get(i).getId().equals(recordId)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) return;
        int ws = rule.getWindowSize() == null ? 10 : rule.getWindowSize();
        int ms = rule.getMinSamples() == null ? 1 : rule.getMinSamples();
        // 正常情况只评估以新记录结尾的窗口；迟到数据插入序列中部时，
        // 其后的所有窗口成员都会变化，需要按采样时间顺序重算这些受影响窗口。
        for (int j = idx; j < series.size(); j++) {
            processWindow(rule, eq, recordId, series, j, ws, ms, j > idx);
        }
    }

    /**
     * 评估以 series[j] 结尾的滚动窗口，留评估日志并按结果更新预警。
     *
     * @return 评估结果：triggered / not_triggered / insufficient_samples
     */
    private String processWindow(TrendRule rule, Equipment eq, Long triggerRecordId,
                                 List<InspectionRecord> series, int j, int ws, int ms, boolean late) {
        Long itemId = rule.getTemplateItemId();
        int from = Math.max(0, j - ws + 1);
        List<TrendEngine.Sample> window = new ArrayList<>(j - from + 1);
        for (int k = from; k <= j; k++) {
            InspectionRecord r = series.get(k);
            window.add(new TrendEngine.Sample(r.getId(), r.getRecordedAt(), r.getCheckNumeric()));
        }
        TrendEngine.WindowResult wr = TrendEngine.evaluateWindow(window, ms,
                rule.getSlopeThreshold(), rule.getFluctuationAmplitude(),
                rule.getNearMargin(), rule.getNearBoundaryCount(),
                rule.getNormalMin(), rule.getNormalMax());
        String windowKey = "rec-" + series.get(j).getId();
        String result = !wr.evaluated() ? "insufficient_samples" : (wr.triggered() ? "triggered" : "not_triggered");
        TrendEvaluationLog evalLog = saveEvalLog(rule, eq.getId(), itemId, triggerRecordId, windowKey, result,
                wr.sampleCount(), ws, ms, wr.detail(), late);
        if (wr.triggered()) {
            TrendAlert alert = upsertAlert(rule, eq, series.get(j), windowKey, wr);
            appendEvidence(alert, windowKey, wr, late, evalLog.getId());
            evalLog.setAlertId(alert.getId());
            evalLogRepo.save(evalLog);
        } else if (wr.evaluated()) {
            markSupersededIfExists(rule, eq.getId(), itemId, windowKey);
        }
        return result;
    }

    /**
     * 预警去重与冷却合并：
     * 1. 同一窗口（去重键相同）的重算更新既有预警结论；
     * 2. 冷却期（或忽略期）内的新窗口证据并入最近的事件；
     * 3. 否则创建新事件。
     */
    private TrendAlert upsertAlert(TrendRule rule, Equipment eq, InspectionRecord windowEndRec,
                                   String windowKey, TrendEngine.WindowResult wr) {
        LocalDateTime now = LocalDateTime.now();
        Long itemId = rule.getTemplateItemId();
        String key = TrendSupport.dedupeKey(eq.getId(), itemId, rule.getId(), rule.getRuleVersion(), windowKey);
        TrendAlert exact = alertRepo.findByDedupeKey(key).orElse(null);
        if (exact != null) {
            exact.setSuperseded(false);
            exact.setWindowStartAt(wr.windowStartAt());
            exact.setWindowEndAt(wr.windowEndAt());
            exact.setTriggeredMetrics(String.join(",", wr.triggeredMetrics()));
            exact.setDetail(wr.detail());
            exact.setLastEvidenceAt(now);
            exact.setUpdatedAt(now);
            return alertRepo.save(exact);
        }
        TrendAlert latest = alertRepo
                .findFirstByEquipmentIdAndTemplateItemIdAndRuleIdAndRuleVersionOrderByIdDesc(
                        eq.getId(), itemId, rule.getId(), rule.getRuleVersion())
                .orElse(null);
        if (latest != null && TrendSupport.withinHorizon(latest, now)) {
            latest.setWindowEndAt(wr.windowEndAt());
            latest.setDetail(wr.detail());
            latest.setTriggeredMetrics(unionMetrics(latest.getTriggeredMetrics(), wr.triggeredMetrics()));
            latest.setLevel(TrendSupport.maxLevel(latest.getLevel(), rule.getLevel()));
            latest.setLastEvidenceAt(now);
            latest.setUpdatedAt(now);
            if ("ignored".equals(latest.getStatus())
                    && latest.getIgnoreUntil() != null && now.isAfter(latest.getIgnoreUntil())) {
                String from = latest.getStatus();
                latest.setStatus("open");
                saveAction(latest.getId(), "auto_reopen", "system",
                        "忽略到期后出现新的触发证据，事件自动重开", from, "open", null);
            }
            return alertRepo.save(latest);
        }
        TrendAlert a = new TrendAlert();
        a.setDedupeKey(key);
        a.setRuleId(rule.getId());
        a.setRuleVersion(rule.getRuleVersion());
        a.setRuleName(rule.getName());
        a.setRuleParams(paramsSnapshot(rule));
        a.setLevel(TrendSupport.validLevel(rule.getLevel()));
        a.setEquipmentId(eq.getId());
        a.setEquipmentCode(eq.getCode());
        a.setEquipmentName(eq.getName());
        a.setPointId(windowEndRec.getPointId());
        a.setTemplateItemId(itemId);
        a.setItemName(rule.getItemName());
        a.setWindowKey(windowKey);
        a.setWindowStartAt(wr.windowStartAt());
        a.setWindowEndAt(wr.windowEndAt());
        a.setTriggeredMetrics(String.join(",", wr.triggeredMetrics()));
        a.setDetail(wr.detail());
        a.setStatus("open");
        a.setFirstTriggeredAt(now);
        a.setLastEvidenceAt(now);
        int cooldown = rule.getCooldownHours() == null ? 24 : rule.getCooldownHours();
        a.setCooldownUntil(now.plusHours(cooldown));
        a.setSuperseded(false);
        return alertRepo.save(a);
    }

    /** 追加触发证据；同一窗口同一指标结论一致的重算不重复追加（幂等）。 */
    private void appendEvidence(TrendAlert alert, String windowKey, TrendEngine.WindowResult wr,
                                boolean late, Long evalLogId) {
        for (TrendEngine.MetricHit hit : wr.metricHits()) {
            TrendAlertEvidence last = evidenceRepo
                    .findFirstByAlertIdAndWindowKeyAndMetricOrderByIdDesc(alert.getId(), windowKey, hit.metric())
                    .orElse(null);
            if (last != null
                    && Objects.equals(last.getComputedValue(), hit.computedValue())
                    && Objects.equals(last.getThresholdValue(), hit.thresholdValue())
                    && Objects.equals(last.getSampleCount(), wr.sampleCount())
                    && Objects.equals(last.getDetail(), hit.detail())) {
                continue;
            }
            TrendAlertEvidence ev = new TrendAlertEvidence();
            ev.setAlertId(alert.getId());
            ev.setEvaluationId(evalLogId);
            ev.setWindowKey(windowKey);
            ev.setMetric(hit.metric());
            ev.setComputedValue(hit.computedValue());
            ev.setThresholdValue(hit.thresholdValue());
            ev.setSampleCount(wr.sampleCount());
            ev.setWindowStartAt(wr.windowStartAt());
            ev.setWindowEndAt(wr.windowEndAt());
            ev.setDetail(hit.detail());
            ev.setLateRecompute(late);
            evidenceRepo.save(ev);
        }
    }

    /** 迟到重算后窗口不再触发：保留事件与历史证据，仅打上失效标记。 */
    private void markSupersededIfExists(TrendRule rule, Long equipmentId, Long itemId, String windowKey) {
        String key = TrendSupport.dedupeKey(equipmentId, itemId, rule.getId(), rule.getRuleVersion(), windowKey);
        alertRepo.findByDedupeKey(key).ifPresent(a -> {
            if (!Boolean.TRUE.equals(a.getSuperseded())) {
                a.setSuperseded(true);
                a.setDetail(a.getDetail() + "（迟到数据重算后该窗口不再触发）");
                a.setUpdatedAt(LocalDateTime.now());
                alertRepo.save(a);
            }
        });
    }

    /**
     * 手动重算：对某设备按规则当前版本回放全部有效样本的每个窗口。
     * 幂等——同一窗口命中同一预警，结论一致的证据不重复追加。
     */
    public Map<String, Object> reevaluate(Long ruleId, Long equipmentId) {
        TrendRule rule = ruleRepo.findById(ruleId)
                .orElseThrow(() -> new IllegalArgumentException("规则不存在"));
        if (!"published".equals(rule.getStatus())) {
            throw new IllegalArgumentException("规则未发布，不能重算");
        }
        Equipment eq = equipmentRepo.findById(equipmentId)
                .orElseThrow(() -> new IllegalArgumentException("设备不存在"));
        if (!TrendSupport.typeMatches(rule.getEquipmentType(), eq.getType())) {
            throw new IllegalArgumentException("规则适用的设备类型与该设备不匹配");
        }
        Map<String, Object> result = txTemplate.execute(tx -> {
            List<InspectionRecord> series = recordRepo.findTrendSeries(String.valueOf(equipmentId), rule.getTemplateItemId());
            int ws = rule.getWindowSize() == null ? 10 : rule.getWindowSize();
            int ms = rule.getMinSamples() == null ? 1 : rule.getMinSamples();
            int evaluated = 0, triggered = 0, insufficient = 0;
            for (int j = 0; j < series.size(); j++) {
                String windowKey = "rec-" + series.get(j).getId();
                boolean late = evalLogRepo.existsByRuleIdAndRuleVersionAndEquipmentIdAndTemplateItemIdAndWindowKey(
                        rule.getId(), rule.getRuleVersion(), equipmentId, rule.getTemplateItemId(), windowKey);
                String r = processWindow(rule, eq, series.get(j).getId(), series, j, ws, ms, late);
                evaluated++;
                if ("triggered".equals(r)) triggered++;
                if ("insufficient_samples".equals(r)) insufficient++;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ruleId", rule.getId());
            out.put("ruleVersion", rule.getRuleVersion());
            out.put("equipmentId", equipmentId);
            out.put("usableSamples", series.size());
            out.put("evaluatedWindows", evaluated);
            out.put("triggered", triggered);
            out.put("insufficientSamples", insufficient);
            return out;
        });
        return result == null ? Map.of() : result;
    }

    private TrendEvaluationLog saveEvalLog(TrendRule rule, Long equipmentId, Long itemId, Long recordId,
                                           String windowKey, String result, Integer sampleCount,
                                           Integer windowSize, Integer minSamples, String detail, boolean late) {
        TrendEvaluationLog l = new TrendEvaluationLog();
        l.setRuleId(rule.getId());
        l.setRuleVersion(rule.getRuleVersion());
        l.setEquipmentId(equipmentId);
        l.setTemplateItemId(itemId);
        l.setRecordId(recordId);
        l.setWindowKey(windowKey);
        l.setResult(result);
        l.setSampleCount(sampleCount);
        l.setWindowSize(windowSize);
        l.setMinSamples(minSamples);
        l.setDetail(detail == null ? "" : (detail.length() > 512 ? detail.substring(0, 512) : detail));
        l.setLateRecompute(late);
        l.setEvaluatedAt(LocalDateTime.now());
        return evalLogRepo.save(l);
    }

    private void saveAction(Long alertId, String action, String operator, String reason,
                            String fromStatus, String toStatus, Long workOrderId) {
        TrendAlertAction act = new TrendAlertAction();
        act.setAlertId(alertId);
        act.setAction(action);
        act.setOperator(operator == null ? "" : operator);
        act.setReason(reason == null ? "" : reason);
        act.setFromStatus(fromStatus == null ? "" : fromStatus);
        act.setToStatus(toStatus == null ? "" : toStatus);
        act.setWorkOrderId(workOrderId);
        actionRepo.save(act);
    }

    private String unionMetrics(String existing, List<String> fresh) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (existing != null && !existing.isBlank()) {
            set.addAll(Arrays.asList(existing.split(",")));
        }
        set.addAll(fresh);
        return String.join(",", set);
    }

    private String paramsSnapshot(TrendRule rule) {
        try {
            return objectMapper.writeValueAsString(TrendSupport.paramsMap(rule));
        } catch (Exception e) {
            return "";
        }
    }
}
