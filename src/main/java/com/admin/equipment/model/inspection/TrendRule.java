package com.admin.equipment.model.inspection;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 趋势预警规则：针对设备类型 + 数值型模板项目发布。
 * 每次参数修改 version 自增，新版本只作用于之后的评估，历史预警与评估保留旧版本号。
 */
@Entity
@Table(name = "inspection_trend_rules")
public class TrendRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    /** 设备类型，空串或 * 表示匹配全部类型 */
    @Column(name = "equipment_type", length = 32)
    private String equipmentType = "";

    @Column(name = "template_item_id", nullable = false)
    private Long templateItemId;

    /** 模板项目名称快照，便于规则列表展示 */
    @Column(name = "item_name", length = 128)
    private String itemName = "";

    /** 滚动窗口长度（小时），按采样时间回看 */
    @Column(name = "window_hours", nullable = false)
    private Integer windowHours = 168;

    /** 窗口内有效样本少于此值时不评估 */
    @Column(name = "min_samples", nullable = false)
    private Integer minSamples = 3;

    /** 变化斜率阈值（单位/天，按绝对值判定），null 表示不启用 */
    @Column(name = "slope_threshold")
    private Double slopeThreshold;

    /** 波动幅度阈值（窗口内 max-min），null 表示不启用 */
    @Column(name = "amplitude_threshold")
    private Double amplitudeThreshold;

    /** 接近边界的余量：读数距正常上/下限在此范围内视为接近边界 */
    @Column(name = "near_bound_margin")
    private Double nearBoundMargin;

    /** 连续接近边界次数阈值，null 表示不启用 */
    @Column(name = "near_bound_limit")
    private Integer nearBoundLimit;

    /** 预警等级：low / medium / high / urgent */
    @Column(length = 16)
    private String level = "medium";

    /** 冷却期（小时）：触发后该时长内的新证据追加到既有事件，不再新开预警 */
    @Column(name = "cooldown_hours", nullable = false)
    private Integer cooldownHours = 24;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(nullable = false)
    private Integer version = 1;

    @Column(name = "created_by", length = 64)
    private String createdBy = "";

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEquipmentType() { return equipmentType; }
    public void setEquipmentType(String equipmentType) { this.equipmentType = equipmentType; }
    public Long getTemplateItemId() { return templateItemId; }
    public void setTemplateItemId(Long templateItemId) { this.templateItemId = templateItemId; }
    public String getItemName() { return itemName; }
    public void setItemName(String itemName) { this.itemName = itemName; }
    public Integer getWindowHours() { return windowHours; }
    public void setWindowHours(Integer windowHours) { this.windowHours = windowHours; }
    public Integer getMinSamples() { return minSamples; }
    public void setMinSamples(Integer minSamples) { this.minSamples = minSamples; }
    public Double getSlopeThreshold() { return slopeThreshold; }
    public void setSlopeThreshold(Double slopeThreshold) { this.slopeThreshold = slopeThreshold; }
    public Double getAmplitudeThreshold() { return amplitudeThreshold; }
    public void setAmplitudeThreshold(Double amplitudeThreshold) { this.amplitudeThreshold = amplitudeThreshold; }
    public Double getNearBoundMargin() { return nearBoundMargin; }
    public void setNearBoundMargin(Double nearBoundMargin) { this.nearBoundMargin = nearBoundMargin; }
    public Integer getNearBoundLimit() { return nearBoundLimit; }
    public void setNearBoundLimit(Integer nearBoundLimit) { this.nearBoundLimit = nearBoundLimit; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public Integer getCooldownHours() { return cooldownHours; }
    public void setCooldownHours(Integer cooldownHours) { this.cooldownHours = cooldownHours; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
