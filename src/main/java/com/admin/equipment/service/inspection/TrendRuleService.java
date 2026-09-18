package com.admin.equipment.service.inspection;

import com.admin.equipment.model.inspection.InspectionTemplateItem;
import com.admin.equipment.model.inspection.TrendRule;
import com.admin.equipment.repo.inspection.InspectionTemplateItemRepository;
import com.admin.equipment.repo.inspection.TrendRuleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 趋势预警规则管理：草稿 → 发布 → 停用/启用。
 * 更新已发布规则会使版本递增，新版本只作用于之后的评估。
 */
@Service
public class TrendRuleService {

    private final TrendRuleRepository ruleRepo;
    private final InspectionTemplateItemRepository itemRepo;
    private final TrendEvaluationService evaluationService;

    public TrendRuleService(TrendRuleRepository ruleRepo,
                            InspectionTemplateItemRepository itemRepo,
                            TrendEvaluationService evaluationService) {
        this.ruleRepo = ruleRepo;
        this.itemRepo = itemRepo;
        this.evaluationService = evaluationService;
    }

    public record RuleSpec(String name, String equipmentType, Long templateItemId,
                           Integer windowSize, Integer minSamples,
                           Double slopeThreshold, Double fluctuationAmplitude,
                           Double nearMargin, Integer nearBoundaryCount,
                           String level, Integer cooldownHours) {}

    public List<TrendRule> list(String status, String equipmentType, Long templateItemId) {
        return ruleRepo.findAllByOrderByIdDesc().stream()
                .filter(r -> status == null || status.isBlank() || status.equals(r.getStatus()))
                .filter(r -> equipmentType == null || equipmentType.isBlank()
                        || equipmentType.equals(r.getEquipmentType()))
                .filter(r -> templateItemId == null || templateItemId.equals(r.getTemplateItemId()))
                .toList();
    }

    public Optional<TrendRule> getById(Long id) {
        return ruleRepo.findById(id);
    }

    @Transactional
    public TrendRule create(RuleSpec spec, String createdBy) {
        if (spec.name() == null || spec.name().isBlank()) {
            throw new IllegalArgumentException("规则名称必填");
        }
        TrendRule rule = new TrendRule();
        rule.setName(spec.name().trim());
        rule.setEquipmentType(spec.equipmentType() == null ? "" : spec.equipmentType().trim());
        applySpec(rule, spec);
        rule.setStatus("draft");
        rule.setRuleVersion(1);
        rule.setCreatedBy(createdBy == null ? "" : createdBy);
        return ruleRepo.save(rule);
    }

