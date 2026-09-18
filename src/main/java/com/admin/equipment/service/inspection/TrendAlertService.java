package com.admin.equipment.service.inspection;

import com.admin.equipment.model.Equipment;
import com.admin.equipment.model.WorkOrder;
import com.admin.equipment.model.inspection.InspectionRecord;
import com.admin.equipment.model.inspection.InspectionTemplateItem;
import com.admin.equipment.model.inspection.TrendAlert;
import com.admin.equipment.model.inspection.TrendAlertAction;
import com.admin.equipment.model.inspection.TrendAlertEvidence;
import com.admin.equipment.model.inspection.TrendEvaluationLog;
import com.admin.equipment.model.inspection.TrendRule;
import com.admin.equipment.repo.EquipmentRepository;
import com.admin.equipment.repo.WorkOrderRepository;
import com.admin.equipment.repo.inspection.InspectionRecordRepository;
import com.admin.equipment.repo.inspection.InspectionTemplateItemRepository;
import com.admin.equipment.repo.inspection.TrendAlertActionRepository;
import com.admin.equipment.repo.inspection.TrendAlertEvidenceRepository;
import com.admin.equipment.repo.inspection.TrendAlertRepository;
import com.admin.equipment.repo.inspection.TrendEvaluationLogRepository;
import com.admin.equipment.repo.inspection.TrendRuleRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 趋势预警查询与处置：确认、忽略（到期自动重开）、转维修工单。
 * 任何处置都保留操作者与理由；预警实体带乐观锁，并发处置/并发评估只会有一方成功。
 */
@Service
public class TrendAlertService {

    /** 预警详情返回的窗口样本上限，超出时取最近的一段并标记截断 */
    private static final int WINDOW_SAMPLE_LIMIT = 200;

    private final TrendAlertRepository alertRepo;
    private final TrendAlertEvidenceRepository evidenceRepo;
    private final TrendAlertActionRepository actionRepo;
    private final TrendEvaluationLogRepository evalLogRepo;
    private final TrendRuleRepository ruleRepo;
    private final InspectionRecordRepository recordRepo;
    private final InspectionTemplateItemRepository itemRepo;
    private final EquipmentRepository equipmentRepo;
    private final WorkOrderRepository workOrderRepo;

    public TrendAlertService(TrendAlertRepository alertRepo,
                             TrendAlertEvidenceRepository evidenceRepo,
                             TrendAlertActionRepository actionRepo,
                             TrendEvaluationLogRepository evalLogRepo,
                             TrendRuleRepository ruleRepo,
                             InspectionRecordRepository recordRepo,
                             InspectionTemplateItemRepository itemRepo,
                             EquipmentRepository equipmentRepo,
                             WorkOrderRepository workOrderRepo) {
        this.alertRepo = alertRepo;
        this.evidenceRepo = evidenceRepo;
        this.actionRepo = actionRepo;
        this.evalLogRepo = evalLogRepo;
        this.ruleRepo = ruleRepo;
        this.recordRepo = recordRepo;
        this.itemRepo = itemRepo;
        this.equipmentRepo = equipmentRepo;
        this.workOrderRepo = workOrderRepo;
    }

    public Page<TrendAlert> search(String status, Long equipmentId, Long ruleId, String level,
                                   int page, int size) {
        String st = status == null || status.isBlank() ? null : status;
        String lv = level == null || level.isBlank() ? null : level;
        return alertRepo.search(st, equipmentId, ruleId, lv, PageRequest.of(page, size));
    }

    public record SamplePoint(Long recordId, LocalDateTime recordedAt, Double value) {}

    public record AlertDetail(TrendAlert alert,
                              List<TrendAlertEvidence> evidence,
                              List<TrendAlertAction> actions,
                              List<TrendEvaluationLog> evaluations,
                              List<SamplePoint> windowSamples,
                              boolean samplesTruncated,
                              String ruleStatus,
                              Integer ruleCurrentVersion,
                              String ruleNote) {}

