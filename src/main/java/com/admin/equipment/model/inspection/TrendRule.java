package com.admin.equipment.model.inspection;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 趋势预警规则：针对设备类型 + 数值型模板项目发布。
 * 更新已发布规则会使 ruleVersion 递增，新版本只作用于之后的评估；
 * 正常范围在规则保存时从模板项快照，模板后续调整不影响已发布版本的结论。
 */
@Entity
@Table(name = "trend_rules")
public class TrendRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    /** 适用设备类型，空 = 全部类型 */
    @Column(name = "equipment_type", length = 32)
    private String equipmentType = "";

    @Column(name = "template_item_id", nullable = false)
    private Long templateItemId;

    @Column(name = "item_name", length = 128)
    private String itemName = "";

    /** 滚动窗口：最近 N 个有效样本 */
    @Column(name = "window_size")
    private Integer windowSize = 10;

    /** 窗口内最少样本数，不足则不评估 */
    @Column(name = "min_samples")
    private Integer minSamples = 5;

    /** 变化斜率阈值（单位/小时；正数=上升，负数=下降） */
    @Column(name = "slope_threshold")
    private Double slopeThreshold;

    /** 波动幅度阈值（窗口内 max-min） */
    @Column(name = "fluctuation_amplitude")
    private Double fluctuationAmplitude;

    /** 接近边界裕度（与正常上下限的距离） */
    @Column(name = "near_margin")
    private Double nearMargin;

    /** 连续接近边界次数阈值 */
    @Column(name = "near_boundary_count")
    private Integer nearBoundaryCount;

    /** 发布时模板项正常范围快照 */
    @Column(name = "normal_min")
    private Double normalMin;

    @Column(name = "normal_max")
    private Double normalMax;

    /** 预警等级：low / medium / high / urgent */
    @Column(length = 16)
    private String level = "medium";

    /** 冷却期（小时）：期内新证据并入既有事件 */
    @Column(name = "cooldown_hours")
    private Integer cooldownHours = 24;

    @Column(name = "rule_version")
    private Integer ruleVersion = 1;

    /** 状态：draft 草稿 / published 已发布 / disabled 已停用 */
    @Column(length = 16)
    private String status = "draft";

    @Column(name = "created_by", length = 64)
    private String createdBy = "";

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

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
    public Integer getWindowSize() { return windowSize; }
    public void setWindowSize(Integer windowSize) { this.windowSize = windowSize; }
    public Integer getMinSamples() { return minSamples; }
    public void setMinSamples(Integer minSamples) { this.minSamples = minSamples; }
    public Double getSlopeThreshold() { return slopeThreshold; }
    public void setSlopeThreshold(Double slopeThreshold) { this.slopeThreshold = slopeThreshold; }
    public Double getFluctuationAmplitude() { return fluctuationAmplitude; }
    public void setFluctuationAmplitude(Double fluctuationAmplitude) { this.fluctuationAmplitude = fluctuationAmplitude; }
    public Double getNearMargin() { return nearMargin; }
    public void setNearMargin(Double nearMargin) { this.nearMargin = nearMargin; }
    public Integer getNearBoundaryCount() { return nearBoundaryCount; }
    public void setNearBoundaryCount(Integer nearBoundaryCount) { this.nearBoundaryCount = nearBoundaryCount; }
    public Double getNormalMin() { return normalMin; }
    public void setNormalMin(Double normalMin) { this.normalMin = normalMin; }
    public Double getNormalMax() { return normalMax; }
    public void setNormalMax(Double normalMax) { this.normalMax = normalMax; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public Integer getCooldownHours() { return cooldownHours; }
    public void setCooldownHours(Integer cooldownHours) { this.cooldownHours = cooldownHours; }
    public Integer getRuleVersion() { return ruleVersion; }
    public void setRuleVersion(Integer ruleVersion) { this.ruleVersion = ruleVersion; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
