package com.softropic.skillars.infrastructure.config;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for Spring Boot 3.x Observability.
 * Enables support for @Observed (traces + metrics) and @Timed (timing metrics) via AOP.
 */
@Configuration(proxyBeanMethods = false)
public class ObservabilityConfig {

    /**
     * Activates @Observed on any Spring-managed bean. Creates a Micrometer Observation
     * (span + timer) for each annotated method invocation.
     */
    @Bean
    public ObservedAspect observedAspect(ObservationRegistry observationRegistry) {
        return new ObservedAspect(observationRegistry);
    }

    /**
     * Activates @Timed on any Spring-managed bean. Without this bean the annotation
     * is silently ignored at runtime.
     */
    @Bean
    public TimedAspect timedAspect(MeterRegistry meterRegistry) {
        return new TimedAspect(meterRegistry);
    }
}
