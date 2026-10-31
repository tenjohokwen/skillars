package com.softropic.skillars.infrastructure.gemini;

import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * Admin-facing compliance signal on the health page. Reports the custom {@code WARNING} status while the
 * developer API is used in prod. Spring's status aggregator ignores codes it has no ordering for, so the
 * overall status (and the load balancer / smoke group) stays UP: this is a notice, not an outage.
 */
@Component("geminiCompliance")
public class GeminiComplianceHealthIndicator extends AbstractHealthIndicator {

    static final Status WARNING = new Status("WARNING");

    private final GeminiComplianceMonitor monitor;

    public GeminiComplianceHealthIndicator(GeminiComplianceMonitor monitor) {
        this.monitor = monitor;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        builder.status(monitor.isGapPresent() ? WARNING : Status.UP)
            .withDetail("mode", monitor.mode().name().toLowerCase());
        if (monitor.isGapPresent()) {
            builder.withDetail("warning", GeminiComplianceMonitor.WARNING_TEXT);
        }
    }
}
