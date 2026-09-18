package com.admin.equipment.repo.inspection;

import com.admin.equipment.model.inspection.TrendAlert;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TrendAlertRepository extends JpaRepository<TrendAlert, Long> {

    Optional<TrendAlert> findByDedupeKey(String dedupeKey);

    Optional<TrendAlert> findFirstByEquipmentIdAndTemplateItemIdAndRuleIdAndRuleVersionOrderByIdDesc(
            Long equipmentId, Long templateItemId, Long ruleId, Integer ruleVersion);

    @Query("SELECT a FROM TrendAlert a WHERE (:status IS NULL OR a.status = :status) "
            + "AND (:equipmentId IS NULL OR a.equipmentId = :equipmentId) "
            + "AND (:ruleId IS NULL OR a.ruleId = :ruleId) "
            + "AND (:level IS NULL OR a.level = :level) ORDER BY a.id DESC")
    Page<TrendAlert> search(@Param("status") String status,
                            @Param("equipmentId") Long equipmentId,
                            @Param("ruleId") Long ruleId,
                            @Param("level") String level,
                            Pageable pageable);
}
