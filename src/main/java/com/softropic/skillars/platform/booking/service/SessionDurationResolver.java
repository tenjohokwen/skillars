package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.repo.CoachPricing;
import com.softropic.skillars.platform.marketplace.repo.CoachPricingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Resolves how long one coaching session with a given coach is.
 *
 * <p>The model is "a system-wide default that a coach may override" (product decision, 2026-08-10):
 * {@code marketplace.coach_pricing.session_duration_minutes} wins when it is non-null, and
 * {@code null} — the state every coach starts in — inherits the live platform value. That is why
 * the column is nullable rather than {@code DEFAULT 60}; see V93.
 *
 * <p><strong>This lives in {@code booking}, not {@code marketplace}.</strong> It is a booking rule
 * that happens to read a marketplace table — the same cross-module read {@code BookingService}
 * already performs with {@code CoachPricingRepository}. Its three callers
 * ({@link AvailabilityService}, {@link BookingService}, {@link BookingBatchService}) share this one
 * definition deliberately; duplicating the fallback chain into each of them is exactly the drift the
 * {@code ACTIVE_SLOT_STATUSES} comment in {@code BookingService} warns about.
 *
 * <p><strong>{@code RescheduleService} deliberately does not use this.</strong> A reschedule is a
 * move, not a resize: it compares against the booking's own existing duration, so a booking made
 * before this constraint existed can still be moved at its own length.
 */
@Service
@RequiredArgsConstructor
public class SessionDurationResolver {

    static final String DEFAULT_DURATION_KEY = "booking.session.defaultDurationMinutes";

    /** Mirrors chk_coach_pricing_session_duration (V93) and ProfileBuilderStep3Request's @Min/@Max. */
    static final long MIN_MINUTES = 15;
    static final long MAX_MINUTES = 240;
    static final long FALLBACK_MINUTES = 60;

    private final CoachPricingRepository coachPricingRepository;
    private final ConfigService configService;

    public Duration resolve(UUID coachId) {
        return coachPricingRepository.findByCoachId(coachId)
            .map(CoachPricing::getSessionDurationMinutes)
            .map(minutes -> Duration.ofMinutes(minutes.longValue()))
            // Two distinct fallbacks reach this line: a coach whose override is null, and a coach
            // with no coach_pricing row at all (reachable — createBookingRequest accepts
            // PENDING_REVIEW profiles, and Step 3 is where pricing is first written).
            //
            // getBoundedLong, not getLong: getLong(key) throws IllegalStateException on a missing
            // key, which would take down the availability endpoint if the V93 seed were ever rolled
            // back, and a zero or negative value would make AvailabilityService's slicing loop
            // forever.
            .orElseGet(() -> Duration.ofMinutes(
                configService.getBoundedLong(DEFAULT_DURATION_KEY, FALLBACK_MINUTES, MIN_MINUTES, MAX_MINUTES)));
    }
}
