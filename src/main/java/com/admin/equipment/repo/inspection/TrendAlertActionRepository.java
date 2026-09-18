package com.admin.equipment.repo.inspection;

import com.admin.equipment.model.inspection.TrendAlertAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TrendAlertActionRepository extends JpaRepository<TrendAlertAction, Long> {
    List<TrendAlertAction> findByAlertIdOrderByCreatedAtAscIdAsc(Long alertId);
}
