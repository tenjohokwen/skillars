package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.infrastructure.util.ClockProvider;
import com.softropic.skillars.platform.config.service.ConfigBounds;
import com.softropic.skillars.platform.config.service.ConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * skillars-12-10: the ONE coach-access window rule for per-player development data (roster, timeline,
 * every read, report generation, the two non-report writes). The length is the existing
 * {@code development.timeline.coachAccessExpiryDays} key (default 90).
 *
 * <p>Two clocks feed {@link #isOpen} (owner decision D12.10-D). Reads and roster {@code accessExpired}
 * pass the pair's latest booking END over the read-relationship statuses, so a brand-new player (booked,
 * nothing attended), an in-progress session and the gap before completion processing all stay open.
 * The timeline and report generation pass the latest ATTENDED-session end instead and treat {@code null}
 * as closed (skillars-12-3: a non-attended wrap-up never earns history access) — they deliberately do
 * not get the open-booking behaviour.
 *
 * <p>An out-of-range stored value does not clamp: {@code ConfigService.getBoundedLong} falls back to the
 * 90-day default with a WARN ({@code ConfigStartupAssertion} also logs ERROR + a metric at boot). The admin
 * API rejects out-of-range writes with a 400, so {@code 0} cannot be set through it (skillars-deferred-159 AC5).
 */
@Component
@RequiredArgsConstructor
public class CoachAccessWindow {

    private final ConfigService configService;

    public long windowDays() {
        return configService.getBoundedLong(
            ConfigBounds.TIMELINE_COACH_ACCESS_EXPIRY_DAYS.key(),
            ConfigBounds.TIMELINE_COACH_ACCESS_EXPIRY_DAYS.defaultValue(),
            ConfigBounds.TIMELINE_COACH_ACCESS_EXPIRY_DAYS.min(),
            ConfigBounds.TIMELINE_COACH_ACCESS_EXPIRY_DAYS.max());
    }

    public Instant now() {
        return Instant.now(ClockProvider.getClock());
    }

    public boolean isOpen(Instant clockInstant) {
        return isOpen(clockInstant, now(), windowDays());
    }

    /** Open iff the instant exists and is not older than {@code windowDays} before {@code now}. */
    public static boolean isOpen(Instant clockInstant, Instant now, long windowDays) {
        return clockInstant != null && !clockInstant.isBefore(now.minus(Duration.ofDays(windowDays)));
    }
}
