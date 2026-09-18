package com.admin.equipment.repo.inspection;

import com.admin.equipment.model.inspection.TrendEvaluation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface TrendEvaluationRepository extends JpaRepository<TrendEvaluation, Long>, JpaSpecificationExecutor<TrendEvaluation> {

    Optional<TrendEvaluation> findFirstByRuleIdAndEquipmentIdAndTemplateItemIdOrderByIdDesc(
            Long ruleId, Long equipmentId, Long templateItemId);
}
