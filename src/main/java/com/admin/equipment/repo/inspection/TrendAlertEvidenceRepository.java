package com.admin.equipment.repo.inspection;

import com.admin.equipment.model.inspection.TrendAlertEvidence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TrendAlertEvidenceRepository extends JpaRepository<TrendAlertEvidence, Long> {

    Optional<TrendAlertEvidence> findByRuleIdAndRuleVersionAndEquipmentIdAndTemplateItemIdAndWindowEnd(
            Long ruleId, Integer ruleVersion, Long equipmentId, Long templateItemId, LocalDateTime windowEnd);

    List<TrendAlertEvidence> findByAlertIdOrderByWindowEndAsc(Long alertId);

    long countByAlertIdAndRetractedFalse(Long alertId);
}
