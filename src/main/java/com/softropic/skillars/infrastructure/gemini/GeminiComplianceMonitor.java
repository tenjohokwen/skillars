package com.softropic.skillars.infrastructure.gemini;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Makes the data-protection gap of the Gemini developer API visible. In {@code prod}, moderation text
 * sent in {@link GeminiMode#DEVELOPER} mode has no GDPR Art. 28 data processing agreement and no EU
 * data-residency commitment (Vertex AI under the Google Cloud DPA has both). The owner accepted that for
 * now, so this never blocks boot: it logs at startup, repeats the reminder daily, exposes a gauge
 * ({@code skillars.gemini.developer_api_in_prod}, 1 = gap present) and feeds the admin health page
 * ({@link GeminiComplianceHealthIndicator}). All of it clears by itself once {@code GEMINI_MODE=vertex}.
 */
@Component
@Slf4j
public class GeminiComplianceMonitor {

    public static final String WARNING_TEXT = "Content moderation uses the Gemini developer API, which has no "
        + "data processing agreement or EU data residency; switch GEMINI_MODE=vertex before processing "
        + "children's media.";

    private final GeminiMode mode;
    private final boolean prod;

    public GeminiComplianceMonitor(GeminiProperties props, Environment env, MeterRegistry registry) {
        this.mode = props.getMode();
        this.prod = env.acceptsProfiles(Profiles.of("prod"));
        Gauge.builder("skillars.gemini.developer_api_in_prod", this, m -> m.isGapPresent() ? 1 : 0)
            .description("1 when prod sends moderation content to the Gemini developer API (no DPA / EU residency)")
            .register(registry);
    }

    public GeminiMode mode() {
        return mode;
    }

    public boolean isGapPresent() {
        return prod && mode == GeminiMode.DEVELOPER;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warnAtStartup() {
        logIfGap();
    }

    /** Throttled reminder; per instance, log only, so no ShedLock. */
    @Scheduled(initialDelay = 24 * 60 * 60 * 1000L, fixedRate = 24 * 60 * 60 * 1000L)
    public void remind() {
        logIfGap();
    }

    private void logIfGap() {
        if (isGapPresent()) {
            log.warn("COMPLIANCE: {}", WARNING_TEXT);
        }
    }
}
