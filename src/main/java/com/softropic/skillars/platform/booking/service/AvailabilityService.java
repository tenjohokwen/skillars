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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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

    @Transactional(readOnly = true)
    public CoachAvailabilityResponse getAvailabilityCalendar(UUID coachId, LocalDate weekStart) {
        // Deferred-18 AC3: a coachId matching no CoachProfile row now 404s instead of silently
        // falling through to an empty 200. This lookup is also reused below for `coachTimezone` —
        // do not add a second findById. A real coach with a blank/missing canonicalTimezone keeps
        // its existing "UTC" fallback unchanged; only non-existence becomes a 404.
        CoachProfile profile = coachProfileRepository.findById(coachId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));

        List<CoachAvailabilityWindow> windows = windowRepository.findByCoachId(coachId);

        String timezone = windows.isEmpty() ? "UTC" : windows.get(0).getCanonicalTimezone();
        if (timezone == null || timezone.isBlank()) timezone = "UTC";

        // Deferred-17 AC4: the zone the client displays slots in must be the same column the
        // booking's own canonicalTimezone comes from (coach_profiles, see BookingService), so it
        // is read here from the coach profile. This is deliberately NOT the `timezone` variable
        // above: that one comes from coach_availability_windows, a separate and independently
        // writable column, and only feeds this method's internal slot-computation zone.
        String coachTimezone = profile.getCanonicalTimezone();
        if (coachTimezone == null || coachTimezone.isBlank()) coachTimezone = "UTC";

        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(timezone);
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

                computedSlots.addAll(computeAvailableSlots(windowStart, windowEnd, occupied));
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

        return new CoachAvailabilityResponse(windowResponses, blockResponses, computedSlots, coachTimezone);
    }

    @Transactional
    public AvailabilityWindowResponse addWindow(Long userId, CreateWindowRequest req) {
        CoachProfile profile = requireProfile(userId);
        CoachAvailabilityWindow window = new CoachAvailabilityWindow();
        window.setCoachId(profile.getId());
        window.setDayOfWeek((short) req.dayOfWeek().intValue());
        window.setStartTime(req.startTime());
        window.setEndTime(req.endTime());
        window.setCanonicalTimezone(profile.getCanonicalTimezone());
        CoachAvailabilityWindow saved = windowRepository.save(window);
        return toWindowResponse(saved, false);
    }

    @Transactional
    public AvailabilityWindowResponse updateWindow(Long userId, UUID windowId, UpdateWindowRequest req) {
        CoachProfile profile = requireProfile(userId);
        CoachAvailabilityWindow window = windowRepository.findByIdAndCoachId(windowId, profile.getId())
            .orElseThrow(() -> new ResourceNotFoundException("Availability window not found", "coach_availability_window"));

        window.setDayOfWeek((short) req.dayOfWeek().intValue());
        window.setStartTime(req.startTime());
        window.setEndTime(req.endTime());
        CoachAvailabilityWindow saved = windowRepository.save(window);

        boolean hasConflict = hasBookingConflict(profile.getId(), saved);
        return toWindowResponse(saved, hasConflict);
    }

    @Transactional
    public void deleteWindow(Long userId, UUID windowId) {
        CoachProfile profile = requireProfile(userId);
        CoachAvailabilityWindow window = windowRepository.findByIdAndCoachId(windowId, profile.getId())
            .orElseThrow(() -> new ResourceNotFoundException("Availability window not found", "coach_availability_window"));
        windowRepository.delete(window);
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

    List<AvailableSlotResponse> computeAvailableSlots(
            Instant windowStart, Instant windowEnd,
            List<CoachAvailabilityBlock> blocks) {
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

        return segments.stream()
            .map(s -> new AvailableSlotResponse(s[0], s[1]))
            .toList();
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
