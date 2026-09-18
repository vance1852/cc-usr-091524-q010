package com.admin.equipment.service.inspection;

import com.admin.equipment.model.Equipment;
import com.admin.equipment.model.inspection.*;
import com.admin.equipment.repo.EquipmentRepository;
import com.admin.equipment.repo.inspection.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 单锚点窗口评估执行器。每个锚点（采样时刻）一个事务，评估结果只取决于
 * 已提交的数据与规则版本，与评估发生的先后、并发顺序无关：
 * 同刻读数按均值合并、缺失值剔除并计数、证据按唯一键幂等写入。
 */
@Service
public class TrendEvaluationWorker {

    private final TrendRuleRepository ruleRepo;
    private final TrendAlertRepository alertRepo;
    private final TrendAlertEvidenceRepository evidenceRepo;
    private final TrendAlertActionRepository actionRepo;
    private final TrendEvaluationRepository evaluationRepo;
    private final InspectionRecordRepository recordRepo;
    private final InspectionPointRepository pointRepo;
    private final InspectionTemplateItemRepository itemRepo;
    private final EquipmentRepository equipmentRepo;

    public TrendEvaluationWorker(TrendRuleRepository ruleRepo,
                                 TrendAlertRepository alertRepo,
                                 TrendAlertEvidenceRepository evidenceRepo,
                                 TrendAlertActionRepository actionRepo,
                                 TrendEvaluationRepository evaluationRepo,
                                 InspectionRecordRepository recordRepo,
                                 InspectionPointRepository pointRepo,
                                 InspectionTemplateItemRepository itemRepo,
                                 EquipmentRepository equipmentRepo) {
        this.ruleRepo = ruleRepo;
        this.alertRepo = alertRepo;
        this.evidenceRepo = evidenceRepo;
        this.actionRepo = actionRepo;
        this.evaluationRepo = evaluationRepo;
        this.recordRepo = recordRepo;
        this.pointRepo = pointRepo;
        this.itemRepo = itemRepo;
        this.equipmentRepo = equipmentRepo;
    }

    /** 窗口内一个有效样本：同刻读数已合并为均值 */
    public record Sample(LocalDateTime time, double value) {}

    /** 窗口统计结果 */
    public record WindowStats(List<Sample> samples, int missingCount, int duplicateCount,
                              Double slope, Double amplitude, int nearBoundTrailing) {}