    /** 预警详情：触发证据、处置记录、评估留痕、窗口时间序列，以及规则现状解释。 */
    public AlertDetail getDetail(Long id) {
        TrendAlert a = alertRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("预警不存在"));
        List<TrendAlertEvidence> evidence = evidenceRepo.findByAlertIdOrderByIdAsc(id);
        List<TrendAlertAction> actions = actionRepo.findByAlertIdOrderByIdAsc(id);
        List<TrendEvaluationLog> evals = evalLogRepo.findByAlertIdOrderByIdAsc(id);

        List<SamplePoint> samples = new ArrayList<>();
        boolean truncated = false;
        if (a.getWindowStartAt() != null && a.getWindowEndAt() != null) {
            List<InspectionRecord> series =
                    recordRepo.findTrendSeries(String.valueOf(a.getEquipmentId()), a.getTemplateItemId());
            for (InspectionRecord r : series) {
                if (r.getRecordedAt() == null) continue;
                if (!r.getRecordedAt().isBefore(a.getWindowStartAt())
                        && !r.getRecordedAt().isAfter(a.getWindowEndAt())) {
                    samples.add(new SamplePoint(r.getId(), r.getRecordedAt(), r.getCheckNumeric()));
                }
            }
            if (samples.size() > WINDOW_SAMPLE_LIMIT) {
                samples = new ArrayList<>(samples.subList(samples.size() - WINDOW_SAMPLE_LIMIT, samples.size()));
                truncated = true;
            }
        }

