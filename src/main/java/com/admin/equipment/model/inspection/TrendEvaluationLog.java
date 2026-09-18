package com.admin.equipment.model.inspection;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 趋势评估日志：每个被评估的窗口一条留痕，
 * 用于解释触发、未触发、样本不足、迟到重算与规则失效。
 */
@Entity
@Table(name = "trend_evaluation_logs", indexes = {
        @Index(name = "idx_eval_tuple", columnList = "equipment_id,template_item_id,rule_id"),
        @Index(name = "idx_eval_alert", columnList = "alert_id")
})
public class TrendEvaluationLog {

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

    /** 触发本次评估的巡检记录 */
    @Column(name = "record_id")
    private Long recordId;

    @Column(name = "window_key", length = 64)
    private String windowKey = "";

    /** 结果：triggered / not_triggered / insufficient_samples / rule_inactive */
    @Column(length = 32)
    private String result = "";

    @Column(name = "sample_count")
    private Integer sampleCount;

    @Column(name = "window_size")
    private Integer windowSize;

    @Column(name = "min_samples")
    private Integer minSamples;

    @Column(length = 512)
    private String detail = "";

    /** 是否迟到数据引起的重算 */
    @Column(name = "late_recompute")
    private Boolean lateRecompute = false;

    /** 本次评估产生/更新的预警 */
    @Column(name = "alert_id")
    private Long alertId;

    @Column(name = "evaluated_at")
    private LocalDateTime evaluatedAt = LocalDateTime.now();

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
    public Long getRecordId() { return recordId; }
    public void setRecordId(Long recordId) { this.recordId = recordId; }
    public String getWindowKey() { return windowKey; }
    public void setWindowKey(String windowKey) { this.windowKey = windowKey; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public Integer getSampleCount() { return sampleCount; }
    public void setSampleCount(Integer sampleCount) { this.sampleCount = sampleCount; }
    public Integer getWindowSize() { return windowSize; }
    public void setWindowSize(Integer windowSize) { this.windowSize = windowSize; }
    public Integer getMinSamples() { return minSamples; }
    public void setMinSamples(Integer minSamples) { this.minSamples = minSamples; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public Boolean getLateRecompute() { return lateRecompute; }
    public void setLateRecompute(Boolean lateRecompute) { this.lateRecompute = lateRecompute; }
    public Long getAlertId() { return alertId; }
    public void setAlertId(Long alertId) { this.alertId = alertId; }
    public LocalDateTime getEvaluatedAt() { return evaluatedAt; }
    public void setEvaluatedAt(LocalDateTime evaluatedAt) { this.evaluatedAt = evaluatedAt; }
}
