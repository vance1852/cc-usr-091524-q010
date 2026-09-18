package com.admin.equipment.service.inspection;

import com.admin.equipment.model.Equipment;
import com.admin.equipment.model.inspection.InspectionPoint;
import com.admin.equipment.model.inspection.InspectionRecord;
import com.admin.equipment.model.inspection.TrendRule;
import com.admin.equipment.repo.EquipmentRepository;
import com.admin.equipment.repo.inspection.InspectionPointRepository;
import com.admin.equipment.repo.inspection.InspectionRecordRepository;
import com.admin.equipment.repo.inspection.TrendRuleRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 趋势评估编排：记录提交后异步触发。
 *
 * 迟到数据（采样时间早于序列最新时刻）按采样时间扇出重算所有受影响窗口
 * （锚点落在 [迟到采样时刻, 迟到采样时刻+窗口长度) 内的历史窗口）。
 * 同一批次的多条记录按（规则, 设备, 项目）分组后合并锚点，每个窗口只评估一次；
 * 同一（设备, 项目）序列的评估通过分段锁串行化，配合数据库唯一约束，
 * 保证并发评估不会改变可复现的结论。
 */
@Service
public class TrendEvaluationService {

    private final InspectionRecordRepository recordRepo;
    private final InspectionPointRepository pointRepo;
    private final EquipmentRepository equipmentRepo;
    private final TrendRuleRepository ruleRepo;
    private final TrendEvaluationWorker worker;

    /** 序列级分段锁：key = equipmentId:templateItemId */
    private final ConcurrentHashMap<String, ReentrantLock> seriesLocks = new ConcurrentHashMap<>();

    public TrendEvaluationService(InspectionRecordRepository recordRepo,
                                  InspectionPointRepository pointRepo,
                                  EquipmentRepository equipmentRepo,
                                  TrendRuleRepository ruleRepo,
                                  TrendEvaluationWorker worker) {
        this.recordRepo = recordRepo;
        this.pointRepo = pointRepo;
        this.equipmentRepo = equipmentRepo;
        this.ruleRepo = ruleRepo;
        this.worker = worker;
    }

    /** 评估分组键：规则 + 设备 + 项目 */
    public record SeriesRuleKey(Long ruleId, Long equipmentId, Long templateItemId) {}

    @Async("trendEvaluationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRecordsSaved(InspectionRecordsSavedEvent event) {
        if (event == null || event.recordIds() == null || event.recordIds().isEmpty()) return;
        List<InspectionRecord> records = recordRepo.findAllById(event.recordIds());
        if (records.isEmpty()) return;

        Map<Long, InspectionPoint> pointCache = new HashMap<>();
        Map<Long, Equipment> equipmentCache = new HashMap<>();
        Map<SeriesRuleKey, Set<LocalDateTime>> groups = new LinkedHashMap<>();
        for (InspectionRecord rec : records) {
            if (rec.getRecordedAt() == null) continue;
            List<TrendRule> rules = ruleRepo.findByTemplateItemIdAndEnabledTrue(rec.getTemplateItemId());
            if (rules.isEmpty()) continue;
            InspectionPoint point = pointCache.computeIfAbsent(rec.getPointId(),
                    id -> pointRepo.findById(id).orElse(null));
            if (point == null) continue;
            for (Long equipmentId : parseIds(point.getEquipmentIds())) {
                Equipment equipment = equipmentCache.computeIfAbsent(equipmentId,
                        id -> equipmentRepo.findById(id).orElse(null));
                if (equipment == null) continue;
                for (TrendRule rule : rules) {
                    if (!TrendRuleService.typeMatches(rule.getEquipmentType(), equipment.getType())) continue;
                    groups.computeIfAbsent(
                            new SeriesRuleKey(rule.getId(), equipmentId, rec.getTemplateItemId()),
                            k -> new TreeSet<>()).add(rec.getRecordedAt());
                }
            }
        }

        // 同一（设备, 项目）序列下的所有规则共享一把锁，串行化并发评估
        Map<String, List<Map.Entry<SeriesRuleKey, Set<LocalDateTime>>>> bySeriesLock = new LinkedHashMap<>();
        for (Map.Entry<SeriesRuleKey, Set<LocalDateTime>> e : groups.entrySet()) {
            String lockKey = e.getKey().equipmentId() + ":" + e.getKey().templateItemId();
            bySeriesLock.computeIfAbsent(lockKey, k -> new ArrayList<>()).add(e);
        }
        for (Map.Entry<String, List<Map.Entry<SeriesRuleKey, Set<LocalDateTime>>>> entry : bySeriesLock.entrySet()) {
            ReentrantLock lock = seriesLocks.computeIfAbsent(entry.getKey(), k -> new ReentrantLock());
            lock.lock();
            try {
                for (Map.Entry<SeriesRuleKey, Set<LocalDateTime>> group : entry.getValue()) {
                    evaluateGroup(group.getKey(), group.getValue());
                }
            } finally {
                lock.unlock();
                seriesLocks.remove(entry.getKey(), lock);
            }
        }
    }