        TrendRule rule = ruleRepo.findById(a.getRuleId()).orElse(null);
        String ruleStatus = rule == null ? "deleted" : rule.getStatus();
        Integer ruleCurrentVersion = rule == null ? null : rule.getRuleVersion();
        String ruleNote;
        if (rule == null) {
            ruleNote = "规则已删除";
        } else if ("disabled".equals(rule.getStatus())) {
            ruleNote = "规则已停用（失效），不再产生新的评估";
        } else if (!Objects.equals(ruleCurrentVersion, a.getRuleVersion())) {
            ruleNote = "规则已更新至 v" + ruleCurrentVersion + "，本预警由 v" + a.getRuleVersion()
                    + " 产生，后续评估使用新版本";
        } else if ("draft".equals(rule.getStatus())) {
            ruleNote = "规则当前为草稿状态";
        } else {
            ruleNote = "规则仍为发布状态";
        }
        return new AlertDetail(a, evidence, actions, evals, samples, truncated,
                ruleStatus, ruleCurrentVersion, ruleNote);
    }

    public record PageInfo(int page, int size, long totalElements, int totalPages) {}

    public record SeriesSample(Long recordId, Long taskId, Long pointId, LocalDateTime recordedAt,
                               Double value, boolean usable, String note) {}

    public record RuleExplanation(Long ruleId, String name, Integer version, String status, String statusNote,
                                  Map<String, Object> params, Map<String, Object> sampleGap,
                                  Map<String, Object> lastEvaluation, Map<String, Object> activeAlert) {}

    public record SeriesView(Map<String, Object> equipment, Map<String, Object> item,
                             long usableSampleCount, PageInfo page, List<SeriesSample> samples,
                             List<RuleExplanation> rules, List<Map<String, Object>> recentLateRecomputes) {}

    /**
     * 时间序列 + 规则解释：除分页样本外，还解释样本不足、迟到重算与规则失效。
     * 缺失值记录保留在序列中并标记 usable=false，不参与趋势计算。
     */
    public SeriesView getSeries(Long equipmentId, Long itemId, LocalDateTime from, LocalDateTime to,
                                int page, int size) {
        Equipment eq = equipmentRepo.findById(equipmentId)
                .orElseThrow(() -> new IllegalArgumentException("设备不存在"));
        InspectionTemplateItem item = itemRepo.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("模板巡检项不存在"));

        Page<InspectionRecord> p = recordRepo.pageTrendSeries(
                String.valueOf(equipmentId), itemId, from, to, PageRequest.of(page, size));
        List<SeriesSample> samples = new ArrayList<>();
        for (InspectionRecord r : p.getContent()) {
            boolean usable = r.getCheckNumeric() != null && r.getRecordedAt() != null;
            samples.add(new SeriesSample(r.getId(), r.getTaskId(), r.getPointId(), r.getRecordedAt(),
                    r.getCheckNumeric(), usable, usable ? "" : "缺失数值，不参与趋势计算"));
        }
        long usableCount = recordRepo.countTrendSeries(String.valueOf(equipmentId), itemId);

        LocalDateTime now = LocalDateTime.now();
        List<RuleExplanation> rules = new ArrayList<>();
        for (TrendRule rule : ruleRepo.findByTemplateItemIdOrderByIdDesc(itemId)) {
            if (!TrendSupport.typeMatches(rule.getEquipmentType(), eq.getType())) continue;
            String statusNote = switch (rule.getStatus()) {
                case "published" -> "已发布，参与评估";
                case "disabled" -> "已停用，规则失效，不再参与评估";
                default -> "草稿未发布，不参与评估";
            };
            Map<String, Object> sampleGap = null;
            int ms = rule.getMinSamples() == null ? 1 : rule.getMinSamples();
            if ("published".equals(rule.getStatus()) && usableCount < ms) {
                Map<String, Object> gap = new LinkedHashMap<>();
                gap.put("required", ms);
                gap.put("actual", usableCount);
                gap.put("note", "有效样本不足，暂无法完成评估");
                sampleGap = gap;
            }
            Map<String, Object> lastEval = null;
            TrendEvaluationLog le = evalLogRepo
                    .findFirstByRuleIdAndEquipmentIdAndTemplateItemIdOrderByIdDesc(rule.getId(), equipmentId, itemId)
                    .orElse(null);
            if (le != null) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("result", le.getResult());
                m.put("detail", le.getDetail());
                m.put("windowKey", le.getWindowKey());
                m.put("sampleCount", le.getSampleCount());
                m.put("minSamples", le.getMinSamples());
                m.put("lateRecompute", Boolean.TRUE.equals(le.getLateRecompute()));
                m.put("evaluatedAt", le.getEvaluatedAt());
                lastEval = m;
            }
            Map<String, Object> activeAlert = null;
            TrendAlert latest = alertRepo
                    .findFirstByEquipmentIdAndTemplateItemIdAndRuleIdAndRuleVersionOrderByIdDesc(
                            equipmentId, itemId, rule.getId(), rule.getRuleVersion())
                    .orElse(null);
            if (latest != null && TrendSupport.withinHorizon(latest, now)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("alertId", latest.getId());
                m.put("status", latest.getStatus());
                m.put("level", latest.getLevel());
                m.put("firstTriggeredAt", latest.getFirstTriggeredAt());
                m.put("cooldownUntil", latest.getCooldownUntil());
                m.put("ignoreUntil", latest.getIgnoreUntil());
                activeAlert = m;
            }
            rules.add(new RuleExplanation(rule.getId(), rule.getName(), rule.getRuleVersion(),
                    rule.getStatus(), statusNote, TrendSupport.paramsMap(rule),
                    sampleGap, lastEval, activeAlert));
        }

        List<Map<String, Object>> recomputes = new ArrayList<>();
        for (TrendEvaluationLog l : evalLogRepo
                .findTop20ByEquipmentIdAndTemplateItemIdAndLateRecomputeTrueOrderByIdDesc(equipmentId, itemId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ruleId", l.getRuleId());
            m.put("ruleVersion", l.getRuleVersion());
            m.put("windowKey", l.getWindowKey());
            m.put("recordId", l.getRecordId());
            m.put("result", l.getResult());
            m.put("detail", l.getDetail());
            m.put("evaluatedAt", l.getEvaluatedAt());
            recomputes.add(m);
        }

        Map<String, Object> equip = new LinkedHashMap<>();
        equip.put("id", eq.getId());
        equip.put("code", eq.getCode());
        equip.put("name", eq.getName());
        equip.put("type", eq.getType());
        Map<String, Object> itemMap = new LinkedHashMap<>();
        itemMap.put("id", item.getId());
        itemMap.put("name", item.getName());
        itemMap.put("type", item.getType());
        itemMap.put("normalMin", item.getNormalMin());
        itemMap.put("normalMax", item.getNormalMax());

        return new SeriesView(equip, itemMap, usableCount,
                new PageInfo(p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages()),
                samples, rules, recomputes);
    }

    @Transactional
    public TrendAlert acknowledge(Long id, String operator, String reason) {
        TrendAlert a = requireAlert(id);
        requireOperatorReason(operator, reason);
        if (!"open".equals(a.getStatus())) {
            throw new IllegalArgumentException("仅未处理的预警可以确认，当前状态：" + a.getStatus());
        }
        a.setStatus("acknowledged");
        a.setUpdatedAt(LocalDateTime.now());
        TrendAlert saved = alertRepo.save(a);
        saveAction(id, "acknowledge", operator, reason, "open", "acknowledged", null);
        return saved;
    }

    @Transactional
    public TrendAlert ignore(Long id, String operator, String reason, Integer ignoreHours) {
        TrendAlert a = requireAlert(id);
        requireOperatorReason(operator, reason);
        if (!Set.of("open", "acknowledged").contains(a.getStatus())) {
            throw new IllegalArgumentException("当前状态不允许忽略，当前状态：" + a.getStatus());
        }
        int hours = ignoreHours == null ? 72 : ignoreHours;
        if (hours <= 0) {
            throw new IllegalArgumentException("忽略时长必须大于 0 小时");
        }
        String from = a.getStatus();
        a.setStatus("ignored");
        a.setIgnoreUntil(LocalDateTime.now().plusHours(hours));
        a.setUpdatedAt(LocalDateTime.now());
        TrendAlert saved = alertRepo.save(a);
        saveAction(id, "ignore", operator, reason + "（忽略 " + hours + " 小时，到期后出现新证据将自动重开）",
                from, "ignored", null);
        return saved;
    }

    public record ConvertResult(TrendAlert alert, WorkOrder workOrder) {}

    @Transactional
    public ConvertResult convertToWorkOrder(Long id, String operator, String reason,
                                            String workOrderType, String priority, String assignee) {
        TrendAlert a = requireAlert(id);
        requireOperatorReason(operator, reason);
        if ("converted".equals(a.getStatus())) {
            throw new IllegalArgumentException("该预警已转为维修工单");
        }
        WorkOrder wo = new WorkOrder();
        wo.setEquipmentId(a.getEquipmentId());
        wo.setType(switch (workOrderType == null ? "repair" : workOrderType) {
            case "maintenance" -> "maintenance";
            case "inspection" -> "inspection";
            default -> "repair";
        });
        wo.setTitle("[趋势预警] " + a.getItemName() + " - " + a.getEquipmentName());
        wo.setPriority(validPriority(priority, a.getLevel()));
        wo.setDescription("来源：趋势预警\n预警ID：" + a.getId()
                + "\n规则：" + a.getRuleName() + "（v" + a.getRuleVersion() + "）"
                + "\n触发：" + a.getDetail()
                + "\n处置人：" + operator + "\n处置理由：" + reason);
        wo.setAssignee(assignee == null ? "" : assignee);
        wo.setStatus("open");
        WorkOrder savedWo = workOrderRepo.save(wo);

        String from = a.getStatus();
        a.setStatus("converted");
        a.setWorkOrderId(savedWo.getId());
        a.setUpdatedAt(LocalDateTime.now());
        TrendAlert saved = alertRepo.save(a);
        saveAction(id, "convert_work_order", operator, reason, from, "converted", savedWo.getId());
        return new ConvertResult(saved, savedWo);
    }

    private TrendAlert requireAlert(Long id) {
        return alertRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("预警不存在"));
    }

    private void requireOperatorReason(String operator, String reason) {
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("操作者必填");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("处置理由必填");
        }
    }

    private String validPriority(String priority, String level) {
        if (Set.of("low", "medium", "high", "urgent").contains(priority)) {
            return priority;
        }
        return TrendSupport.validLevel(level);
    }

    private void saveAction(Long alertId, String action, String operator, String reason,
                            String fromStatus, String toStatus, Long workOrderId) {
        TrendAlertAction act = new TrendAlertAction();
        act.setAlertId(alertId);
        act.setAction(action);
        act.setOperator(operator);
        act.setReason(reason);
        act.setFromStatus(fromStatus);
        act.setToStatus(toStatus);
        act.setWorkOrderId(workOrderId);
        actionRepo.save(act);
    }
}