    @Transactional
    public TrendEvaluation evaluateAnchor(Long ruleId, Long equipmentId, Long templateItemId,
                                          LocalDateTime anchor, boolean lateRecompute) {
        TrendRule rule = ruleRepo.findById(ruleId).orElse(null);
        if (rule == null || !Boolean.TRUE.equals(rule.getEnabled())) return null;
        Equipment equipment = equipmentRepo.findById(equipmentId).orElse(null);
        if (equipment == null) return null;
        InspectionTemplateItem item = itemRepo.findById(templateItemId).orElse(null);
        if (item == null) return null;
        List<Long> pointIds = pointIdsOf(equipmentId);
        if (pointIds.isEmpty()) return null;

        LocalDateTime windowStart = anchor.minusHours(rule.getWindowHours());
        List<InspectionRecord> records = recordRepo.findWindowSamples(templateItemId, pointIds, windowStart, anchor);
        WindowStats stats = computeStats(records, rule, item);
        int valid = stats.samples().size();

        if (valid < rule.getMinSamples()) {
            String detail = "窗口[" + fmtTime(windowStart) + " ~ " + fmtTime(anchor) + "] 有效样本 "
                    + valid + " 个（缺失 " + stats.missingCount() + "，同刻重复合并 " + stats.duplicateCount()
                    + "），少于最少样本 " + rule.getMinSamples() + "，暂不评估";
            return saveEvaluation(rule, equipmentId, anchor, windowStart, "insufficient_samples",
                    stats, "", detail, lateRecompute, null);
        }

        List<String> triggered = new ArrayList<>();
        List<String> metricDesc = new ArrayList<>();
        if (rule.getSlopeThreshold() != null) {
            String desc = "斜率 " + fmtNum(stats.slope()) + "/天（阈值 " + fmtNum(rule.getSlopeThreshold()) + "）";
            metricDesc.add(desc);
            if (stats.slope() != null && Math.abs(stats.slope()) >= rule.getSlopeThreshold()) {
                triggered.add("slope");
            }
        }
        if (rule.getAmplitudeThreshold() != null) {
            String desc = "波动幅度 " + fmtNum(stats.amplitude()) + "（阈值 " + fmtNum(rule.getAmplitudeThreshold()) + "）";
            metricDesc.add(desc);
            if (stats.amplitude() != null && stats.amplitude() >= rule.getAmplitudeThreshold()) {
                triggered.add("amplitude");
            }
        }
        if (rule.getNearBoundLimit() != null) {
            String desc = "连续接近边界 " + stats.nearBoundTrailing() + " 次（阈值 " + rule.getNearBoundLimit() + "）";
            metricDesc.add(desc);
            if (stats.nearBoundTrailing() >= rule.getNearBoundLimit()) {
                triggered.add("near_bound");
            }
        }

        String baseDetail = "窗口[" + fmtTime(windowStart) + " ~ " + fmtTime(anchor) + "] 有效样本 " + valid
                + "（缺失 " + stats.missingCount() + "，同刻重复合并 " + stats.duplicateCount() + "）；"
                + String.join("；", metricDesc);

        if (triggered.isEmpty()) {
            // 迟到重算可能推翻旧结论：撤回该窗口既有证据，但保留审计痕迹
            TrendAlertEvidence existing = evidenceRepo
                    .findByRuleIdAndRuleVersionAndEquipmentIdAndTemplateItemIdAndWindowEnd(
                            rule.getId(), rule.getVersion(), equipmentId, templateItemId, anchor)
                    .orElse(null);
            if (existing != null && !Boolean.TRUE.equals(existing.getRetracted())) {
                existing.setRetracted(true);
                existing.setDetail(existing.getDetail() + "；迟到数据重算后不再满足触发条件，证据撤回");
                evidenceRepo.save(existing);
                refreshActiveEvidenceCount(existing.getAlertId());
            }
            return saveEvaluation(rule, equipmentId, anchor, windowStart, "not_triggered",
                    stats, "", baseDetail + "；未触发", lateRecompute, null);
        }

        String metrics = String.join(",", triggered);
        String detail = baseDetail + "；触发：" + metricNames(triggered);
        TrendAlertEvidence evidence = evidenceRepo
                .findByRuleIdAndRuleVersionAndEquipmentIdAndTemplateItemIdAndWindowEnd(
                        rule.getId(), rule.getVersion(), equipmentId, templateItemId, anchor)
                .orElse(null);

        TrendAlert alert;
        LocalDateTime now = LocalDateTime.now();
        if (evidence != null) {
            // 同一窗口重复评估（含迟到重算）：更新既有证据，结论保持幂等
            alert = alertRepo.findById(evidence.getAlertId()).orElse(null);
            fillEvidence(evidence, rule, equipmentId, windowStart, anchor, stats, metrics, detail, lateRecompute);
            evidence.setRetracted(false);
            evidenceRepo.save(evidence);
        } else {
            alert = alertRepo.findFirstByRuleIdAndRuleVersionAndEquipmentIdAndTemplateItemIdAndCooldownUntilAfterOrderByLastEvidenceAtDesc(
                    rule.getId(), rule.getVersion(), equipmentId, templateItemId, now).orElse(null);
            if (alert == null) {
                alert = createAlert(rule, equipment, windowStart, anchor, now);
            }
            evidence = new TrendAlertEvidence();
            evidence.setAlertId(alert.getId());
            fillEvidence(evidence, rule, equipmentId, windowStart, anchor, stats, metrics, detail, lateRecompute);
            evidenceRepo.save(evidence);
        }

        if (alert != null) {
            updateAlertOnEvidence(alert, rule, stats, metrics, now);
        }
        return saveEvaluation(rule, equipmentId, anchor, windowStart, "triggered",
                stats, metrics, detail, lateRecompute, alert != null ? alert.getId() : null);
    }

    /** 设备关联的巡检点ID列表（设备级序列由这些点的读数组成） */
    public List<Long> pointIdsOf(Long equipmentId) {
        String token = String.valueOf(equipmentId);
        List<Long> pointIds = new ArrayList<>();
        for (InspectionPoint p : pointRepo.findAll()) {
            if (p.getEquipmentIds() == null || p.getEquipmentIds().isBlank()) continue;
            for (String part : p.getEquipmentIds().split(",")) {
                if (token.equals(part.trim())) {
                    pointIds.add(p.getId());
                    break;
                }
            }
        }
        return pointIds;
    }

