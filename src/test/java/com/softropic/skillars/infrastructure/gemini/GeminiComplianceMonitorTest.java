package com.softropic.skillars.infrastructure.gemini;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.SimpleStatusAggregator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.mock.env.MockEnvironment;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Gemini compliance monitor: prod + developer API is flagged, everything else is quiet")
class GeminiComplianceMonitorTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private GeminiComplianceMonitor monitor(GeminiMode mode, String... profiles) {
        var props = new GeminiProperties();
        props.setMode(mode);
        var env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return new GeminiComplianceMonitor(props, env, registry);
    }

    private double gauge() {
        return registry.get("skillars.gemini.developer_api_in_prod").gauge().value();
    }

    @Test
    void prodDeveloper_flagsGapWithWarningHealthAndGauge() {
        var monitor = monitor(GeminiMode.DEVELOPER, "prod");

        assertThat(monitor.isGapPresent()).isTrue();
        assertThat(gauge()).isEqualTo(1.0);
        Health health = new GeminiComplianceHealthIndicator(monitor).health();
        assertThat(health.getStatus().getCode()).isEqualTo("WARNING");
        assertThat(health.getDetails()).containsEntry("mode", "developer")
            .containsEntry("warning", GeminiComplianceMonitor.WARNING_TEXT);
        monitor.warnAtStartup();
        monitor.remind();
    }

    @Test
    void warningDoesNotDegradeTheAggregateStatus() {
        Status aggregate = new SimpleStatusAggregator()
            .getAggregateStatus(Set.of(Status.UP, GeminiComplianceHealthIndicator.WARNING));

        assertThat(aggregate).isEqualTo(Status.UP);
    }

    @Test
    void prodVertex_isClean() {
        var monitor = monitor(GeminiMode.VERTEX, "prod");

        assertThat(monitor.isGapPresent()).isFalse();
        assertThat(gauge()).isZero();
        Health health = new GeminiComplianceHealthIndicator(monitor).health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("mode", "vertex").doesNotContainKey("warning");
    }

    @Test
    void nonProdDeveloper_isClean() {
        var monitor = monitor(GeminiMode.DEVELOPER, "uat");

        assertThat(monitor.isGapPresent()).isFalse();
        assertThat(gauge()).isZero();
    }
}
