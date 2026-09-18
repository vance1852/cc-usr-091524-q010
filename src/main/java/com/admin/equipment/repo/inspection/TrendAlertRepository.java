package com.admin.equipment.repo.inspection;

import com.admin.equipment.model.inspection.TrendAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.LocalDateTime;
import java.util.Optional;

public interface TrendAlertRepository extends JpaRepository<TrendAlert, Long>, JpaSpecificationExecutor<TrendAlert> {

    Optional<TrendAlert> findByRuleIdAndRuleVersionAndEquipmentIdAndTemplateItemIdAndWindowEnd(
            Long ruleId, Integer ruleVersion, Long equipmentId, Long templateItemId, LocalDateTime windowEnd);

    /** 冷却期内的最新事件：新证据应追加到该事件而不是新开预警 */
    Optional<TrendAlert> findFirstByRuleIdAndRuleVersionAndEquipmentIdAndTemplateItemIdAndCooldownUntilAfterOrderByLastEvidenceAtDesc(
            Long ruleId, Integer ruleVersion, Long equipmentId, Long templateItemId, LocalDateTime now);
}
