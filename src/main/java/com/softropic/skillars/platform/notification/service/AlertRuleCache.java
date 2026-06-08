package com.softropic.skillars.platform.notification.service;

import com.softropic.skillars.platform.notification.repo.AlertRule;
import com.softropic.skillars.platform.notification.repo.AlertRuleRepository;

import io.micrometer.observation.annotation.Observed;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory cache of enabled {@link AlertRule} entities, hot-reloaded from the DB on a
 * configurable schedule.
 *
 * <p>Uses {@link AtomicReference} for thread-safe visibility of list replacement without locking.
 * The {@code @Scheduled} refresh calls {@link AlertRuleRepository#findAllByEnabledTrue()} and
 * atomically replaces the cached list — no JVM restart is required when DB rows are updated.
 */
@Service
public class AlertRuleCache {

    private final AlertRuleRepository alertRuleRepository;
    private final AtomicReference<List<AlertRule>> cachedRules = new AtomicReference<>(List.of());

    public AlertRuleCache(AlertRuleRepository alertRuleRepository) {
        this.alertRuleRepository = alertRuleRepository;
    }

    @PostConstruct
    public void init() {
        refresh();
    }

    /**
     * Reload enabled alert rules from DB and atomically replace the cache.
     * Called on startup via {@link PostConstruct} and on a scheduled interval.
     */
    @Observed(name = "scheduler.cache-refresh", contextualName = "alert-rule-cache")
    @Scheduled(fixedDelayString = "${alert.rule-cache.refresh-interval-ms:60000}")
    public void refresh() {
        List<AlertRule> rules = alertRuleRepository.findAllByEnabledTrue();
        cachedRules.set(rules);
    }

    /**
     * Return an unmodifiable snapshot of the currently cached enabled alert rules.
     * Thread-safe — reads from AtomicReference.
     */
    public List<AlertRule> getCachedRules() {
        return Collections.unmodifiableList(cachedRules.get());
    }
}
