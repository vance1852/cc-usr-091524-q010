package com.admin.equipment.repo.inspection;

import com.admin.equipment.model.inspection.TrendRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TrendRuleRepository extends JpaRepository<TrendRule, Long> {
    List<TrendRule> findByTemplateItemIdAndEnabledTrue(Long templateItemId);
    List<TrendRule> findByTemplateItemId(Long templateItemId);
    List<TrendRule> findAllByOrderByIdDesc();
}
