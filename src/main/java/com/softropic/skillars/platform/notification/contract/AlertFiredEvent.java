package com.softropic.skillars.platform.notification.contract;

/**
 * Event published by {@link com.softropic.skillars.platform.notification.service.AlertEvaluationService}
 * when a metric value breaches its configured threshold.
 *
 * <p>Handled by {@link com.softropic.skillars.platform.notification.service.AlertNotificationListener}.
 *
 * @param metricName          name of the breached metric (e.g. FAILURE_RATE)
 * @param actualValue         computed metric value at evaluation time
 * @param threshold           configured threshold that was breached
 * @param notificationChannel delivery channel: LOG or EMAIL
 */
public record AlertFiredEvent(
        String metricName,
        double actualValue,
        double threshold,
        String notificationChannel) {
}
