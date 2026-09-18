package com.admin.equipment.service.inspection;

import com.admin.equipment.model.inspection.InspectionTemplateItem;
import com.admin.equipment.model.inspection.TrendRule;
import com.admin.equipment.repo.inspection.InspectionTemplateItemRepository;
import com.admin.equipment.repo.inspection.TrendRuleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 趋势预警规则的发布与维护。
 * 规则参数修改会使 version 自增，新版本只作用于之后的评估；
 * 历史预警、证据与评估日志保留产生它们时的规则版本号。
 */
@Service
public class TrendRuleService {

    private static final Set<String> LEVELS = Set.of("low", "medium", "high", "urgent");

    private final TrendRuleRepository ruleRepo;
    private final InspectionTemplateItemRepository itemRepo;

    public TrendRuleService(TrendRuleRepository ruleRepo, InspectionTemplateItemRepository itemRepo) {
        this.ruleRepo = ruleRepo;
        this.itemRepo = itemRepo;
    }

    public record RuleSpec(String name, String equipmentType, Long templateItemId,
                           Integer windowHours, Integer minSamples,
                           Double slopeThreshold, Double amplitudeThreshold,
                           Double nearBoundMargin, Integer nearBoundLimit,
                           String level, Integer cooldownHours) {}

    public List<TrendRule> list(String equipmentType, Boolean enabled) {
        return ruleRepo.findAllByOrderByIdDesc().stream()
                .filter(r -> equipmentType == null || equipmentType.isBlank()
                        || equipmentType.equals(r.getEquipmentType()))
                .filter(r -> enabled == null || enabled.equals(r.getEnabled()))
                .toList();
    }

    public Optional<TrendRule> getById(Long id) {
        return ruleRepo.findById(id);
    }

    @Transactional
    public TrendRule create(RuleSpec spec, String createdBy) {
        InspectionTemplateItem item = validate(spec);
        TrendRule rule = new TrendRule();
        apply(rule, spec, item);
        rule.setVersion(1);
        rule.setEnabled(true);
        rule.setCreatedBy(createdBy == null ? "" : createdBy);
        rule.setCreatedAt(LocalDateTime.now());
        rule.setUpdatedAt(LocalDateTime.now());
        return ruleRepo.save(rule);
    }

    /** 参数更新：版本号自增，仅影响之后的评估 */
    @Transactional
    public TrendRule update(Long id, RuleSpec spec) {
        TrendRule rule = ruleRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("规则不存在"));
        InspectionTemplateItem item = validate(spec);
        apply(rule, spec, item);
        rule.setVersion((rule.getVersion() == null ? 1 : rule.getVersion()) + 1);
        rule.setUpdatedAt(LocalDateTime.now());
        return ruleRepo.save(rule);
    }

    /** 停用/启用：不改变版本号；停用后新数据不再评估，历史结论保留 */
    @Transactional
    public TrendRule setEnabled(Long id, boolean enabled) {
        TrendRule rule = ruleRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("规则不存在"));
        rule.setEnabled(enabled);
        rule.setUpdatedAt(LocalDateTime.now());
        return ruleRepo.save(rule);
    }

    /** 规则设备类型匹配：空或 * 表示全部类型 */
    public static boolean typeMatches(String ruleEquipmentType, String equipmentType) {
        if (ruleEquipmentType == null || ruleEquipmentType.isBlank() || "*".equals(ruleEquipmentType)) {
            return true;
        }
        return ruleEquipmentType.equals(equipmentType == null ? "" : equipmentType);
    }

    private void apply(TrendRule rule, RuleSpec spec, InspectionTemplateItem item) {
        rule.setName(spec.name().trim());
        rule.setEquipmentType(spec.equipmentType() == null ? "" : spec.equipmentType().trim());
        rule.setTemplateItemId(item.getId());
        rule.setItemName(item.getName());
        rule.setWindowHours(spec.windowHours() == null ? 168 : spec.windowHours());
        rule.setMinSamples(spec.minSamples() == null ? 3 : spec.minSamples());
        rule.setSlopeThreshold(spec.slopeThreshold());
        rule.setAmplitudeThreshold(spec.amplitudeThreshold());
        rule.setNearBoundMargin(spec.nearBoundMargin());
        rule.setNearBoundLimit(spec.nearBoundLimit());
        rule.setLevel(spec.level() == null || spec.level().isBlank() ? "medium" : spec.level());
        rule.setCooldownHours(spec.cooldownHours() == null ? 24 : spec.cooldownHours());
    }

    private InspectionTemplateItem validate(RuleSpec spec) {
        if (spec == null) throw new IllegalArgumentException("规则参数必填");
        if (spec.name() == null || spec.name().isBlank()) throw new IllegalArgumentException("规则名称必填");
        if (spec.templateItemId() == null) throw new IllegalArgumentException("模板项目必填");
        InspectionTemplateItem item = itemRepo.findById(spec.templateItemId())
                .orElseThrow(() -> new IllegalArgumentException("模板项目不存在"));
        if (!"numeric".equals(item.getType())) {
            throw new IllegalArgumentException("趋势规则仅支持数值型模板项目");
        }
        int windowHours = spec.windowHours() == null ? 168 : spec.windowHours();
        if (windowHours < 1 || windowHours > 24 * 366) {
            throw new IllegalArgumentException("滚动窗口需在 1~8784 小时之间");
        }
        int minSamples = spec.minSamples() == null ? 3 : spec.minSamples();
        if (minSamples < 2) {
            throw new IllegalArgumentException("最少样本不能小于 2");
        }
        boolean hasSlope = spec.slopeThreshold() != null;
        boolean hasAmplitude = spec.amplitudeThreshold() != null;
        boolean hasNearBound = spec.nearBoundLimit() != null || spec.nearBoundMargin() != null;
        if (!hasSlope && !hasAmplitude && !hasNearBound) {
            throw new IllegalArgumentException("至少配置一项触发指标：变化斜率、波动幅度或连续接近边界");
        }
        if (hasSlope && spec.slopeThreshold() <= 0) {
            throw new IllegalArgumentException("变化斜率阈值必须大于 0");
        }
        if (hasAmplitude && spec.amplitudeThreshold() <= 0) {
            throw new IllegalArgumentException("波动幅度阈值必须大于 0");
        }
        if (hasNearBound) {
            if (spec.nearBoundMargin() == null || spec.nearBoundLimit() == null) {
                throw new IllegalArgumentException("连续接近边界需同时提供边界余量与次数阈值");
            }
            if (spec.nearBoundMargin() <= 0) throw new IllegalArgumentException("边界余量必须大于 0");
            if (spec.nearBoundLimit() < 1) throw new IllegalArgumentException("连续接近边界次数阈值不能小于 1");
            if (item.getNormalMin() == null && item.getNormalMax() == null) {
                throw new IllegalArgumentException("该模板项目未配置正常上下限，无法使用连续接近边界指标");
            }
        }
        if (spec.level() != null && !spec.level().isBlank() && !LEVELS.contains(spec.level())) {
            throw new IllegalArgumentException("预警等级不合法，可选：low/medium/high/urgent");
        }
        int cooldown = spec.cooldownHours() == null ? 24 : spec.cooldownHours();
        if (cooldown < 0) {
            throw new IllegalArgumentException("冷却期不能为负数");
        }
        return item;
    }
}