    /** 窗口统计：同刻读数合并为均值，缺失值剔除并计数，指标由有序样本确定性计算 */
    public WindowStats computeStats(List<InspectionRecord> records, TrendRule rule, InspectionTemplateItem item) {
        TreeMap<LocalDateTime, double[]> byTime = new TreeMap<>();
        TreeMap<LocalDateTime, Integer> counts = new TreeMap<>();
        int missing = 0;
        int totalValues = 0;
        for (InspectionRecord r : records) {
            if (r.getRecordedAt() == null) continue;
            if (r.getCheckNumeric() == null) {
                missing++;
                continue;
            }
            totalValues++;
            double[] acc = byTime.computeIfAbsent(r.getRecordedAt(), k -> new double[1]);
            acc[0] += r.getCheckNumeric();
            counts.merge(r.getRecordedAt(), 1, Integer::sum);
        }
        List<Sample> samples = new ArrayList<>();
        for (Map.Entry<LocalDateTime, double[]> e : byTime.entrySet()) {
            samples.add(new Sample(e.getKey(), e.getValue()[0] / counts.get(e.getKey())));
        }
        int duplicates = totalValues - samples.size();

        Double slope = null;
        if (samples.size() >= 2) {
            slope = slopePerDay(samples);
        }
        Double amplitude = null;
        if (!samples.isEmpty()) {
            double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
            for (Sample s : samples) {
                min = Math.min(min, s.value());
                max = Math.max(max, s.value());
            }
            amplitude = max - min;
        }
        int nearBoundTrailing = 0;
        if (rule.getNearBoundMargin() != null) {
            for (int i = samples.size() - 1; i >= 0; i--) {
                if (isNearBound(samples.get(i).value(), item, rule.getNearBoundMargin())) {
                    nearBoundTrailing++;
                } else {
                    break;
                }
            }
        }
        return new WindowStats(samples, missing, duplicates, slope, amplitude, nearBoundTrailing);
    }

    private static double slopePerDay(List<Sample> samples) {
        int n = samples.size();
        double[] x = new double[n];
        double sumX = 0, sumY = 0;
        LocalDateTime t0 = samples.get(0).time();
        for (int i = 0; i < n; i++) {
            x[i] = Duration.between(t0, samples.get(i).time()).toMillis() / 86400000.0;
            sumX += x[i];
            sumY += samples.get(i).value();
        }
        double meanX = sumX / n, meanY = sumY / n;
        double num = 0, den = 0;
        for (int i = 0; i < n; i++) {
            num += (x[i] - meanX) * (samples.get(i).value() - meanY);
            den += (x[i] - meanX) * (x[i] - meanX);
        }
        return den == 0 ? 0 : num / den;
    }

    private static boolean isNearBound(double value, InspectionTemplateItem item, double margin) {
        boolean nearMax = item.getNormalMax() != null && value >= item.getNormalMax() - margin;
        boolean nearMin = item.getNormalMin() != null && value <= item.getNormalMin() + margin;
        return nearMax || nearMin;
    }

    private TrendAlert createAlert(TrendRule rule, Equipment equipment,
                                   LocalDateTime windowStart, LocalDateTime windowEnd, LocalDateTime now) {
        TrendAlert alert = new TrendAlert();
        alert.setRuleId(rule.getId());
        alert.setRuleVersion(rule.getVersion());
        alert.setRuleName(rule.getName());
        alert.setEquipmentId(equipment.getId());
        alert.setEquipmentCode(equipment.getCode());
        alert.setEquipmentName(equipment.getName());
        alert.setTemplateItemId(rule.getTemplateItemId());
        alert.setItemName(rule.getItemName());
        alert.setLevel(rule.getLevel());
        alert.setStatus("open");
        alert.setWindowStart(windowStart);
        alert.setWindowEnd(windowEnd);
        alert.setFirstTriggeredAt(now);
        alert.setLastEvidenceAt(now);
        alert.setCooldownUntil(now.plusHours(rule.getCooldownHours()));
        return alertRepo.save(alert);
    }

