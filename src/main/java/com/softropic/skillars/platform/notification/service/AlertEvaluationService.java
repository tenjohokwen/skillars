package com.softropic.skillars.platform.notification.service;

import com.softropic.skillars.platform.notification.contract.AlertFiredEvent;
import com.softropic.skillars.platform.notification.repo.AlertRule;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.annotation.Observed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Evaluates DB-configured alert rules against Micrometer counters on a fixed schedule.
 *
 * <p>For each enabled rule, computes the current metric value and publishes an
 * {@link AlertFiredEvent} when the value breaches the configured threshold.
 *
 * <p>This service is NOT @Transactional — it reads in-memory Micrometer counters only.
 * No DB call is made during evaluation (Pitfall 3 anti-pattern: reading DB in hot loop).
 *
 * <p>Pitfall 8 guard: metrics with total sample count below {@link #MINIMUM_SAMPLE_SIZE}
 * return -1.0 and are skipped, preventing false alarms at startup when counters are near zero.
 */
@Service
public class AlertEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(AlertEvaluationService.class);

    /** Minimum number of total observations required before an alert can fire. Pitfall 8 guard. */
    private static final int MINIMUM_SAMPLE_SIZE = 10;

    private final MeterRegistry meterRegistry;
    private final AlertRuleCache alertRuleCache;
    private final ApplicationEventPublisher eventPublisher;

    public AlertEvaluationService(MeterRegistry meterRegistry,
                                  AlertRuleCache alertRuleCache,
                                  ApplicationEventPublisher eventPublisher) {
        this.meterRegistry = meterRegistry;
        this.alertRuleCache = alertRuleCache;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Evaluate all cached alert rules against current Micrometer counter values.
     * Runs on a fixed delay (default 30 s). Safe to call directly in tests.
     */
    @Observed(name = "scheduler.alert-evaluation")
    @Scheduled(fixedDelayString = "${alert.evaluation-interval-ms:30000}")
    public void evaluate() {
        for (AlertRule rule : alertRuleCache.getCachedRules()) {
            double actualValue = computeMetricValue(rule.getMetricName());
            if (actualValue < 0) {
                // metric not computable (no samples, unsupported metric name, etc.)
                continue;
            }
            if (actualValue >= rule.getThreshold().doubleValue()) {
                log.warn("Alert threshold breached",
                        kv("operation", "alert_evaluation"),
                        kv("metric", rule.getMetricName()),
                        kv("actual", actualValue),
                        kv("threshold", rule.getThreshold()),
                        kv("status", "BREACHED"));
                eventPublisher.publishEvent(new AlertFiredEvent(
                        rule.getMetricName(),
                        actualValue,
                        rule.getThreshold().doubleValue(),
                        rule.getNotificationChannel()));
            }
        }
    }

    /**
     * Compute the current ratio for the named metric using in-memory Micrometer counters.
     *
     * @param metricName metric identifier
     * @return ratio in [0.0, 1.0], or -1.0 if the metric cannot be computed (insufficient samples / unknown name)
     */
    private double computeMetricValue(String metricName) {
        // TODO: Implement generic metric computation or add domain-specific metrics here.
        // Payment-specific metrics (FAILURE_RATE, etc.) have been removed during template conversion.
        return -1.0;
    }

    /**
     * Safe counter read — returns 0.0 if the counter has never been registered.
     *
     * @param name Micrometer counter name
     * @return current count, or 0.0 on any error
     */
    private double getCounter(String name) {
        try {
            return meterRegistry.counter(name).count();
        } catch (Exception e) {
            return 0.0;
        }
    }
}
