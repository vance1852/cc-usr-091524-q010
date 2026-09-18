package com.admin.equipment.model.inspection;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 趋势预警事件。同一设备、项目、规则版本与窗口只产生一个预警（dedupeKey 唯一）；
 * 冷却期（或忽略期）内其它窗口触发的新证据追加到既有事件，不产生新预警。
 */
@Entity
@Table(name = "trend_alerts", indexes = {
        @Index(name = "idx_alerts_tuple", columnList = "equipment_id,template_item_id,rule_id,rule_version"),
        @Index(name = "idx_alerts_status", columnList = "status")
})
public class TrendAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 去重键：equipmentId:templateItemId:ruleId:ruleVersion:windowKey */
    @Column(name = "dedupe_key", nullable = false, unique = true, length = 191)
    private String dedupeKey;

    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    @Column(name = "rule_version", nullable = false)
    private Integer ruleVersion;

    @Column(name = "rule_name", length = 128)
    private String ruleName = "";

    /** 触发时规则参数快照（JSON），规则变更后本预警仍可解释 */
    @Column(name = "rule_params", length = 1024)
    private String ruleParams = "";

    @Column(length = 16)
    private String level = "medium";

    @Column(name = "equipment_id", nullable = false)
    private Long equipmentId;

    @Column(name = "equipment_code", length = 32)
    private String equipmentCode = "";

    @Column(name = "equipment_name", length = 128)
    private String equipmentName = "";

    @Column(name = "point_id")
    private Long pointId;

    @Column(name = "template_item_id", nullable = false)
    private Long templateItemId;

    @Column(name = "item_name", length = 128)
    private String itemName = "";

    /** 首个触发窗口的标识：rec-{窗口末条记录ID} */
    @Column(name = "window_key", length = 64)
    private String windowKey = "";

    @Column(name = "window_start_at")
    private LocalDateTime windowStartAt;

    @Column(name = "window_end_at")
    private LocalDateTime windowEndAt;

    /** 触发过的指标：slope / fluctuation / near_boundary，逗号分隔 */
    @Column(name = "triggered_metrics", length = 255)
    private String triggeredMetrics = "";

    @Column(length = 512)
    private String detail = "";

    /** 状态：open 未处理 / acknowledged 已确认 / ignored 已忽略 / converted 已转工单 */
    @Column(length = 16)
    private String status = "open";

    @Column(name = "first_triggered_at")
    private LocalDateTime firstTriggeredAt;

    @Column(name = "last_evidence_at")
    private LocalDateTime lastEvidenceAt;

    @Column(name = "cooldown_until")
    private LocalDateTime cooldownUntil;

    @Column(name = "ignore_until")
    private LocalDateTime ignoreUntil;

    @Column(name = "work_order_id")
    private Long workOrderId;

    /** 迟到数据重算后该窗口不再触发（保留事件与历史证据供审计） */
    @Column(name = "superseded")
    private Boolean superseded = false;

    @Version
    private Long version;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getDedupeKey() { return dedupeKey; }
    public void setDedupeKey(String dedupeKey) { this.dedupeKey = dedupeKey; }
    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }
    public Integer getRuleVersion() { return ruleVersion; }
    public void setRuleVersion(Integer ruleVersion) { this.ruleVersion = ruleVersion; }
    public String getRuleName() { return ruleName; }
    public void setRuleName(String ruleName) { this.ruleName = ruleName; }
    public String getRuleParams() { return ruleParams; }
    public void setRuleParams(String ruleParams) { this.ruleParams = ruleParams; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public Long getEquipmentId() { return equipmentId; }
    public void setEquipmentId(Long equipmentId) { this.equipmentId = equipmentId; }
    public String getEquipmentCode() { return equipmentCode; }
    public void setEquipmentCode(String equipmentCode) { this.equipmentCode = equipmentCode; }
    public String getEquipmentName() { return equipmentName; }
    public void setEquipmentName(String equipmentName) { this.equipmentName = equipmentName; }
    public Long getPointId() { return pointId; }
    public void setPointId(Long pointId) { this.pointId = pointId; }
    public Long getTemplateItemId() { return templateItemId; }
    public void setTemplateItemId(Long templateItemId) { this.templateItemId = templateItemId; }
    public String getItemName() { return itemName; }
    public void setItemName(String itemName) { this.itemName = itemName; }
    public String getWindowKey() { return windowKey; }
    public void setWindowKey(String windowKey) { this.windowKey = windowKey; }
    public LocalDateTime getWindowStartAt() { return windowStartAt; }
    public void setWindowStartAt(LocalDateTime windowStartAt) { this.windowStartAt = windowStartAt; }
    public LocalDateTime getWindowEndAt() { return windowEndAt; }
    public void setWindowEndAt(LocalDateTime windowEndAt) { this.windowEndAt = windowEndAt; }
    public String getTriggeredMetrics() { return triggeredMetrics; }
    public void setTriggeredMetrics(String triggeredMetrics) { this.triggeredMetrics = triggeredMetrics; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getFirstTriggeredAt() { return firstTriggeredAt; }
    public void setFirstTriggeredAt(LocalDateTime firstTriggeredAt) { this.firstTriggeredAt = firstTriggeredAt; }
    public LocalDateTime getLastEvidenceAt() { return lastEvidenceAt; }
    public void setLastEvidenceAt(LocalDateTime lastEvidenceAt) { this.lastEvidenceAt = lastEvidenceAt; }
    public LocalDateTime getCooldownUntil() { return cooldownUntil; }
    public void setCooldownUntil(LocalDateTime cooldownUntil) { this.cooldownUntil = cooldownUntil; }
    public LocalDateTime getIgnoreUntil() { return ignoreUntil; }
    public void setIgnoreUntil(LocalDateTime ignoreUntil) { this.ignoreUntil = ignoreUntil; }
    public Long getWorkOrderId() { return workOrderId; }
    public void setWorkOrderId(Long workOrderId) { this.workOrderId = workOrderId; }
    public Boolean getSuperseded() { return superseded; }
    public void setSuperseded(Boolean superseded) { this.superseded = superseded; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
