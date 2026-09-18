package com.admin.equipment.model.inspection;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 预警触发证据：一次触发评估的完整快照。
 * 同一设备、项目、规则版本与窗口的证据全局唯一（幂等键），
 * 迟到数据重算只更新既有证据，不产生重复记录。
 */
@Entity
@Table(name = "inspection_trend_evidences", uniqueConstraints = @UniqueConstraint(
        name = "uk_trend_evidence_window",
        columnNames = {"rule_id", "rule_version", "equipment_id", "template_item_id", "window_end"}),
        indexes = @Index(name = "idx_trend_evidence_alert", columnList = "alert_id"))
public class TrendAlertEvidence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "alert_id", nullable = false)
    private Long alertId;

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

    /** 窗口内有效样本数（同刻读数已合并、缺失值已剔除） */
    @Column(name = "sample_count")
    private Integer sampleCount = 0;

    /** 窗口内缺失数值的记录数 */
    @Column(name = "missing_count")
    private Integer missingCount = 0;

    /** 窗口内因同刻重复被合并的读数个数 */
    @Column(name = "duplicate_count")
    private Integer duplicateCount = 0;

    /** 最小二乘斜率（单位/天） */
    @Column(name = "slope")
    private Double slope;

    /** 波动幅度：窗口内 max-min */
    @Column(name = "amplitude")
    private Double amplitude;

    /** 最新连续接近边界次数 */
    @Column(name = "near_bound_count")
    private Integer nearBoundCount = 0;

    /** 触发的指标，逗号分隔：slope / amplitude / near_bound */
    @Column(name = "triggered_metrics", length = 64)
    private String triggeredMetrics = "";

    @Column(length = 1024)
    private String detail = "";

    /** 是否由迟到数据触发的重算产生 */
    @Column(name = "late_recompute")
    private Boolean lateRecompute = false;

    /** 迟到重算后不再满足触发条件时置为 true，证据保留用于审计 */
    @Column(name = "retracted")
    private Boolean retracted = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAlertId() { return alertId; }
    public void setAlertId(Long alertId) { this.alertId = alertId; }
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
    public Boolean getRetracted() { return retracted; }
    public void setRetracted(Boolean retracted) { this.retracted = retracted; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