    /** 全量更新规则参数；已发布的规则更新后版本递增，只作用于之后的评估。 */
    @Transactional
    public TrendRule update(Long id, RuleSpec spec) {
        TrendRule rule = ruleRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("规则不存在"));
        if (spec.name() == null || spec.name().isBlank()) {
            throw new IllegalArgumentException("规则名称必填");
        }
        rule.setName(spec.name().trim());
        rule.setEquipmentType(spec.equipmentType() == null ? "" : spec.equipmentType().trim());
        applySpec(rule, spec);
        if ("published".equals(rule.getStatus())) {
            rule.setRuleVersion((rule.getRuleVersion() == null ? 1 : rule.getRuleVersion()) + 1);
            rule.setPublishedAt(LocalDateTime.now());
        }
        rule.setUpdatedAt(LocalDateTime.now());
        return ruleRepo.save(rule);
    }

    @Transactional
    public TrendRule publish(Long id) {
        TrendRule rule = ruleRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("规则不存在"));
        if (!"draft".equals(rule.getStatus())) {
            throw new IllegalArgumentException("仅草稿状态的规则可以发布，当前状态：" + rule.getStatus());
        }
        rule.setStatus("published");
        rule.setPublishedAt(LocalDateTime.now());
        rule.setUpdatedAt(LocalDateTime.now());
        return ruleRepo.save(rule);
    }

    @Transactional
    public TrendRule disable(Long id) {
        TrendRule rule = ruleRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("规则不存在"));
        if ("disabled".equals(rule.getStatus())) {
            throw new IllegalArgumentException("规则已停用");
        }
        rule.setStatus("disabled");
        rule.setUpdatedAt(LocalDateTime.now());
        return ruleRepo.save(rule);
    }

    @Transactional
    public TrendRule enable(Long id) {
        TrendRule rule = ruleRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("规则不存在"));
        if (!"disabled".equals(rule.getStatus())) {
            throw new IllegalArgumentException("仅已停用的规则可以重新启用");
        }
        rule.setStatus("published");
        rule.setPublishedAt(LocalDateTime.now());
        rule.setUpdatedAt(LocalDateTime.now());
        return ruleRepo.save(rule);
    }

    /** 手动重算：对指定设备按规则当前版本回放全部历史窗口（幂等）。 */
    public Map<String, Object> reevaluate(Long id, Long equipmentId) {
        if (equipmentId == null) {
            throw new IllegalArgumentException("设备ID必填");
        }
        return evaluationService.reevaluate(id, equipmentId);
    }

    private void applySpec(TrendRule rule, RuleSpec spec) {
        if (spec.templateItemId() == null) {
            throw new IllegalArgumentException("模板巡检项必填");
        }
        InspectionTemplateItem item = itemRepo.findById(spec.templateItemId())
                .orElseThrow(() -> new IllegalArgumentException("模板巡检项不存在"));
        if (!"numeric".equals(item.getType())) {
            throw new IllegalArgumentException("仅数值型巡检项支持趋势预警规则");
        }
        int ws = spec.windowSize() == null ? 10 : spec.windowSize();
        if (ws < 2) {
            throw new IllegalArgumentException("滚动窗口至少包含 2 个样本");
        }
        int ms = spec.minSamples() == null ? Math.min(5, ws) : spec.minSamples();
        if (ms < 1 || ms > ws) {
            throw new IllegalArgumentException("最少样本数必须在 1 与窗口大小之间");
        }
        Double slope = spec.slopeThreshold();
        if (slope != null && slope == 0) {
            throw new IllegalArgumentException("斜率阈值不能为 0（正数表示上升、负数表示下降）");
        }
        Double fluct = spec.fluctuationAmplitude();
        if (fluct != null && fluct <= 0) {
            throw new IllegalArgumentException("波动幅度阈值必须大于 0");
        }
        Integer nearCount = spec.nearBoundaryCount();
        Double nearMargin = spec.nearMargin();
        if (nearCount != null) {
            if (nearCount < 1) {
                throw new IllegalArgumentException("连续接近边界次数必须大于 0");
            }
            if (nearMargin == null || nearMargin < 0) {
                throw new IllegalArgumentException("接近边界规则需要设置非负的边界裕度");
            }
            if (item.getNormalMin() == null && item.getNormalMax() == null) {
                throw new IllegalArgumentException("模板巡检项未设置正常范围，无法使用连续接近边界规则");
            }
        }
        if (slope == null && fluct == null && nearCount == null) {
            throw new IllegalArgumentException("至少配置一种趋势条件（变化斜率 / 波动幅度 / 连续接近边界）");
        }
        int cooldown = spec.cooldownHours() == null ? 24 : spec.cooldownHours();
        if (cooldown < 0) {
            throw new IllegalArgumentException("冷却期不能为负数");
        }
        rule.setTemplateItemId(item.getId());
        rule.setItemName(item.getName());
        rule.setWindowSize(ws);
        rule.setMinSamples(ms);
        rule.setSlopeThreshold(slope);
        rule.setFluctuationAmplitude(fluct);
        rule.setNearMargin(nearCount != null ? nearMargin : null);
        rule.setNearBoundaryCount(nearCount);
        // 正常范围快照：评估只依赖规则版本快照，模板后续调整不影响已发布版本的结论
        rule.setNormalMin(item.getNormalMin());
        rule.setNormalMax(item.getNormalMax());
        rule.setLevel(TrendSupport.validLevel(spec.level()));
        rule.setCooldownHours(cooldown);
    }
}
