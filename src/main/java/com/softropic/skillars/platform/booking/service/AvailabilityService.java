package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.platform.booking.contract.AvailabilityBlockResponse;
import com.softropic.skillars.platform.booking.contract.AvailabilityWindowResponse;
import com.softropic.skillars.platform.booking.contract.AvailableSlotResponse;
import com.softropic.skillars.platform.booking.contract.CoachAvailabilityResponse;
import com.softropic.skillars.platform.booking.contract.CreateBlockRequest;
import com.softropic.skillars.platform.booking.contract.CreateWindowRequest;
import com.softropic.skillars.platform.booking.contract.UpdateWindowRequest;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.booking.repo.CoachAvailabilityBlock;
import com.softropic.skillars.platform.booking.repo.CoachAvailabilityBlockRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachAvailabilityWindow;
import com.softropic.skillars.platform.marketplace.repo.CoachAvailabilityWindowRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AvailabilityService {

    private final CoachAvailabilityWindowRepository windowRepository;
    private final CoachAvailabilityBlockRepository blockRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final BookingRepository bookingRepository;
    // Injected instead of ConfigService + CoachPricingRepository: the fallback chain has one
    // definition, shared with BookingService and BookingBatchService.
    private final SessionDurationResolver sessionDurationResolver;
    // Deferred-78 AC1: addWindow/updateWindow/deleteWindow re-lock the coach row before mutating,
    // mirroring BookingService's own two-step requireProfile-then-lock pattern — without this, the
    // per-coach locks that BookingService/BookingBatchService/RescheduleService now take before
    // reading windows serialize against nothing, since the writer side never took the same lock.
    private final PessimisticLockRetryer lockRetryer;
    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public CoachAvailabilityResponse getAvailabilityCalendar(UUID coachId, LocalDate weekStart) {
        // Deferred-18 AC3: a coachId matching no CoachProfile row now 404s instead of silently
        // falling through to an empty 200. This lookup is also reused below for `coachTimezone` —
        // do not add a second findById. A real coach with a blank/missing canonicalTimezone keeps
        // its existing "UTC" fallback unchanged; only non-existence becomes a 404.
        CoachProfile profile = coachProfileRepository.findById(coachId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));

        List<CoachAvailabilityWindow> windows = windowRepository.findByCoachIdOrderByDayOfWeekAscStartTimeAscIdAsc(coachId);

        // Deferred-17 AC4 / skillars-deferred-65 AC3: the zone driving week-scoping bounds is the
        // coach profile's own canonical_timezone — the same column the booking's own
        // canonicalTimezone comes from (coach_profiles, see BookingService) and the same value the
        // response's own canonicalTimezone field already reports. This used to instead read
        // windows.get(0).getCanonicalTimezone(), an arbitrary pick off an unordered list
        // (CoachAvailabilityWindowRepository.findByCoachId issues no ORDER BY): two identical
        // requests for a coach with windows in multiple timezones could return different week
        // boundaries and a different blocks set purely from row-order luck. Per-window timezone
        // divergence remains a deliberate feature (skillars-deferred-63/-64) — this only changes
        // which value drives the *outer* week-scoping bounds, not per-window slot computation below.
        String coachTimezone = profile.getCanonicalTimezone();
        if (coachTimezone == null || coachTimezone.isBlank()) coachTimezone = "UTC";

        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(coachTimezone);
        } catch (DateTimeException e) {
            zoneId = ZoneId.of("UTC");
        }

        // Deferred-18 AC2: each window below now materializes its instants in its OWN zone, which
        // can diverge from `zoneId` (derived from windows.get(0) only). Padding the fetch bounds
        // keeps every window's instants inside the range used to fetch blocks/bookings, regardless
        // of that divergence. `weekStartExact`/`weekEndExact` preserve the unpadded bounds so
        // `blockResponses` (the API's `blocks` field) stays exactly week-scoped — padding must
        // widen what's fetched for filtering, not what's returned.
        //
        // TWO days, not one. AC2 prescribed a one-day pad, but one day is arithmetically too small
        // for the divergence it exists to cover, and the 2026-08-07 code review found the gap:
        // region zones alone span 25h (Pacific/Niue at UTC-11 to Pacific/Kiritimati at UTC+14), and
        // ZoneId.of additionally accepts fixed offsets up to +/-18:00 (a case AC4 deliberately keeps
        // valid), for a worst case of 36h. Coverage requires the pad to exceed the offset spread on
        // each side; at 24h a window leading the outer zone by more than a day had its overlapping
        // bookings fall outside the fetch entirely, silently reproducing the very AC1 failure mode
        // this padding exists to prevent. 48h covers every case ZoneId.of can produce.
        Instant weekStartInstant = weekStart.minusDays(2).atStartOfDay(zoneId).toInstant();
        Instant weekEndInstant = weekStart.plusDays(9).atStartOfDay(zoneId).toInstant();
        Instant weekStartExact = weekStart.atStartOfDay(zoneId).toInstant();
        Instant weekEndExact = weekStart.plusDays(7).atStartOfDay(zoneId).toInstant();

        List<CoachAvailabilityBlock> weekBlocks =
            blockRepository.findByCoachIdAndEndDatetimeAfterAndStartDatetimeBefore(
                coachId, weekStartInstant, weekEndInstant);

        // Deferred-18 AC1: active bookings for any requester (not just the current parent/player),
        // fetched once for the whole padded week rather than once per window, reusing the same
        // overlap query and active-status set BookingService.createBookingRequest's own check uses
        // so the two never drift.
        List<Booking> weekBookings = bookingRepository.findOverlappingBookings(
            coachId, weekStartInstant, weekEndInstant, BookingService.ACTIVE_SLOT_STATUSES, null);

        // UAT.2 AC2: resolved ONCE for the whole request, not per window and certainly not per day —
        // it is a per-coach value and coachId is already in hand here.
        Duration slotLength = sessionDurationResolver.resolve(coachId);

        List<AvailableSlotResponse> computedSlots = new ArrayList<>();
        for (int dayOffset = 0; dayOffset < 7; dayOffset++) {
            LocalDate date = weekStart.plusDays(dayOffset);
            DayOfWeek dow = date.getDayOfWeek();
            int isoDay = dow.getValue();

            List<CoachAvailabilityWindow> dayWindows = windows.stream()
                .filter(w -> w.getDayOfWeek() == (short) isoDay)
                .toList();

            for (CoachAvailabilityWindow window : dayWindows) {
                // Null/blank guarded explicitly, mirroring the outer zone derivation above:
                // ZoneId.of(null) throws NullPointerException, not DateTimeException, so the catch
                // alone would 500 the whole calendar instead of falling back to UTC. Unreachable
                // today (canonical_timezone is NOT NULL in V26), but the asymmetry with :59-60 is
                // exactly the kind that survives a later schema change.
                String windowTimezone = window.getCanonicalTimezone();
                ZoneId windowZoneId;
                try {
                    windowZoneId = (windowTimezone == null || windowTimezone.isBlank())
                        ? ZoneId.of("UTC")
                        : ZoneId.of(windowTimezone);
                } catch (DateTimeException e) {
                    windowZoneId = ZoneId.of("UTC");
                }

                Instant windowStart = date.atTime(window.getStartTime()).atZone(windowZoneId).toInstant();
                Instant windowEnd = date.atTime(window.getEndTime()).atZone(windowZoneId).toInstant();

                // A DST gap can invert these two instants even though the LOCAL times are ordered.
                // LocalDateTime.atZone() silently shifts a nonexistent local time forward by the gap
                // length, and the DB guard chk_availability_time_order (V26:56) constrains only the
                // local times, so it cannot see this. Concretely: Europe/Berlin, window 02:30-03:00
                // on 2026-03-29 — 02:30 falls in the spring-forward gap and resolves to 01:30Z while
                // 03:00 resolves to 01:00Z. computeAvailableSlots seeds its segment list with
                // {windowStart, windowEnd} unconditionally, so without this guard the API emits a
                // slot whose startDatetime is AFTER its endDatetime; the frontend renders it as
                // clickable and submitting it 400s behind a generic toast.
                //
                // Guarded here at the call site, before `occupied` is built, rather than inside
                // computeAvailableSlots: the two instants exist here, and pushing the check inward
                // would give that method a new failure mode it has no way to report.
                if (!windowEnd.isAfter(windowStart)) {
                    // WARN, not silence: a window whose local range straddles a DST gap is a data
                    // problem someone should see and fix, not something to swallow.
                    log.warn("Skipping availability window with non-positive duration — its local "
                            + "range straddles a DST gap in its own zone. coachId={} windowId={} "
                            + "day={} local={}-{} zone={} resolved={}/{}",
                        coachId, window.getId(), date, window.getStartTime(), window.getEndTime(),
                        windowZoneId, windowStart, windowEnd);
                    continue;
                }

                // Inversion is the loud case; SHORTENING is the quiet one. A window whose start
                // alone falls in the gap still satisfies the guard above but loses time: Berlin
                // 02:30-04:00 on 2026-03-29 resolves to 01:30Z-02:00Z — 30 minutes offered where
                // the coach configured 90. That is arguably the correct outcome (02:30 local does
                // not exist that day, so real availability starts at 03:00), which is exactly why
                // it must not pass silently: the coach sees fewer bookable minutes than they set up
                // and has no way to discover why. Log it and carry on — do NOT skip the window,
                // the shortened remainder is genuinely bookable.
                Duration localDuration =
                    Duration.between(window.getStartTime(), window.getEndTime());
                Duration actualDuration = Duration.between(windowStart, windowEnd);
                if (!actualDuration.equals(localDuration)) {
                    log.warn("Availability window duration changed by a DST transition — offering "
                            + "{} instead of the configured {}. coachId={} windowId={} day={} "
                            + "local={}-{} zone={}",
                        actualDuration, localDuration, coachId, window.getId(), date,
                        window.getStartTime(), window.getEndTime(), windowZoneId);
                }

                List<CoachAvailabilityBlock> occupied = new ArrayList<>(weekBlocks.stream()
                    .filter(b -> b.getEndDatetime().isAfter(windowStart)
                        && b.getStartDatetime().isBefore(windowEnd))
                    .toList());

                // AC1: merge overlapping bookings in as transient (never-saved) pseudo-blocks —
                // computeAvailableSlots only ever reads getStartDatetime()/getEndDatetime(), so it
                // does not care whether they're persisted. Deliberately NOT added to weekBlocks —
                // that list also feeds blockResponses below, and a booking is not a block.
                for (Booking booking : weekBookings) {
                    if (booking.getRequestedEndTime().isAfter(windowStart)
                            && booking.getRequestedStartTime().isBefore(windowEnd)) {
                        CoachAvailabilityBlock pseudoBlock = new CoachAvailabilityBlock();
                        pseudoBlock.setStartDatetime(booking.getRequestedStartTime());
                        pseudoBlock.setEndDatetime(booking.getRequestedEndTime());
                        occupied.add(pseudoBlock);
                    }
                }

                computedSlots.addAll(computeAvailableSlots(windowStart, windowEnd, occupied, slotLength));
            }
        }

        List<AvailabilityWindowResponse> windowResponses = windows.stream()
            .map(w -> new AvailabilityWindowResponse(
                w.getId(), w.getDayOfWeek(), w.getStartTime(), w.getEndTime(),
                w.getCanonicalTimezone(), false))
            .toList();

        List<AvailabilityBlockResponse> blockResponses = weekBlocks.stream()
            .filter(b -> b.getEndDatetime().isAfter(weekStartExact) && b.getStartDatetime().isBefore(weekEndExact))
            .map(b -> new AvailabilityBlockResponse(
                b.getId(), b.getStartDatetime(), b.getEndDatetime(), b.getReason()))
            .toList();

        return new CoachAvailabilityResponse(windowResponses, blockResponses, computedSlots, coachTimezone,
            computeAvailabilitySignature(windows, slotLength));
    }

    static String computeAvailabilitySignature(List<CoachAvailabilityWindow> windows, Duration slotLength) {
        StringBuilder sb = new StringBuilder();
        windows.stream()
            .sorted(Comparator.comparing(CoachAvailabilityWindow::getId))
            .forEach(w -> sb.append(w.getId()).append(':').append(w.getDayOfWeek()).append(':')
                .append(w.getStartTime()).append('-').append(w.getEndTime()).append(':')
                .append(w.getCanonicalTimezone()).append(';'));
        sb.append("duration=").append(slotLength.toMinutes());
        return sb.toString();
    }

    @Transactional
    public AvailabilityWindowResponse addWindow(Long userId, CreateWindowRequest req) {
        CoachProfile profile = requireProfile(userId);
        CoachProfile lockedProfile = lockProfile(profile.getId());
        CoachAvailabilityWindow window = new CoachAvailabilityWindow();
        window.setCoachId(lockedProfile.getId());
        window.setDayOfWeek((short) req.dayOfWeek().intValue());
        window.setStartTime(req.startTime());
        window.setEndTime(req.endTime());
        window.setCanonicalTimezone(lockedProfile.getCanonicalTimezone());
        CoachAvailabilityWindow saved = windowRepository.save(window);
        return toWindowResponse(saved, false);
    }

    @Transactional
    public AvailabilityWindowResponse updateWindow(Long userId, UUID windowId, UpdateWindowRequest req) {
        CoachProfile profile = requireProfile(userId);
        CoachProfile lockedProfile = lockProfile(profile.getId());
        CoachAvailabilityWindow window = windowRepository.findByIdAndCoachId(windowId, lockedProfile.getId())
            .orElseThrow(() -> new ResourceNotFoundException("Availability window not found", "coach_availability_window"));

        window.setDayOfWeek((short) req.dayOfWeek().intValue());
        window.setStartTime(req.startTime());
        window.setEndTime(req.endTime());
        CoachAvailabilityWindow saved = windowRepository.save(window);

        boolean hasConflict = hasBookingConflict(lockedProfile.getId(), saved);
        return toWindowResponse(saved, hasConflict);
    }

    @Transactional
    public void deleteWindow(Long userId, UUID windowId) {
        CoachProfile profile = requireProfile(userId);
        CoachProfile lockedProfile = lockProfile(profile.getId());
        CoachAvailabilityWindow window = windowRepository.findByIdAndCoachId(windowId, lockedProfile.getId())
            .orElseThrow(() -> new ResourceNotFoundException("Availability window not found", "coach_availability_window"));
        windowRepository.delete(window);
    }

    /**
     * Deferred-78 AC1: resolves the coach by userId via the unlocked {@link #requireProfile} first
     * (for a clean 404 before taking any lock — mirrors {@code BookingService.createBookingRequest}'s
     * own unlocked-then-locked shape), then re-locks by the resolved id so this write serializes
     * against the per-coach locks {@code BookingService}/{@code BookingBatchService}/
     * {@code RescheduleService} take before reading windows.
     */
    private CoachProfile lockProfile(UUID coachId) {
        return lockRetryer.withBoundedRetry(() -> {
            CoachProfile c = coachProfileRepository.findByIdForUpdate(coachId)
                .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));
            entityManager.refresh(c, LockModeType.PESSIMISTIC_WRITE);
            return c;
        });
    }

    @Transactional
    public AvailabilityBlockResponse addBlock(Long userId, CreateBlockRequest req) {
        CoachProfile profile = requireProfile(userId);
        CoachAvailabilityBlock block = new CoachAvailabilityBlock();
        block.setCoachId(profile.getId());
        block.setStartDatetime(req.startDatetime());
        block.setEndDatetime(req.endDatetime());
        block.setReason(req.reason());
        CoachAvailabilityBlock saved = blockRepository.save(block);
        return toBlockResponse(saved);
    }

    @Transactional
    public void deleteBlock(Long userId, UUID blockId) {
        CoachProfile profile = requireProfile(userId);
        CoachAvailabilityBlock block = blockRepository.findByIdAndCoachId(blockId, profile.getId())
            .orElseThrow(() -> new OperationNotAllowedException("Block not found or not owned by coach", SecurityError.MISSING_RIGHTS));
        blockRepository.delete(block);
    }

    /**
     * Returns the coach's free time inside {@code [windowStart, windowEnd)} as consecutive
     * fixed-length slots of {@code slotLength}, not as whole free segments (UAT.2 AC2). A whole
     * segment was one clickable row that booked the coach's entire working day for one credit.
     *
     * <p><strong>The grid is anchored per segment, not per window, and that is intended.</strong> A
     * 09:00-17:00 window with a 12:00-12:45 block yields slots on the hour to 12:00, then 12:45,
     * 13:45 ... — the post-block run is anchored to the block's end because that is genuinely when
     * the coach next becomes free. Anchoring the whole window to 09:00 instead would silently
     * discard the 12:45-13:00 quarter-hour and every equivalent fragment. Do not "tidy" this into a
     * window-anchored grid; {@code BookingService} deliberately enforces exact duration only, never
     * grid alignment, for the same reason.
     *
     * <p>Any remainder shorter than one slot is dropped, so a segment shorter than {@code
     * slotLength} yields nothing at all.
     */
    List<AvailableSlotResponse> computeAvailableSlots(
            Instant windowStart, Instant windowEnd,
            List<CoachAvailabilityBlock> blocks, Duration slotLength) {
        // SessionDurationResolver.getBoundedLong already refuses a non-positive length; this makes
        // the failure loud rather than an infinite loop in the slicing below if that ever changes
        // or a caller passes one directly.
        if (slotLength == null || slotLength.isZero() || slotLength.isNegative()) {
            throw new IllegalArgumentException("Slot length must be positive, was: " + slotLength);
        }
        List<Instant[]> segments = new ArrayList<>();
        segments.add(new Instant[]{windowStart, windowEnd});

        for (CoachAvailabilityBlock block : blocks) {
            List<Instant[]> next = new ArrayList<>();
            for (Instant[] seg : segments) {
                Instant sStart = seg[0];
                Instant sEnd = seg[1];
                Instant bStart = block.getStartDatetime();
                Instant bEnd = block.getEndDatetime();

                if (!bEnd.isAfter(sStart) || !bStart.isBefore(sEnd)) {
                    next.add(seg);
                } else {
                    if (sStart.isBefore(bStart)) {
                        next.add(new Instant[]{sStart, bStart});
                    }
                    if (bEnd.isBefore(sEnd)) {
                        next.add(new Instant[]{bEnd, sEnd});
                    }
                }
            }
            segments = next;
        }

        // Slice strictly downstream of the segment computation above, which is left untouched.
        List<AvailableSlotResponse> slots = new ArrayList<>();
        for (Instant[] seg : segments) {
            Instant segEnd = seg[1];
            for (Instant t = seg[0]; !t.plus(slotLength).isAfter(segEnd); t = t.plus(slotLength)) {
                slots.add(new AvailableSlotResponse(t, t.plus(slotLength)));
            }
        }
        return List.copyOf(slots);
    }

    private CoachProfile requireProfile(Long userId) {
        return coachProfileRepository.findByUserId(userId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));
    }

    private AvailabilityWindowResponse toWindowResponse(CoachAvailabilityWindow w, boolean hasConflict) {
        return new AvailabilityWindowResponse(
            w.getId(), w.getDayOfWeek(), w.getStartTime(), w.getEndTime(),
            w.getCanonicalTimezone(), hasConflict);
    }

    private AvailabilityBlockResponse toBlockResponse(CoachAvailabilityBlock b) {
        return new AvailabilityBlockResponse(b.getId(), b.getStartDatetime(), b.getEndDatetime(), b.getReason());
    }

    private boolean hasBookingConflict(UUID coachId, CoachAvailabilityWindow window) {
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(window.getCanonicalTimezone());
        } catch (DateTimeException e) {
            log.warn("Skipping booking-conflict check for window {} (coach {}): invalid canonicalTimezone '{}'",
                window.getId(), coachId, window.getCanonicalTimezone());
            return false;
        }
        Instant now = Instant.now();
        Instant horizon = now.plus(90, ChronoUnit.DAYS);
        List<Booking> futureBookings = bookingRepository.findByCoachIdAndStatusInAndTimeBetween(
            coachId, List.of("CONFIRMED", "UPCOMING"), now, horizon);

        return futureBookings.stream().anyMatch(b -> {
            ZonedDateTime startZdt = b.getRequestedStartTime().atZone(zoneId);
            LocalTime localTime = startZdt.toLocalTime();
            return window.getDayOfWeek() == (short) startZdt.getDayOfWeek().getValue()
                && !localTime.isBefore(window.getStartTime())
                && localTime.isBefore(window.getEndTime());
        });
    }
}
