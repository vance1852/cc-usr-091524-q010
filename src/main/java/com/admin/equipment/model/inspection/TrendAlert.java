package com.admin.equipment.model.inspection;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 趋势预警事件。同一设备、模板项目、规则版本与触发窗口只产生一个事件
 * （数据库唯一约束兜底），冷却期内的新触发以证据形式追加到本事件。
 */
@Entity
@Table(name = "inspection_trend_alerts", uniqueConstraints = @UniqueConstraint(
        name = "uk_trend_alert_window",
        columnNames = {"rule_id", "rule_version", "equipment_id", "template_item_id", "window_end"}))
public class TrendAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    @Column(name = "rule_version", nullable = false)
    private Integer ruleVersion;

    @Column(name = "rule_name", length = 128)
    private String ruleName = "";

    @Column(name = "equipment_id", nullable = false)
    private Long equipmentId;

    @Column(name = "equipment_code", length = 32)
    private String equipmentCode = "";

    @Column(name = "equipment_name", length = 128)
    private String equipmentName = "";

    @Column(name = "template_item_id", nullable = false)
    private Long templateItemId;

    @Column(name = "item_name", length = 128)
    private String itemName = "";

    /** 预警等级：low / medium / high / urgent */
    @Column(length = 16)
    private String level = "medium";

    /** 状态：open 待处理 / acknowledged 已确认 / ignored 已忽略 / wo_created 已转工单 */
    @Column(length = 16)
    private String status = "open";

    /** 首次触发窗口（事件身份的一部分，创建后不变） */
    @Column(name = "window_start", nullable = false)
    private LocalDateTime windowStart;

    @Column(name = "window_end", nullable = false)
    private LocalDateTime windowEnd;

    @Column(name = "first_triggered_at")
    private LocalDateTime firstTriggeredAt = LocalDateTime.now();

    @Column(name = "last_evidence_at")
    private LocalDateTime lastEvidenceAt;

    /** 冷却截止：该时间之前的再次触发只追加证据 */
    @Column(name = "cooldown_until")
    private LocalDateTime cooldownUntil;

    /** 最近一次证据的指标快照 */
    @Column(name = "sample_count")
    private Integer sampleCount = 0;

    @Column(name = "slope")
    private Double slope;

    @Column(name = "amplitude")
    private Double amplitude;

    @Column(name = "near_bound_count")
    private Integer nearBoundCount = 0;

    @Column(name = "trigger_summary", length = 512)
    private String triggerSummary = "";

    @Column(name = "active_evidence_count")
    private Integer activeEvidenceCount = 1;

    @Column(name = "acknowledged_by", length = 64)
    private String acknowledgedBy;

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    @Column(name = "acknowledge_reason", length = 512)
    private String acknowledgeReason;

    @Column(name = "ignored_by", length = 64)
    private String ignoredBy;

    @Column(name = "ignored_at")
    private LocalDateTime ignoredAt;

    @Column(name = "ignore_reason", length = 512)
    private String ignoreReason;

    /** 忽略到期时间，到期后预警恢复为待处理 */
    @Column(name = "ignore_until")
    private LocalDateTime ignoreUntil;

    @Column(name = "work_order_id")
    private Long workOrderId;

    @Column(name = "wo_created_by", length = 64)
    private String woCreatedBy;

    @Column(name = "wo_created_at")
    private LocalDateTime woCreatedAt;

    @Column(name = "wo_reason", length = 512)
    private String woReason;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }
    public Integer getRuleVersion() { return ruleVersion; }
    public void setRuleVersion(Integer ruleVersion) { this.ruleVersion = ruleVersion; }
    public String getRuleName() { return ruleName; }
    public void setRuleName(String ruleName) { this.ruleName = ruleName; }
    public Long getEquipmentId() { return equipmentId; }
    public void setEquipmentId(Long equipmentId) { this.equipmentId = equipmentId; }
    public String getEquipmentCode() { return equipmentCode; }
    public void setEquipmentCode(String equipmentCode) { this.equipmentCode = equipmentCode; }
    public String getEquipmentName() { return equipmentName; }
    public void setEquipmentName(String equipmentName) { this.equipmentName = equipmentName; }
    public Long getTemplateItemId() { return templateItemId; }
    public void setTemplateItemId(Long templateItemId) { this.templateItemId = templateItemId; }
    public String getItemName() { return itemName; }
    public void setItemName(String itemName) { this.itemName = itemName; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getWindowStart() { return windowStart; }
    public void setWindowStart(LocalDateTime windowStart) { this.windowStart = windowStart; }
    public LocalDateTime getWindowEnd() { return windowEnd; }
    public void setWindowEnd(LocalDateTime windowEnd) { this.windowEnd = windowEnd; }
    public LocalDateTime getFirstTriggeredAt() { return firstTriggeredAt; }
    public void setFirstTriggeredAt(LocalDateTime firstTriggeredAt) { this.firstTriggeredAt = firstTriggeredAt; }
    public LocalDateTime getLastEvidenceAt() { return lastEvidenceAt; }
    public void setLastEvidenceAt(LocalDateTime lastEvidenceAt) { this.lastEvidenceAt = lastEvidenceAt; }
    public LocalDateTime getCooldownUntil() { return cooldownUntil; }
    public void setCooldownUntil(LocalDateTime cooldownUntil) { this.cooldownUntil = cooldownUntil; }
    public Integer getSampleCount() { return sampleCount; }
    public void setSampleCount(Integer sampleCount) { this.sampleCount = sampleCount; }
    public Double getSlope() { return slope; }
    public void setSlope(Double slope) { this.slope = slope; }
    public Double getAmplitude() { return amplitude; }
    public void setAmplitude(Double amplitude) { this.amplitude = amplitude; }
    public Integer getNearBoundCount() { return nearBoundCount; }
    public void setNearBoundCount(Integer nearBoundCount) { this.nearBoundCount = nearBoundCount; }
    public String getTriggerSummary() { return triggerSummary; }
    public void setTriggerSummary(String triggerSummary) { this.triggerSummary = triggerSummary; }
    public Integer getActiveEvidenceCount() { return activeEvidenceCount; }
    public void setActiveEvidenceCount(Integer activeEvidenceCount) { this.activeEvidenceCount = activeEvidenceCount; }
    public String getAcknowledgedBy() { return acknowledgedBy; }
    public void setAcknowledgedBy(String acknowledgedBy) { this.acknowledgedBy = acknowledgedBy; }
    public LocalDateTime getAcknowledgedAt() { return acknowledgedAt; }
    public void setAcknowledgedAt(LocalDateTime acknowledgedAt) { this.acknowledgedAt = acknowledgedAt; }
    public String getAcknowledgeReason() { return acknowledgeReason; }
    public void setAcknowledgeReason(String acknowledgeReason) { this.acknowledgeReason = acknowledgeReason; }
    public String getIgnoredBy() { return ignoredBy; }
    public void setIgnoredBy(String ignoredBy) { this.ignoredBy = ignoredBy; }
    public LocalDateTime getIgnoredAt() { return ignoredAt; }
    public void setIgnoredAt(LocalDateTime ignoredAt) { this.ignoredAt = ignoredAt; }
    public String getIgnoreReason() { return ignoreReason; }
    public void setIgnoreReason(String ignoreReason) { this.ignoreReason = ignoreReason; }
    public LocalDateTime getIgnoreUntil() { return ignoreUntil; }
    public void setIgnoreUntil(LocalDateTime ignoreUntil) { this.ignoreUntil = ignoreUntil; }
    public Long getWorkOrderId() { return workOrderId; }
    public void setWorkOrderId(Long workOrderId) { this.workOrderId = workOrderId; }
    public String getWoCreatedBy() { return woCreatedBy; }
    public void setWoCreatedBy(String woCreatedBy) { this.woCreatedBy = woCreatedBy; }
    public LocalDateTime getWoCreatedAt() { return woCreatedAt; }
    public void setWoCreatedAt(LocalDateTime woCreatedAt) { this.woCreatedAt = woCreatedAt; }
    public String getWoReason() { return woReason; }
    public void setWoReason(String woReason) { this.woReason = woReason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