    /**
     * 评估一组记录引发的全部受影响窗口：
     * 迟到的记录（序列中已存在更晚样本）扇出重算 [采样时刻, 采样时刻+窗口长度) 内的历史窗口；
     * 同批记录的锚点合并去重，每个窗口只评估一次。
     */
    private void evaluateGroup(SeriesRuleKey key, Set<LocalDateTime> recordAnchors) {
        TrendRule rule = ruleRepo.findById(key.ruleId()).orElse(null);
        if (rule == null || !Boolean.TRUE.equals(rule.getEnabled())) return;
        List<Long> pointIds = worker.pointIdsOf(key.equipmentId());
        if (pointIds.isEmpty()) return;

        InspectionRecord latest = recordRepo.findFirstByTemplateItemIdAndPointIdInOrderByRecordedAtDescIdDesc(
                key.templateItemId(), pointIds);
        LocalDateTime seriesLatest = latest != null ? latest.getRecordedAt() : null;

        TreeSet<LocalDateTime> anchors = new TreeSet<>();
        for (LocalDateTime t : recordAnchors) {
            if (seriesLatest != null && seriesLatest.isAfter(t)) {
                List<InspectionRecord> affected = recordRepo.findRangeSamples(key.templateItemId(), pointIds,
                        t, t.plusHours(rule.getWindowHours()));
                for (InspectionRecord r : affected) {
                    if (r.getRecordedAt() != null) anchors.add(r.getRecordedAt());
                }
            } else {
                anchors.add(t);
            }
        }
        for (LocalDateTime anchor : anchors) {
            // 迟到重算：窗口锚点早于序列最新时刻，或该窗口本身不含新记录（被迟到数据波及）
            boolean lateRecompute = (seriesLatest != null && anchor.isBefore(seriesLatest))
                    || !recordAnchors.contains(anchor);
            evaluateWithRetry(key.ruleId(), key.equipmentId(), key.templateItemId(), anchor, lateRecompute);
        }
    }

    /** 唯一约束兜底：并发下重复插入时重试一次，此时会命中既有记录并幂等更新 */
    private void evaluateWithRetry(Long ruleId, Long equipmentId, Long templateItemId,
                                   LocalDateTime anchor, boolean lateRecompute) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                worker.evaluateAnchor(ruleId, equipmentId, templateItemId, anchor, lateRecompute);
                return;
            } catch (DataIntegrityViolationException e) {
                if (attempt == 1) throw e;
            }
        }
    }

    private static List<Long> parseIds(String str) {
        List<Long> result = new ArrayList<>();
        if (str == null || str.isBlank()) return result;
        for (String p : str.split(",")) {
            try {
                long id = Long.parseLong(p.trim());
                if (id > 0) result.add(id);
            } catch (NumberFormatException ignored) {}
        }
        return result;
    }
}
