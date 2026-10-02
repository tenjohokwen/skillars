package com.softropic.skillars.platform.marketplace.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(schema = "marketplace", name = "coach_availability_windows")
@Getter
@Setter
@NoArgsConstructor
public class CoachAvailabilityWindow {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "coach_id", nullable = false)
    private UUID coachId;

    @Column(name = "day_of_week", nullable = false)
    private short dayOfWeek;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    /**
     * skillars-deferred-140 AC1.3: always equal to this window's coach's
     * {@code coach_profiles.canonical_timezone} as of the write that created/last re-stamped this
     * row ({@code CoachProfileService.saveStep4}/{@code saveStep1}, {@code AvailabilityService
     * .addWindow}) — the coach's profile zone is now authoritative for all availability, so no
     * window is ever written with a zone of its own choosing.
     *
     * <p>Kept on the entity/column rather than dropped. No computational path reads it any more:
     * {@code AvailabilityService.getAvailabilityCalendar} materializes every window in the profile
     * zone (AC1.3), and the deferred-140 code review moved the two remaining readers —
     * {@code BookingService.isSlotWithinAvailabilityWindow} (which the AC1.3 audit missed, being in
     * another module) and {@code AvailabilityService.hasBookingConflict} — onto the profile zone too.
     * It survives only as a reporting value on {@code AvailabilityWindowResponse} and as part of
     * {@code computeAvailabilitySignature}'s cache-invalidation key.
     *
     * <p>{@code V155__backfill_availability_window_canonical_timezone.sql} reconciled every
     * pre-existing row, so "always equal to the profile zone" is now true of stored data and not just
     * of new writes. That makes the column a genuine deprecation/removal candidate.
     */
    @Column(name = "canonical_timezone", nullable = false)
    private String canonicalTimezone;
}
