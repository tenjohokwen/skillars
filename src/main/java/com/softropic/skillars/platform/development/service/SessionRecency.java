package com.softropic.skillars.platform.development.service;

import java.time.Duration;
import java.time.Instant;

/**
 * Pure, stateless "days since the last attended session" computation shared by
 * {@link CoachRosterService} and {@link SessionPrepService}. skillars-12-6 AC8 (G2/G3): one server-
 * provided definition so the roster caption, the Session Prep Card and the dormant badge never
 * disagree (a client-side {@code Date.now()} diff would differ across timezones and in tests).
 */
final class SessionRecency {

    private SessionRecency() {}

    /**
     * Whole 24-hour periods from {@code from} to {@code now}, clamped at 0: a {@code lastSessionDate}
     * can be marginally after {@code now} (TimelineEventListener stamps processing time and SLU
     * {@code calculated_at} is async). Accepted imprecision: 24h-based, so "1 day ago" can span up to
     * 47h; consistent with the dormancy calculation.
     */
    static long daysBetween(Instant from, Instant now) {
        return Math.max(0L, Duration.between(from, now).toDays());
    }

    /** Null-safe variant for a possibly-absent {@code lastSessionDate}. */
    static Long daysSinceOrNull(Instant lastSessionDate, Instant now) {
        return lastSessionDate == null ? null : daysBetween(lastSessionDate, now);
    }
}
