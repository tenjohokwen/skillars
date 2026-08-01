package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.platform.booking.contract.AvailabilityWindowResponse;
import com.softropic.skillars.platform.booking.contract.AvailableSlotResponse;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.instancio.Select.field;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
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

    @InjectMocks
    AvailabilityService service;

    private static final Long COACH_USER_ID = 300L;

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

    // ----- computeAvailableSlots tests -----

    @Test
    void computeAvailableSlots_noBlocks_returnsFullWindows() {
        Instant start = Instant.parse("2026-06-16T09:00:00Z");
        Instant end = Instant.parse("2026-06-16T13:00:00Z");

        List<AvailableSlotResponse> result = service.computeAvailableSlots(start, end, List.of());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).startDatetime()).isEqualTo(start);
        assertThat(result.get(0).endDatetime()).isEqualTo(end);
    }

    @Test
    void computeAvailableSlots_fullBlock_returnsEmpty() {
        Instant windowStart = Instant.parse("2026-06-16T09:00:00Z");
        Instant windowEnd = Instant.parse("2026-06-16T13:00:00Z");

        CoachAvailabilityBlock block = blockWith(
            Instant.parse("2026-06-16T08:00:00Z"),
            Instant.parse("2026-06-16T14:00:00Z")
        );

        List<AvailableSlotResponse> result = service.computeAvailableSlots(windowStart, windowEnd, List.of(block));

        assertThat(result).isEmpty();
    }

    @Test
    void computeAvailableSlots_partialOverlap_returnsTwoSegments() {
        // AC 6: 10:00–12:00 block on 09:00–13:00 window → segments [09:00–10:00] and [12:00–13:00]
        Instant windowStart = Instant.parse("2026-06-16T09:00:00Z");
        Instant windowEnd = Instant.parse("2026-06-16T13:00:00Z");

        CoachAvailabilityBlock block = blockWith(
            Instant.parse("2026-06-16T10:00:00Z"),
            Instant.parse("2026-06-16T12:00:00Z")
        );

        List<AvailableSlotResponse> result = service.computeAvailableSlots(windowStart, windowEnd, List.of(block));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).startDatetime()).isEqualTo(Instant.parse("2026-06-16T09:00:00Z"));
        assertThat(result.get(0).endDatetime()).isEqualTo(Instant.parse("2026-06-16T10:00:00Z"));
        assertThat(result.get(1).startDatetime()).isEqualTo(Instant.parse("2026-06-16T12:00:00Z"));
        assertThat(result.get(1).endDatetime()).isEqualTo(Instant.parse("2026-06-16T13:00:00Z"));
    }

    @Test
    void computeAvailableSlots_multipleWindows_multipleBlocks() {
        // Window: 09:00–11:00, two blocks: 09:30–10:00 and 10:30–11:30
        // Expected: [09:00–09:30], [10:00–10:30] (11:00 cap applies)
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

        List<AvailableSlotResponse> result = service.computeAvailableSlots(windowStart, windowEnd, List.of(block1, block2));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).startDatetime()).isEqualTo(Instant.parse("2026-06-16T09:00:00Z"));
        assertThat(result.get(0).endDatetime()).isEqualTo(Instant.parse("2026-06-16T09:30:00Z"));
        assertThat(result.get(1).startDatetime()).isEqualTo(Instant.parse("2026-06-16T10:00:00Z"));
        assertThat(result.get(1).endDatetime()).isEqualTo(Instant.parse("2026-06-16T10:30:00Z"));
    }

    private CoachAvailabilityBlock blockWith(Instant start, Instant end) {
        return Instancio.of(CoachAvailabilityBlock.class)
            .set(field(CoachAvailabilityBlock::getId), UUID.randomUUID())
            .set(field(CoachAvailabilityBlock::getCoachId), UUID.randomUUID())
            .set(field(CoachAvailabilityBlock::getStartDatetime), start)
            .set(field(CoachAvailabilityBlock::getEndDatetime), end)
            .create();
    }
}
