package com.admin.equipment.repo.inspection;

import com.admin.equipment.model.inspection.TrendEvaluationLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TrendEvaluationLogRepository extends JpaRepository<TrendEvaluationLog, Long> {
    List<TrendEvaluationLog> findByAlertIdOrderByIdAsc(Long alertId);
    Optional<TrendEvaluationLog> findFirstByRuleIdAndEquipmentIdAndTemplateItemIdOrderByIdDesc(
            Long ruleId, Long equipmentId, Long templateItemId);
    List<TrendEvaluationLog> findTop20ByEquipmentIdAndTemplateItemIdAndLateRecomputeTrueOrderByIdDesc(
            Long equipmentId, Long templateItemId);
    boolean existsByRuleIdAndRuleVersionAndEquipmentIdAndTemplateItemIdAndWindowKey(
            Long ruleId, Integer ruleVersion, Long equipmentId, Long templateItemId, String windowKey);
}
