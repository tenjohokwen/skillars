package com.softropic.skillars.platform.notification.repo;

import com.softropic.skillars.infrastructure.persistence.AbstractAuditingEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

/**
 * Alert rule — stores a threshold, metric name, and notification channel for a named alert.
 *
 * <p>Rules are loaded from the DB by {@link AlertRuleRepository} and hot-reloaded by
 * {@link com.softropic.skillars.platform.notification.service.AlertRuleCache} on a scheduled interval.
 * No restart is needed when DB rows are updated.
 *
 * <p>metricName is stored as a plain String (not enum) to allow adding new metric names
 * via DB without a code change or restart.
 */
@Entity
@Table(name = "alert_rule", schema = "main")
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Getter
public class AlertRule extends AbstractAuditingEntity {

    /**
     * Name of the metric to evaluate. Known values: FAILURE_RATE, FRAUD_SPIKE_RATE,
     * CALLBACK_ANOMALY. Stored as String to allow new metrics without code change.
     */
    @Column(name = "metric_name", nullable = false)
    private String metricName;

    /** Breach threshold as a ratio (e.g. 0.20 = 20%). */
    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal threshold;

    /** Evaluation window duration in seconds (informational; actual cadence set by schedule). */
    @Column(name = "window_seconds", nullable = false)
    private int windowSeconds;

    /** Notification delivery channel: LOG or EMAIL. */
    @Column(name = "notification_channel", nullable = false)
    private String notificationChannel;

    /** Whether this rule is active. Disabled rules are ignored by the cache and evaluator. */
    @Column(nullable = false)
    private boolean enabled;

    /** Human-readable description of the rule's purpose. */
    @Column(length = 500)
    private String description;
}