    private void fillEvidence(TrendAlertEvidence evidence, TrendRule rule, Long equipmentId,
                              LocalDateTime windowStart, LocalDateTime windowEnd,
                              WindowStats stats, String metrics, String detail, boolean lateRecompute) {
        evidence.setRuleId(rule.getId());
        evidence.setRuleVersion(rule.getVersion());
        evidence.setEquipmentId(equipmentId);
        evidence.setTemplateItemId(rule.getTemplateItemId());
        evidence.setWindowStart(windowStart);
        evidence.setWindowEnd(windowEnd);
        evidence.setSampleCount(stats.samples().size());
        evidence.setMissingCount(stats.missingCount());
        evidence.setDuplicateCount(stats.duplicateCount());
        evidence.setSlope(stats.slope());
        evidence.setAmplitude(stats.amplitude());
        evidence.setNearBoundCount(stats.nearBoundTrailing());
        evidence.setTriggeredMetrics(metrics);
        evidence.setDetail(detail);
        evidence.setLateRecompute(lateRecompute);
    }

    private void updateAlertOnEvidence(TrendAlert alert, TrendRule rule, WindowStats stats,
                                       String metrics, LocalDateTime now) {
        alert.setLastEvidenceAt(now);
        LocalDateTime newCooldown = now.plusHours(rule.getCooldownHours());
        if (alert.getCooldownUntil() == null || newCooldown.isAfter(alert.getCooldownUntil())) {
            alert.setCooldownUntil(newCooldown);
        }
        alert.setSampleCount(stats.samples().size());
        alert.setSlope(stats.slope());
        alert.setAmplitude(stats.amplitude());
        alert.setNearBoundCount(stats.nearBoundTrailing());
        alert.setTriggerSummary("触发指标：" + metricNames(List.of(metrics.split(",")))
                + "；斜率 " + fmtNum(stats.slope()) + "/天，波动幅度 " + fmtNum(stats.amplitude())
                + "，连续接近边界 " + stats.nearBoundTrailing() + " 次");
        // 忽略到期后仍有新证据：自动重开并留痕
        if ("ignored".equals(alert.getStatus()) && alert.getIgnoreUntil() != null
                && now.isAfter(alert.getIgnoreUntil())) {
            alert.setStatus("open");
            logAction(alert.getId(), "reopen", "system", "忽略到期，新证据追加，预警自动重新打开", null);
        }
        alertRepo.save(alert);
        refreshActiveEvidenceCount(alert.getId());
    }

    private void refreshActiveEvidenceCount(Long alertId) {
        alertRepo.findById(alertId).ifPresent(a -> {
            a.setActiveEvidenceCount((int) evidenceRepo.countByAlertIdAndRetractedFalse(alertId));
            alertRepo.save(a);
        });
    }

    private void logAction(Long alertId, String action, String operator, String reason, String detail) {
        TrendAlertAction log = new TrendAlertAction();
        log.setAlertId(alertId);
        log.setAction(action);
        log.setOperator(operator == null ? "" : operator);
        log.setReason(reason == null ? "" : reason);
        log.setDetail(detail == null ? "" : detail);
        actionRepo.save(log);
    }

    private TrendEvaluation saveEvaluation(TrendRule rule, Long equipmentId, LocalDateTime anchor,
                                           LocalDateTime windowStart, String outcome, WindowStats stats,
                                           String metrics, String detail, boolean lateRecompute, Long alertId) {
        TrendEvaluation eval = new TrendEvaluation();
        eval.setRuleId(rule.getId());
        eval.setRuleVersion(rule.getVersion());
        eval.setEquipmentId(equipmentId);
        eval.setTemplateItemId(rule.getTemplateItemId());
        eval.setWindowStart(windowStart);
        eval.setWindowEnd(anchor);
        eval.setOutcome(outcome);
        eval.setSampleCount(stats.samples().size());
        eval.setMissingCount(stats.missingCount());
        eval.setDuplicateCount(stats.duplicateCount());
        eval.setSlope(stats.slope());
        eval.setAmplitude(stats.amplitude());
        eval.setNearBoundCount(stats.nearBoundTrailing());
        eval.setTriggeredMetrics(metrics);
        eval.setDetail(detail);
        eval.setLateRecompute(lateRecompute);
        eval.setAlertId(alertId);
        return evaluationRepo.save(eval);
    }

    private static String metricNames(List<String> metrics) {
        List<String> names = new ArrayList<>();
        for (String m : metrics) {
            switch (m) {
                case "slope" -> names.add("变化斜率");
                case "amplitude" -> names.add("波动幅度");
                case "near_bound" -> names.add("连续接近边界");
                default -> names.add(m);
            }
        }
        return String.join("、", names);
    }

    private static String fmtTime(LocalDateTime t) {
        return t == null ? "" : t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    private static String fmtNum(Double v) {
        if (v == null) return "-";
        String s = String.format(Locale.ROOT, "%.3f", v);
        while (s.contains(".") && s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
