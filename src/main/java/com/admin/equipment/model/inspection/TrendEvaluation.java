package com.admin.equipment.model.inspection;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 趋势评估日志：每次窗口评估的完整记录。
 * 用于解释样本不足、迟到重算与规则版本失效，保证结论可复现、可审计。
 */
@Entity
@Table(name = "inspection_trend_evaluations", indexes = {
        @Index(name = "idx_trend_eval_series", columnList = "rule_id,equipment_id,template_item_id,id"),
        @Index(name = "idx_trend_eval_equipment", columnList = "equipment_id,template_item_id,id")
})
public class TrendEvaluation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    @Column(name = "rule_version", nullable = false)
    private Integer ruleVersion;

    @Column(name = "equipment_id", nullable = false)
    private Long equipmentId;

    @Column(name = "template_item_id", nullable = false)
    private Long templateItemId;

    @Column(name = "window_start", nullable = false)
    private LocalDateTime windowStart;

    @Column(name = "window_end", nullable = false)
    private LocalDateTime windowEnd;

    /** 结果：triggered 触发 / not_triggered 未触发 / insufficient_samples 样本不足 */
    @Column(name = "outcome", length = 32)
    private String outcome;

    @Column(name = "sample_count")
    private Integer sampleCount = 0;

    @Column(name = "missing_count")
    private Integer missingCount = 0;

    @Column(name = "duplicate_count")
    private Integer duplicateCount = 0;

    @Column(name = "slope")
    private Double slope;

    @Column(name = "amplitude")
    private Double amplitude;

    @Column(name = "near_bound_count")
    private Integer nearBoundCount = 0;

    @Column(name = "triggered_metrics", length = 64)
    private String triggeredMetrics = "";

    @Column(length = 1024)
    private String detail = "";

    /** 本次评估是否由迟到数据触发（含受影响窗口的重算） */
    @Column(name = "late_recompute")
    private Boolean lateRecompute = false;

    /** 触发时关联的预警事件ID */
    @Column(name = "alert_id")
    private Long alertId;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }
    public Integer getRuleVersion() { return ruleVersion; }
    public void setRuleVersion(Integer ruleVersion) { this.ruleVersion = ruleVersion; }
    public Long getEquipmentId() { return equipmentId; }
    public void setEquipmentId(Long equipmentId) { this.equipmentId = equipmentId; }
    public Long getTemplateItemId() { return templateItemId; }
    public void setTemplateItemId(Long templateItemId) { this.templateItemId = templateItemId; }
    public LocalDateTime getWindowStart() { return windowStart; }
    public void setWindowStart(LocalDateTime windowStart) { this.windowStart = windowStart; }
    public LocalDateTime getWindowEnd() { return windowEnd; }
    public void setWindowEnd(LocalDateTime windowEnd) { this.windowEnd = windowEnd; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public Integer getSampleCount() { return sampleCount; }
    public void setSampleCount(Integer sampleCount) { this.sampleCount = sampleCount; }
    public Integer getMissingCount() { return missingCount; }
    public void setMissingCount(Integer missingCount) { this.missingCount = missingCount; }
    public Integer getDuplicateCount() { return duplicateCount; }
    public void setDuplicateCount(Integer duplicateCount) { this.duplicateCount = duplicateCount; }
    public Double getSlope() { return slope; }
    public void setSlope(Double slope) { this.slope = slope; }
    public Double getAmplitude() { return amplitude; }
    public void setAmplitude(Double amplitude) { this.amplitude = amplitude; }
    public Integer getNearBoundCount() { return nearBoundCount; }
    public void setNearBoundCount(Integer nearBoundCount) { this.nearBoundCount = nearBoundCount; }
    public String getTriggeredMetrics() { return triggeredMetrics; }
    public void setTriggeredMetrics(String triggeredMetrics) { this.triggeredMetrics = triggeredMetrics; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public Boolean getLateRecompute() { return lateRecompute; }
    public void setLateRecompute(Boolean lateRecompute) { this.lateRecompute = lateRecompute; }
    public Long getAlertId() { return alertId; }
    public void setAlertId(Long alertId) { this.alertId = alertId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
