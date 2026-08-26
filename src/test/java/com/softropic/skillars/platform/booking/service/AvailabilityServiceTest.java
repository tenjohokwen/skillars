package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.booking.contract.AvailabilityWindowResponse;
import com.softropic.skillars.platform.booking.contract.AvailableSlotResponse;
import com.softropic.skillars.platform.booking.contract.CoachAvailabilityResponse;
import com.softropic.skillars.platform.booking.contract.UpdateWindowRequest;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.booking.repo.CoachAvailabilityBlock;
import com.softropic.skillars.platform.booking.repo.CoachAvailabilityBlockRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachAvailabilityWindow;
import com.softropic.skillars.platform.marketplace.repo.CoachAvailabilityWindowRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import org.instancio.Instancio;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AvailabilityServiceTest {

    @Mock
    CoachAvailabilityWindowRepository windowRepository;

    @Mock
    CoachAvailabilityBlockRepository blockRepository;

    @Mock
    CoachProfileRepository coachProfileRepository;

    @Mock
    BookingRepository bookingRepository;

    @Mock
    SessionDurationResolver sessionDurationResolver;

    @InjectMocks
    AvailabilityService service;

    private static final Long COACH_USER_ID = 300L;
    private static final Duration ONE_HOUR = Duration.ofHours(1);

    // ----- updateWindow / hasBookingConflict tests (AC 4) -----

    @Test
    void updateWindow_overlappingConfirmedBooking_returnsHasConflictTrue() {
        UUID coachId = UUID.randomUUID();
        UUID windowId = UUID.randomUUID();
        CoachProfile profile = makeCoachProfile(coachId, COACH_USER_ID);
        CoachAvailabilityWindow window = makeWindow(windowId, coachId);

        // Booking whose local start time falls within the window's day/time range
        ZonedDateTime bookingStart = ZonedDateTime.now(ZoneId.of("Europe/Berlin"))
            .plusDays(3).withHour(11).withMinute(0).withSecond(0).withNano(0);
        Booking booking = makeBooking(coachId, bookingStart.toInstant());
        window.setDayOfWeek((short) bookingStart.getDayOfWeek().getValue());

        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(profile));
        when(windowRepository.findByIdAndCoachId(windowId, coachId)).thenReturn(Optional.of(window));
        when(windowRepository.save(window)).thenReturn(window);
        when(bookingRepository.findByCoachIdAndStatusInAndTimeBetween(
            eq(coachId), anyList(), any(Instant.class), any(Instant.class)))
            .thenReturn(List.of(booking));

        UpdateWindowRequest req = new UpdateWindowRequest(
            (int) window.getDayOfWeek(), window.getStartTime(), window.getEndTime());
        AvailabilityWindowResponse response = service.updateWindow(COACH_USER_ID, windowId, req);

        assertThat(response.hasConflict()).isTrue();
    }

    @Test
    void updateWindow_noOverlap_returnsHasConflictFalse() {
        UUID coachId = UUID.randomUUID();
        UUID windowId = UUID.randomUUID();
        CoachProfile profile = makeCoachProfile(coachId, COACH_USER_ID);
        CoachAvailabilityWindow window = makeWindow(windowId, coachId);

        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(profile));
        when(windowRepository.findByIdAndCoachId(windowId, coachId)).thenReturn(Optional.of(window));
        when(windowRepository.save(window)).thenReturn(window);
        when(bookingRepository.findByCoachIdAndStatusInAndTimeBetween(
            eq(coachId), anyList(), any(Instant.class), any(Instant.class)))
            .thenReturn(List.of());

        UpdateWindowRequest req = new UpdateWindowRequest(
            (int) window.getDayOfWeek(), window.getStartTime(), window.getEndTime());
        AvailabilityWindowResponse response = service.updateWindow(COACH_USER_ID, windowId, req);

        assertThat(response.hasConflict()).isFalse();
    }

    @Test
    void updateWindow_bookingImmediatelyAfterDstTransition_computesConflictUsingCorrectOffset() {
        // Uses the real next Europe/Berlin DST transition (whichever comes first, spring-forward
        // or fall-back) rather than a hardcoded date, so this stays correct regardless of when the
        // suite runs. A booking timestamp is placed 30 minutes after the transition instant — if
        // hasBookingConflict ever regressed to a stale/fixed UTC offset instead of ZoneId-aware
        // conversion, the computed local time would land outside this narrow window and the
        // expected conflict would be missed.
        UUID coachId = UUID.randomUUID();
        UUID windowId = UUID.randomUUID();
        CoachProfile profile = makeCoachProfile(coachId, COACH_USER_ID);
        ZoneId zoneId = ZoneId.of("Europe/Berlin");

        java.time.zone.ZoneOffsetTransition transition = zoneId.getRules().nextTransition(Instant.now());
        Instant bookingInstant = transition.getInstant().plusSeconds(1800);
        ZonedDateTime bookingLocal = bookingInstant.atZone(zoneId);
        Booking booking = makeBooking(coachId, bookingInstant);

        CoachAvailabilityWindow window = makeWindow(windowId, coachId);
        window.setDayOfWeek((short) bookingLocal.getDayOfWeek().getValue());
        window.setStartTime(bookingLocal.toLocalTime().minusMinutes(15));
        window.setEndTime(bookingLocal.toLocalTime().plusMinutes(15));

        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(profile));
        when(windowRepository.findByIdAndCoachId(windowId, coachId)).thenReturn(Optional.of(window));
        when(windowRepository.save(window)).thenReturn(window);
        when(bookingRepository.findByCoachIdAndStatusInAndTimeBetween(
            eq(coachId), anyList(), any(Instant.class), any(Instant.class)))
            .thenReturn(List.of(booking));

        UpdateWindowRequest req = new UpdateWindowRequest(
            (int) window.getDayOfWeek(), window.getStartTime(), window.getEndTime());
        AvailabilityWindowResponse response = service.updateWindow(COACH_USER_ID, windowId, req);

        assertThat(response.hasConflict()).isTrue();
    }

    /**
     * Stubs the block fetch with the REAL query's half-open overlap semantics
     * ({@code endDatetime > :from AND startDatetime < :to}) instead of returning a fixed list for
     * any bounds. Without this, a test cannot observe the fetch bounds at all: a wildcard
     * {@code any(), any()} stub hands back the same rows however narrow the range is, so any test
     * claiming to guard the padding passes identically with the padding reverted. That is exactly
     * the hole the 2026-08-07 code review found in this file.
     */
    private void stubBlockFetch(UUID coachId, List<CoachAvailabilityBlock> allBlocks) {
        when(blockRepository.findByCoachIdAndEndDatetimeAfterAndStartDatetimeBefore(eq(coachId), any(), any()))
            .thenAnswer(inv -> {
                Instant from = inv.getArgument(1);
                Instant to = inv.getArgument(2);
                return allBlocks.stream()
                    .filter(b -> b.getEndDatetime().isAfter(from) && b.getStartDatetime().isBefore(to))
                    .toList();
            });
    }

    /**
     * Same idea for the booking fetch, mirroring {@code BookingRepository.findOverlappingBookings}'
     * JPQL ({@code requestedStartTime < :endTime AND requestedEndTime > :startTime}).
     */
    private void stubBookingFetch(UUID coachId, List<Booking> allBookings) {
        when(bookingRepository.findOverlappingBookings(eq(coachId), any(), any(), anyList(), isNull()))
            .thenAnswer(inv -> {
                Instant from = inv.getArgument(1);
                Instant to = inv.getArgument(2);
                return allBookings.stream()
                    .filter(b -> b.getRequestedStartTime().isBefore(to)
                        && b.getRequestedEndTime().isAfter(from))
                    .toList();
            });
    }

    private CoachAvailabilityBlock makeBlock(UUID coachId, Instant start, Instant end) {
        CoachAvailabilityBlock block = new CoachAvailabilityBlock();
        block.setId(UUID.randomUUID());
        block.setCoachId(coachId);
        block.setStartDatetime(start);
        block.setEndDatetime(end);
        return block;
    }

    private CoachProfile makeCoachProfile(UUID coachId, Long userId) {
        CoachProfile profile = new CoachProfile();
        profile.setId(coachId);
        profile.setUserId(userId);
        profile.setCanonicalTimezone("Europe/Berlin");
        return profile;
    }

    private CoachAvailabilityWindow makeWindow(UUID windowId, UUID coachId) {
        CoachAvailabilityWindow window = new CoachAvailabilityWindow();
        window.setId(windowId);
        window.setCoachId(coachId);
        window.setDayOfWeek((short) 1);
        window.setStartTime(LocalTime.of(9, 0));
        window.setEndTime(LocalTime.of(17, 0));
        window.setCanonicalTimezone("Europe/Berlin");
        return window;
    }

    private Booking makeBooking(UUID coachId, Instant startTime) {
        Booking booking = new Booking();
        booking.setId(UUID.randomUUID());
        booking.setCoachId(coachId);
        booking.setStatus("CONFIRMED");
        booking.setRequestedStartTime(startTime);
        booking.setRequestedEndTime(startTime.plusSeconds(3600));
        return booking;
    }

    // ----- getAvailabilityCalendar tests (Deferred-18 AC1-AC3) -----

    @Test
    void getAvailabilityCalendar_unknownCoachId_throwsResourceNotFoundException() {
        UUID coachId = UUID.randomUUID();
        when(coachProfileRepository.findById(coachId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAvailabilityCalendar(coachId, LocalDate.of(2026, 6, 15)))
            .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(windowRepository, blockRepository, bookingRepository);
    }

    @Test
    void getAvailabilityCalendar_activeBookingFromAnyRequester_excludedFromComputedSlots_notLeakedIntoBlockResponses() {
        UUID coachId = UUID.randomUUID();
        CoachProfile profile = makeCoachProfile(coachId, COACH_USER_ID);
        LocalDate weekStart = LocalDate.of(2026, 6, 15); // Monday

        CoachAvailabilityWindow window = makeWindow(UUID.randomUUID(), coachId);
        window.setDayOfWeek((short) 1);
        window.setStartTime(LocalTime.of(9, 0));
        window.setEndTime(LocalTime.of(17, 0));
        window.setCanonicalTimezone("Europe/Berlin");

        // 10:00-11:00 Berlin (CEST, UTC+2) — a REQUESTED booking belonging to a different
        // parent/player than whoever is viewing this calendar; AC1 has no notion of "current
        // parent" at all, unlike the deleted frontend bookedStartTimes guard.
        Instant bookingStart = Instant.parse("2026-06-15T08:00:00Z");
        Instant bookingEnd = Instant.parse("2026-06-15T09:00:00Z");
        Booking otherRequestersBooking = makeBooking(coachId, bookingStart);
        otherRequestersBooking.setRequestedEndTime(bookingEnd);
        otherRequestersBooking.setStatus("REQUESTED");

        when(coachProfileRepository.findById(coachId)).thenReturn(Optional.of(profile));
        when(windowRepository.findByCoachId(coachId)).thenReturn(List.of(window));
        when(blockRepository.findByCoachIdAndEndDatetimeAfterAndStartDatetimeBefore(eq(coachId), any(), any()))
            .thenReturn(List.of());
        when(bookingRepository.findOverlappingBookings(
                eq(coachId), any(), any(), eq(BookingService.ACTIVE_SLOT_STATUSES), isNull()))
            .thenReturn(List.of(otherRequestersBooking));
        when(sessionDurationResolver.resolve(coachId)).thenReturn(ONE_HOUR);

        CoachAvailabilityResponse response = service.getAvailabilityCalendar(coachId, weekStart);

        ZoneId berlin = ZoneId.of("Europe/Berlin");
        Instant windowStart = weekStart.atTime(9, 0).atZone(berlin).toInstant();
        Instant windowEnd = weekStart.atTime(17, 0).atZone(berlin).toInstant();

        // UAT.2 AC2: the carve-out is now expressed as "the slot covering the booked hour is
        // absent" rather than "the segment is split" — 7 one-hour slots across an 8-hour window.
        assertThat(response.computedSlots()).containsExactlyInAnyOrder(
            new AvailableSlotResponse(windowStart, bookingStart),
            new AvailableSlotResponse(bookingEnd, bookingEnd.plus(ONE_HOUR)),
            new AvailableSlotResponse(bookingEnd.plusSeconds(3600), bookingEnd.plusSeconds(7200)),
            new AvailableSlotResponse(bookingEnd.plusSeconds(7200), bookingEnd.plusSeconds(10800)),
            new AvailableSlotResponse(bookingEnd.plusSeconds(10800), bookingEnd.plusSeconds(14400)),
            new AvailableSlotResponse(bookingEnd.plusSeconds(14400), bookingEnd.plusSeconds(18000)),
            new AvailableSlotResponse(bookingEnd.plusSeconds(18000), windowEnd));
        // (No separate "the booked slot is absent" assertion: containsExactlyInAnyOrder above
        // already pins the whole collection, so one would be tautological.)
        // A booking is not a block — the transient pseudo-block must never leak into the response.
        assertThat(response.blocks()).isEmpty();
    }

    /**
     * A window whose local range straddles a spring-forward DST gap must contribute no slot.
     *
     * <p>{@code LocalDateTime.atZone()} silently shifts a nonexistent local time forward by the gap
     * length, so the two instants invert even though the local times are correctly ordered — and the
     * DB guard {@code chk_availability_time_order} constrains only the local times, so it cannot
     * catch this. Europe/Berlin loses 02:00-03:00 on 2026-03-29: 02:30 resolves to 01:30Z and 03:00
     * to 01:00Z. Before the guard, {@code computeAvailableSlots} emitted
     * {@code {01:30Z, 01:00Z}} — an {@code AvailableSlotResponse} with {@code startDatetime} after
     * {@code endDatetime}, which the frontend renders as clickable and which 400s on submit behind
     * a generic toast.
     *
     * <p><strong>Mutation-checked:</strong> removing the {@code windowEnd.isAfter(windowStart)}
     * guard fails this test with one inverted slot present instead of none.
     */
    @Test
    void getAvailabilityCalendar_windowStraddlingDstGap_contributesNoSlot() {
        UUID coachId = UUID.randomUUID();
        CoachProfile profile = makeCoachProfile(coachId, COACH_USER_ID);
        // Sunday 2026-03-29 is the European spring-forward date.
        LocalDate weekStart = LocalDate.of(2026, 3, 23); // Monday of that week
        LocalDate dstSunday = LocalDate.of(2026, 3, 29);

        CoachAvailabilityWindow gapWindow = makeWindow(UUID.randomUUID(), coachId);
        gapWindow.setDayOfWeek((short) 7); // Sunday, ISO
        gapWindow.setStartTime(LocalTime.of(2, 30));
        gapWindow.setEndTime(LocalTime.of(3, 0));
        gapWindow.setCanonicalTimezone("Europe/Berlin");

        when(coachProfileRepository.findById(coachId)).thenReturn(Optional.of(profile));
        when(windowRepository.findByCoachId(coachId)).thenReturn(List.of(gapWindow));
        when(blockRepository.findByCoachIdAndEndDatetimeAfterAndStartDatetimeBefore(eq(coachId), any(), any()))
            .thenReturn(List.of());
        when(bookingRepository.findOverlappingBookings(
                eq(coachId), any(), any(), eq(BookingService.ACTIVE_SLOT_STATUSES), isNull()))
            .thenReturn(List.of());

        // Pin the premise rather than assuming it: if a future tzdata revision moved Germany's
        // transition, this fixture would stop exercising the inversion and the test below would
        // pass vacuously.
        ZoneId berlin = ZoneId.of("Europe/Berlin");
        Instant resolvedStart = dstSunday.atTime(2, 30).atZone(berlin).toInstant();
        Instant resolvedEnd = dstSunday.atTime(3, 0).atZone(berlin).toInstant();
        assertThat(resolvedStart)
            .as("fixture premise: 02:30 must fall in the DST gap and resolve AFTER 03:00")
            .isAfter(resolvedEnd);

        CoachAvailabilityResponse response = service.getAvailabilityCalendar(coachId, weekStart);

        assertThat(response.computedSlots())
            .as("an inverted window must be skipped entirely, not emitted as a negative-duration slot")
            .isEmpty();
    }

    @Test
    void getAvailabilityCalendar_padsFetchBoundsByOneDayEachSide_toCoverDivergentWindowZones() {
        UUID coachId = UUID.randomUUID();
        CoachProfile profile = makeCoachProfile(coachId, COACH_USER_ID);
        profile.setCanonicalTimezone("America/Los_Angeles");
        LocalDate weekStart = LocalDate.of(2026, 6, 15); // Monday

        CoachAvailabilityWindow laWindow = makeWindow(UUID.randomUUID(), coachId);
        laWindow.setDayOfWeek((short) 1);
        laWindow.setCanonicalTimezone("America/Los_Angeles");

        when(coachProfileRepository.findById(coachId)).thenReturn(Optional.of(profile));
        when(windowRepository.findByCoachId(coachId)).thenReturn(List.of(laWindow));
        when(blockRepository.findByCoachIdAndEndDatetimeAfterAndStartDatetimeBefore(eq(coachId), any(), any()))
            .thenReturn(List.of());
        when(bookingRepository.findOverlappingBookings(eq(coachId), any(), any(), anyList(), isNull()))
            .thenReturn(List.of());
        when(sessionDurationResolver.resolve(coachId)).thenReturn(ONE_HOUR);

        service.getAvailabilityCalendar(coachId, weekStart);

        // The coach profile's own canonicalTimezone drives the OUTER fetch-window zone — only the
        // per-window instant computation (asserted separately below) reads each window's own zone.
        ZoneId outerZone = ZoneId.of("America/Los_Angeles");
        Instant expectedStart = weekStart.minusDays(2).atStartOfDay(outerZone).toInstant();
        Instant expectedEnd = weekStart.plusDays(9).atStartOfDay(outerZone).toInstant();

        ArgumentCaptor<Instant> startCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> endCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(blockRepository).findByCoachIdAndEndDatetimeAfterAndStartDatetimeBefore(
            eq(coachId), startCaptor.capture(), endCaptor.capture());
        assertThat(startCaptor.getValue()).isEqualTo(expectedStart);
        assertThat(endCaptor.getValue()).isEqualTo(expectedEnd);

        verify(bookingRepository).findOverlappingBookings(
            eq(coachId), eq(expectedStart), eq(expectedEnd), eq(BookingService.ACTIVE_SLOT_STATUSES), isNull());
    }

    @Test
    void getAvailabilityCalendar_windowZoneDivergesWidelyFromOuterZone_blockStillSubtractedButStaysOutOfBlockResponses() {
        // Regression guard for the fetch-window gap AC2 introduces if left under-padded: a window
        // whose own zone diverges from the coach profile's canonicalTimezone by more than the pad
        // has instants that fall outside the fetch range, silently dropping its overlapping
        // blocks/bookings.
        //
        // Zones are chosen to exceed a ONE-day pad on purpose: Pacific/Niue is UTC-11 and
        // Pacific/Kiritimati is UTC+14, a 25h spread. This is what the previous version of this
        // test could not do — it stubbed the fetch with any()/any() matchers, so the block came
        // back whatever bounds were passed and reverting the pad changed nothing it observed.
        // stubBlockFetch applies the real query predicate, so the bounds now actually matter.
        UUID coachId = UUID.randomUUID();
        CoachProfile profile = makeCoachProfile(coachId, COACH_USER_ID);
        profile.setCanonicalTimezone("Pacific/Niue"); // drives the outer zoneId
        LocalDate weekStart = LocalDate.of(2026, 6, 15); // Monday

        CoachAvailabilityWindow niueWindow = makeWindow(UUID.randomUUID(), coachId);
        niueWindow.setDayOfWeek((short) 1);
        niueWindow.setStartTime(LocalTime.of(9, 0));
        niueWindow.setEndTime(LocalTime.of(11, 0));
        niueWindow.setCanonicalTimezone("Pacific/Niue"); // UTC-11

        CoachAvailabilityWindow kiritimatiWindow = makeWindow(UUID.randomUUID(), coachId);
        kiritimatiWindow.setDayOfWeek((short) 1);
        kiritimatiWindow.setStartTime(LocalTime.of(0, 0));
        kiritimatiWindow.setEndTime(LocalTime.of(2, 0));
        kiritimatiWindow.setCanonicalTimezone("Pacific/Kiritimati"); // UTC+14

        // Kiritimati window Monday 00:00-02:00 = 2026-06-14T10:00Z..12:00Z — a full 25h ahead of
        // the outer Niue zone. The block below sits inside that window but ENDS at exactly
        // 2026-06-14T11:00:00Z, which is the instant a one-day pad would have used as its lower
        // fetch bound (2026-06-14T00:00 Niue). The query predicate is endDatetime > :from, so a
        // one-day pad drops this block and the window comes back as one undivided segment.
        // The two-day pad (2026-06-13T11:00Z) fetches it and the window is correctly split.
        CoachAvailabilityBlock beyondOneDayPadBlock = makeBlock(coachId,
            Instant.parse("2026-06-14T10:30:00Z"), Instant.parse("2026-06-14T11:00:00Z"));

        when(coachProfileRepository.findById(coachId)).thenReturn(Optional.of(profile));
        when(windowRepository.findByCoachId(coachId)).thenReturn(List.of(niueWindow, kiritimatiWindow));
        stubBlockFetch(coachId, List.of(beyondOneDayPadBlock));
        stubBookingFetch(coachId, List.of());
        when(sessionDurationResolver.resolve(coachId)).thenReturn(ONE_HOUR);

        CoachAvailabilityResponse response = service.getAvailabilityCalendar(coachId, weekStart);

        // Slot-sliced (UAT.2 AC2), but still discriminating on the pad: without the two-day pad the
        // block is never fetched, the Kiritimati window comes back undivided as 10:00Z-12:00Z, and
        // that yields a 10:00-11:00 slot which must NOT be present here.
        assertThat(response.computedSlots()).containsExactlyInAnyOrder(
            // Niue window, Monday 09:00-11:00 at UTC-11 -> two one-hour slots
            new AvailableSlotResponse(
                Instant.parse("2026-06-15T20:00:00Z"), Instant.parse("2026-06-15T21:00:00Z")),
            new AvailableSlotResponse(
                Instant.parse("2026-06-15T21:00:00Z"), Instant.parse("2026-06-15T22:00:00Z")),
            // Kiritimati window: the pre-block 10:00-10:30 segment is too short for a session and
            // drops out entirely; only the post-block hour survives.
            new AvailableSlotResponse(
                Instant.parse("2026-06-14T11:00:00Z"), Instant.parse("2026-06-14T12:00:00Z")));

        // AC2: blockResponses (the API's `blocks` field) stays exactly week-scoped — the padding
        // that made the block fetchable at all must not leak it into the response. weekStartExact
        // is 2026-06-15T11:00Z (Niue), and the block ends well before it.
        assertThat(response.blocks()).isEmpty();
    }

    @Test
    void getAvailabilityCalendar_bookingOnDivergentZoneWindow_excludedEvenBeyondOneDayOfPadding() {
        // The AC1 x AC2 combination the Dev Notes mandate ("a coach with per-window-divergent zones
        // AND an overlapping booking on one of those windows"), which no test covered: the AC1 test
        // used a single Europe/Berlin window, and the AC2 divergence test used a block with the
        // booking fetch stubbed empty. This also pins the padding for the BOOKING query specifically
        // — it shares the same bounds as the block query but is a separate call.
        UUID coachId = UUID.randomUUID();
        CoachProfile profile = makeCoachProfile(coachId, COACH_USER_ID);
        profile.setCanonicalTimezone("Pacific/Niue"); // drives the outer zoneId
        LocalDate weekStart = LocalDate.of(2026, 6, 15); // Monday

        CoachAvailabilityWindow niueWindow = makeWindow(UUID.randomUUID(), coachId);
        niueWindow.setDayOfWeek((short) 1);
        niueWindow.setStartTime(LocalTime.of(9, 0));
        niueWindow.setEndTime(LocalTime.of(11, 0));
        niueWindow.setCanonicalTimezone("Pacific/Niue"); // UTC-11

        CoachAvailabilityWindow kiritimatiWindow = makeWindow(UUID.randomUUID(), coachId);
        kiritimatiWindow.setDayOfWeek((short) 1);
        kiritimatiWindow.setStartTime(LocalTime.of(0, 0));
        kiritimatiWindow.setEndTime(LocalTime.of(2, 0));
        kiritimatiWindow.setCanonicalTimezone("Pacific/Kiritimati"); // UTC+14

        // 2026-06-14T10:15Z..10:45Z — inside the Kiritimati window (10:00Z-12:00Z), but the whole
        // booking ends before a one-day pad's lower bound of 2026-06-14T11:00Z, so requestedEndTime
        // > :startTime fails and the booking is never fetched under the old pad.
        Booking booking = makeBooking(coachId, Instant.parse("2026-06-14T10:15:00Z"));
        booking.setRequestedEndTime(Instant.parse("2026-06-14T10:45:00Z"));
        booking.setStatus("REQUESTED");

        when(coachProfileRepository.findById(coachId)).thenReturn(Optional.of(profile));
        when(windowRepository.findByCoachId(coachId)).thenReturn(List.of(niueWindow, kiritimatiWindow));
        stubBlockFetch(coachId, List.of());
        stubBookingFetch(coachId, List.of(booking));
        when(sessionDurationResolver.resolve(coachId)).thenReturn(ONE_HOUR);

        CoachAvailabilityResponse response = service.getAvailabilityCalendar(coachId, weekStart);

        // Slot-sliced (UAT.2 AC2). Still discriminating on the pad: were the booking not fetched,
        // the Kiritimati window would slice into 10:00-11:00 and 11:00-12:00; instead the pre-
        // booking 15-minute sliver drops out and the post-booking run is anchored to 10:45.
        assertThat(response.computedSlots()).containsExactlyInAnyOrder(
            new AvailableSlotResponse(
                Instant.parse("2026-06-15T20:00:00Z"), Instant.parse("2026-06-15T21:00:00Z")),
            new AvailableSlotResponse(
                Instant.parse("2026-06-15T21:00:00Z"), Instant.parse("2026-06-15T22:00:00Z")),
            new AvailableSlotResponse(
                Instant.parse("2026-06-14T10:45:00Z"), Instant.parse("2026-06-14T11:45:00Z")));

        assertThat(response.blocks()).isEmpty();
    }

    // skillars-deferred-65 AC3: proves the fix itself, not just preserved old assertions. Before
    // this fix, the outer week-scoping zone came from windows.get(0) off an unordered
    // CoachAvailabilityWindowRepository.findByCoachId result — row-order luck. This calls the
    // service twice with the SAME two windows in OPPOSITE list order and asserts the fetch bounds
    // are identical both times, driven only by the coach profile's canonicalTimezone (Asia/Tokyo),
    // which matches neither window's own zone — the exact case that would have failed against the
    // pre-fix windows.get(0) code (whichever window sorted first would have driven a different
    // outer zone on each call).
    @Test
    void getAvailabilityCalendar_outerFetchBoundsFollowCoachProfileZone_invariantToWindowListOrder() {
        UUID coachId = UUID.randomUUID();
        CoachProfile profile = makeCoachProfile(coachId, COACH_USER_ID);
        profile.setCanonicalTimezone("Asia/Tokyo");
        LocalDate weekStart = LocalDate.of(2026, 6, 15); // Monday

        CoachAvailabilityWindow laWindow = makeWindow(UUID.randomUUID(), coachId);
        laWindow.setDayOfWeek((short) 1);
        laWindow.setCanonicalTimezone("America/Los_Angeles");

        CoachAvailabilityWindow niueWindow = makeWindow(UUID.randomUUID(), coachId);
        niueWindow.setDayOfWeek((short) 1);
        niueWindow.setCanonicalTimezone("Pacific/Niue");

        when(coachProfileRepository.findById(coachId)).thenReturn(Optional.of(profile));
        when(blockRepository.findByCoachIdAndEndDatetimeAfterAndStartDatetimeBefore(eq(coachId), any(), any()))
            .thenReturn(List.of());
        when(bookingRepository.findOverlappingBookings(eq(coachId), any(), any(), anyList(), isNull()))
            .thenReturn(List.of());
        when(sessionDurationResolver.resolve(coachId)).thenReturn(ONE_HOUR);

        ZoneId profileZone = ZoneId.of("Asia/Tokyo");
        Instant expectedStart = weekStart.minusDays(2).atStartOfDay(profileZone).toInstant();
        Instant expectedEnd = weekStart.plusDays(9).atStartOfDay(profileZone).toInstant();

        when(windowRepository.findByCoachId(coachId)).thenReturn(List.of(laWindow, niueWindow));
        service.getAvailabilityCalendar(coachId, weekStart);

        when(windowRepository.findByCoachId(coachId)).thenReturn(List.of(niueWindow, laWindow));
        service.getAvailabilityCalendar(coachId, weekStart);

        ArgumentCaptor<Instant> startCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> endCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(blockRepository, times(2)).findByCoachIdAndEndDatetimeAfterAndStartDatetimeBefore(
            eq(coachId), startCaptor.capture(), endCaptor.capture());

        assertThat(startCaptor.getAllValues()).containsExactly(expectedStart, expectedStart);
        assertThat(endCaptor.getAllValues()).containsExactly(expectedEnd, expectedEnd);
    }

    @Test
    void getAvailabilityCalendar_coachExistsWithBlankTimezone_returns200WithUtcFallbackNot404() {
        // AC3 explicitly forbids collapsing "coach doesn't exist" and "coach exists but has no zone"
        // into the same 404. Nothing pinned the second half — every other fixture seeds
        // Europe/Berlin — so a later refactor merging the two conditions would go unnoticed.
        UUID coachId = UUID.randomUUID();
        CoachProfile profile = makeCoachProfile(coachId, COACH_USER_ID);
        profile.setCanonicalTimezone("   ");

        when(coachProfileRepository.findById(coachId)).thenReturn(Optional.of(profile));
        when(windowRepository.findByCoachId(coachId)).thenReturn(List.of());
        stubBlockFetch(coachId, List.of());
        stubBookingFetch(coachId, List.of());

        CoachAvailabilityResponse response = service.getAvailabilityCalendar(coachId, LocalDate.of(2026, 6, 15));

        assertThat(response.canonicalTimezone()).isEqualTo("UTC");
        assertThat(response.windows()).isEmpty();
        assertThat(response.computedSlots()).isEmpty();
    }

    // ----- computeAvailableSlots tests -----

    @Test
    void computeAvailableSlots_noBlocks_slicesTheWholeWindowIntoSessions() {
        // Rewritten for UAT.2 AC2: this used to assert one whole-window segment, which is the
        // defect — a 09:00–13:00 window rendered as ONE clickable row that booked four hours.
        Instant start = Instant.parse("2026-06-16T09:00:00Z");
        Instant end = Instant.parse("2026-06-16T13:00:00Z");

        List<AvailableSlotResponse> result =
            service.computeAvailableSlots(start, end, List.of(), ONE_HOUR);

        assertThat(result).hasSize(4);
        assertThat(result).extracting(AvailableSlotResponse::startDatetime).containsExactly(
            Instant.parse("2026-06-16T09:00:00Z"), Instant.parse("2026-06-16T10:00:00Z"),
            Instant.parse("2026-06-16T11:00:00Z"), Instant.parse("2026-06-16T12:00:00Z"));
        assertThat(result).extracting(AvailableSlotResponse::endDatetime).containsExactly(
            Instant.parse("2026-06-16T10:00:00Z"), Instant.parse("2026-06-16T11:00:00Z"),
            Instant.parse("2026-06-16T12:00:00Z"), Instant.parse("2026-06-16T13:00:00Z"));
    }

    @Test
    void computeAvailableSlots_fullBlock_returnsEmpty() {
        Instant windowStart = Instant.parse("2026-06-16T09:00:00Z");
        Instant windowEnd = Instant.parse("2026-06-16T13:00:00Z");

        CoachAvailabilityBlock block = blockWith(
            Instant.parse("2026-06-16T08:00:00Z"),
            Instant.parse("2026-06-16T14:00:00Z")
        );

        List<AvailableSlotResponse> result =
            service.computeAvailableSlots(windowStart, windowEnd, List.of(block), ONE_HOUR);

        assertThat(result).isEmpty();
    }

    @Test
    void computeAvailableSlots_partialOverlap_slicesBothSidesOfTheBlock() {
        // 10:30–11:00 block on a 09:00–14:00 window → free segments [09:00–10:30] and [11:00–14:00].
        // Deliberately NOT whole multiples of the session length: the first segment yields ONE slot
        // and drops its trailing half-hour, the second yields THREE. Under the old whole-segment
        // behaviour this returned two rows of 90 and 180 minutes, so the fixture discriminates —
        // a fixture whose segments are each exactly one session long cannot.
        Instant windowStart = Instant.parse("2026-06-16T09:00:00Z");
        Instant windowEnd = Instant.parse("2026-06-16T14:00:00Z");

        CoachAvailabilityBlock block = blockWith(
            Instant.parse("2026-06-16T10:30:00Z"),
            Instant.parse("2026-06-16T11:00:00Z")
        );

        List<AvailableSlotResponse> result =
            service.computeAvailableSlots(windowStart, windowEnd, List.of(block), ONE_HOUR);

        assertThat(result).extracting(AvailableSlotResponse::startDatetime).containsExactly(
            Instant.parse("2026-06-16T09:00:00Z"),
            Instant.parse("2026-06-16T11:00:00Z"),
            Instant.parse("2026-06-16T12:00:00Z"),
            Instant.parse("2026-06-16T13:00:00Z"));
        assertThat(result).extracting(AvailableSlotResponse::endDatetime).containsExactly(
            Instant.parse("2026-06-16T10:00:00Z"),
            Instant.parse("2026-06-16T12:00:00Z"),
            Instant.parse("2026-06-16T13:00:00Z"),
            Instant.parse("2026-06-16T14:00:00Z"));
    }

    @Test
    void computeAvailableSlots_multipleWindows_multipleBlocks() {
        // Window: 09:00–11:00, two blocks: 09:30–10:00 and 10:30–11:30 → free segments
        // [09:00–09:30] and [10:00–10:30], both HALF an hour. At a 60-minute session length each
        // is too short to hold a session, so the coach offers nothing here — which is the point of
        // slicing: a 30-minute gap is no longer bookable as if it were a full lesson.
        Instant windowStart = Instant.parse("2026-06-16T09:00:00Z");
        Instant windowEnd = Instant.parse("2026-06-16T11:00:00Z");

        CoachAvailabilityBlock block1 = blockWith(
            Instant.parse("2026-06-16T09:30:00Z"),
            Instant.parse("2026-06-16T10:00:00Z")
        );
        CoachAvailabilityBlock block2 = blockWith(
            Instant.parse("2026-06-16T10:30:00Z"),
            Instant.parse("2026-06-16T12:00:00Z")
        );

        assertThat(service.computeAvailableSlots(windowStart, windowEnd, List.of(block1, block2), ONE_HOUR))
            .isEmpty();

        // Same fixture at a 30-minute length yields exactly the two segments the pre-slicing
        // behaviour returned — the segment computation itself is unchanged.
        List<AvailableSlotResponse> halfHour = service.computeAvailableSlots(
            windowStart, windowEnd, List.of(block1, block2), Duration.ofMinutes(30));

        assertThat(halfHour).hasSize(2);
        assertThat(halfHour.get(0).startDatetime()).isEqualTo(Instant.parse("2026-06-16T09:00:00Z"));
        assertThat(halfHour.get(0).endDatetime()).isEqualTo(Instant.parse("2026-06-16T09:30:00Z"));
        assertThat(halfHour.get(1).startDatetime()).isEqualTo(Instant.parse("2026-06-16T10:00:00Z"));
        assertThat(halfHour.get(1).endDatetime()).isEqualTo(Instant.parse("2026-06-16T10:30:00Z"));
    }

    /** AC2's worked case: an eight-hour working day is eight bookable hours, not one. */
    @Test
    void computeAvailableSlots_fullWorkingDay_yieldsOneSlotPerHour() {
        List<AvailableSlotResponse> result = service.computeAvailableSlots(
            Instant.parse("2026-06-16T09:00:00Z"), Instant.parse("2026-06-16T17:00:00Z"),
            List.of(), ONE_HOUR);

        assertThat(result).hasSize(8);
        assertThat(result.get(0).startDatetime()).isEqualTo(Instant.parse("2026-06-16T09:00:00Z"));
        assertThat(result.get(7).startDatetime()).isEqualTo(Instant.parse("2026-06-16T16:00:00Z"));
        assertThat(result.get(7).endDatetime()).isEqualTo(Instant.parse("2026-06-16T17:00:00Z"));
    }

    /** AC2's worked case: a lunch block splits the day into 3 slots before and 4 after. */
    @Test
    void computeAvailableSlots_workingDayWithLunchBlock_yieldsSevenSlots() {
        CoachAvailabilityBlock lunch = blockWith(
            Instant.parse("2026-06-16T12:00:00Z"), Instant.parse("2026-06-16T13:00:00Z"));

        List<AvailableSlotResponse> result = service.computeAvailableSlots(
            Instant.parse("2026-06-16T09:00:00Z"), Instant.parse("2026-06-16T17:00:00Z"),
            List.of(lunch), ONE_HOUR);

        assertThat(result).hasSize(7);
        assertThat(result).extracting(AvailableSlotResponse::startDatetime).containsExactly(
            Instant.parse("2026-06-16T09:00:00Z"), Instant.parse("2026-06-16T10:00:00Z"),
            Instant.parse("2026-06-16T11:00:00Z"),
            Instant.parse("2026-06-16T13:00:00Z"), Instant.parse("2026-06-16T14:00:00Z"),
            Instant.parse("2026-06-16T15:00:00Z"), Instant.parse("2026-06-16T16:00:00Z"));
    }

    /** AC2's worked case: the trailing remainder shorter than one session is dropped. */
    @Test
    void computeAvailableSlots_windowWithTrailingRemainder_dropsTheRemainder() {
        List<AvailableSlotResponse> result = service.computeAvailableSlots(
            Instant.parse("2026-06-16T09:00:00Z"), Instant.parse("2026-06-16T10:30:00Z"),
            List.of(), ONE_HOUR);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).startDatetime()).isEqualTo(Instant.parse("2026-06-16T09:00:00Z"));
        assertThat(result.get(0).endDatetime()).isEqualTo(Instant.parse("2026-06-16T10:00:00Z"));
    }

    /** AC2's worked case: a segment shorter than one session yields nothing at all. */
    @Test
    void computeAvailableSlots_windowShorterThanOneSession_yieldsNoSlots() {
        assertThat(service.computeAvailableSlots(
            Instant.parse("2026-06-16T09:00:00Z"), Instant.parse("2026-06-16T09:45:00Z"),
            List.of(), ONE_HOUR)).isEmpty();
    }

    /** AC2's worked case: a coach override of 90 minutes gives 5 slots and drops 16:30–17:00. */
    @Test
    void computeAvailableSlots_ninetyMinuteCoach_yieldsFiveSlotsAndDropsTheTail() {
        List<AvailableSlotResponse> result = service.computeAvailableSlots(
            Instant.parse("2026-06-16T09:00:00Z"), Instant.parse("2026-06-16T17:00:00Z"),
            List.of(), Duration.ofMinutes(90));

        assertThat(result).extracting(AvailableSlotResponse::startDatetime).containsExactly(
            Instant.parse("2026-06-16T09:00:00Z"), Instant.parse("2026-06-16T10:30:00Z"),
            Instant.parse("2026-06-16T12:00:00Z"), Instant.parse("2026-06-16T13:30:00Z"),
            Instant.parse("2026-06-16T15:00:00Z"));
        assertThat(result).extracting(AvailableSlotResponse::endDatetime).containsExactly(
            Instant.parse("2026-06-16T10:30:00Z"), Instant.parse("2026-06-16T12:00:00Z"),
            Instant.parse("2026-06-16T13:30:00Z"), Instant.parse("2026-06-16T15:00:00Z"),
            Instant.parse("2026-06-16T16:30:00Z"));
    }

    /**
     * The grid is anchored to each SEGMENT's start, not to the window's — the post-block run
     * begins when the coach actually becomes free. Pinned so nobody "tidies" it into a
     * window-anchored grid, which would silently discard the 12:45–13:00 fragment.
     */
    @Test
    void computeAvailableSlots_offGridBlock_anchorsThePostBlockRunToTheBlockEnd() {
        CoachAvailabilityBlock block = blockWith(
            Instant.parse("2026-06-16T12:00:00Z"), Instant.parse("2026-06-16T12:45:00Z"));

        List<AvailableSlotResponse> result = service.computeAvailableSlots(
            Instant.parse("2026-06-16T09:00:00Z"), Instant.parse("2026-06-16T17:00:00Z"),
            List.of(block), ONE_HOUR);

        assertThat(result).extracting(AvailableSlotResponse::startDatetime).containsExactly(
            Instant.parse("2026-06-16T09:00:00Z"), Instant.parse("2026-06-16T10:00:00Z"),
            Instant.parse("2026-06-16T11:00:00Z"),
            Instant.parse("2026-06-16T12:45:00Z"), Instant.parse("2026-06-16T13:45:00Z"),
            Instant.parse("2026-06-16T14:45:00Z"), Instant.parse("2026-06-16T15:45:00Z"));
    }

    /**
     * A non-positive length would loop forever rather than fail; it must fail loudly instead.
     * Both branches of the guard are exercised — zero and negative are separate conditions.
     */
    @Test
    void computeAvailableSlots_nonPositiveSlotLength_throwsRatherThanLooping() {
        assertThatThrownBy(() -> service.computeAvailableSlots(
            Instant.parse("2026-06-16T09:00:00Z"), Instant.parse("2026-06-16T17:00:00Z"),
            List.of(), Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> service.computeAvailableSlots(
            Instant.parse("2026-06-16T09:00:00Z"), Instant.parse("2026-06-16T17:00:00Z"),
            List.of(), Duration.ofMinutes(-30)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private CoachAvailabilityBlock blockWith(Instant start, Instant end) {
        return Instancio.of(CoachAvailabilityBlock.class)
            .set(field(CoachAvailabilityBlock::getId), UUID.randomUUID())
            .set(field(CoachAvailabilityBlock::getCoachId), UUID.randomUUID())
            .set(field(CoachAvailabilityBlock::getStartDatetime), start)
            .set(field(CoachAvailabilityBlock::getEndDatetime), end)
            .create();
    }

    // ----- computeAvailabilitySignature tests (AC2) -----

    @Test
    void computeAvailabilitySignature_sameWindowsAndDuration_producesIdenticalSignature() {
        UUID coachId = UUID.randomUUID();
        CoachAvailabilityWindow window = makeWindow(UUID.randomUUID(), coachId);

        String first = AvailabilityService.computeAvailabilitySignature(List.of(window), ONE_HOUR);
        String second = AvailabilityService.computeAvailabilitySignature(List.of(window), ONE_HOUR);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void computeAvailabilitySignature_changedWindowTime_producesDifferentSignature() {
        UUID coachId = UUID.randomUUID();
        CoachAvailabilityWindow window = makeWindow(UUID.randomUUID(), coachId);
        String baseline = AvailabilityService.computeAvailabilitySignature(List.of(window), ONE_HOUR);

        CoachAvailabilityWindow changed = makeWindow(window.getId(), coachId);
        changed.setStartTime(window.getStartTime().plusMinutes(15));
        String withChangedTime = AvailabilityService.computeAvailabilitySignature(List.of(changed), ONE_HOUR);

        assertThat(withChangedTime).isNotEqualTo(baseline);
    }

    @Test
    void computeAvailabilitySignature_addedWindow_producesDifferentSignature() {
        UUID coachId = UUID.randomUUID();
        CoachAvailabilityWindow window = makeWindow(UUID.randomUUID(), coachId);
        String baseline = AvailabilityService.computeAvailabilitySignature(List.of(window), ONE_HOUR);

        CoachAvailabilityWindow addedWindow = makeWindow(UUID.randomUUID(), coachId);
        String withAddedWindow =
            AvailabilityService.computeAvailabilitySignature(List.of(window, addedWindow), ONE_HOUR);

        assertThat(withAddedWindow).isNotEqualTo(baseline);
    }

    @Test
    void computeAvailabilitySignature_removedWindow_producesDifferentSignature() {
        UUID coachId = UUID.randomUUID();
        CoachAvailabilityWindow window1 = makeWindow(UUID.randomUUID(), coachId);
        CoachAvailabilityWindow window2 = makeWindow(UUID.randomUUID(), coachId);
        String baseline = AvailabilityService.computeAvailabilitySignature(List.of(window1, window2), ONE_HOUR);

        String withWindowRemoved = AvailabilityService.computeAvailabilitySignature(List.of(window1), ONE_HOUR);

        assertThat(withWindowRemoved).isNotEqualTo(baseline);
    }

    @Test
    void computeAvailabilitySignature_changedDuration_producesDifferentSignature() {
        UUID coachId = UUID.randomUUID();
        CoachAvailabilityWindow window = makeWindow(UUID.randomUUID(), coachId);
        String baseline = AvailabilityService.computeAvailabilitySignature(List.of(window), ONE_HOUR);

        String withChangedDuration =
            AvailabilityService.computeAvailabilitySignature(List.of(window), Duration.ofMinutes(90));

        assertThat(withChangedDuration).isNotEqualTo(baseline);
    }
}
