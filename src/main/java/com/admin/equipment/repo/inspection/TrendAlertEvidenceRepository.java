package com.admin.equipment.repo.inspection;

import com.admin.equipment.model.inspection.TrendAlertEvidence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TrendAlertEvidenceRepository extends JpaRepository<TrendAlertEvidence, Long> {
    List<TrendAlertEvidence> findByAlertIdOrderByIdAsc(Long alertId);
    Optional<TrendAlertEvidence> findFirstByAlertIdAndWindowKeyAndMetricOrderByIdDesc(
            Long alertId, String windowKey, String metric);
}
