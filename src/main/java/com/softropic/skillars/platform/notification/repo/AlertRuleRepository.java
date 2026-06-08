package com.softropic.skillars.platform.notification.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link AlertRule} entities.
 */
public interface AlertRuleRepository extends JpaRepository<AlertRule, Long> {

    /**
     * Return all alert rules that are currently enabled.
     * Used by {@link com.softropic.skillars.platform.notification.service.AlertRuleCache} during cache refresh.
     */
    List<AlertRule> findAllByEnabledTrue();
}
