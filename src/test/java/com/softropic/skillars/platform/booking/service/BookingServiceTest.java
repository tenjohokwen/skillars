package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.platform.booking.contract.BookingConfirmedEvent;
import com.softropic.skillars.platform.booking.contract.BookingDeclinedEvent;
import com.softropic.skillars.platform.booking.contract.BookingRequestedEvent;
import com.softropic.skillars.platform.booking.contract.BookingResponse;
import com.softropic.skillars.platform.booking.contract.CreateBookingRequest;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.booking.repo.SessionPackPurchasedRepository;
import com.softropic.skillars.platform.marketplace.contract.CoachProfileStatus;
import com.softropic.skillars.platform.marketplace.repo.CoachAvailabilityWindow;
import com.softropic.skillars.platform.marketplace.repo.CoachAvailabilityWindowRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;
import org.instancio.Instancio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private SessionPackService sessionPackService;
    @Mock private CoachProfileRepository coachProfileRepository;
    @Mock private CoachAvailabilityWindowRepository coachAvailabilityWindowRepository;
    @Mock private PlayerProfileRepository playerProfileRepository;
    @Mock private UserRepository userRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private SessionPackPurchasedRepository sessionPackPurchasedRepository;

    private BookingService bookingService;

    private static final Long PARENT_ID = 100L;
    private static final Long PLAYER_ID = 200L;
    private static final UUID COACH_ID = UUID.randomUUID();
    private static final Long COACH_USER_ID = 300L;

    @BeforeEach
    void setUp() {
        bookingService = new BookingService(
            bookingRepository, sessionPackService, coachProfileRepository,
            coachAvailabilityWindowRepository, playerProfileRepository,
            userRepository, eventPublisher, sessionPackPurchasedRepository
        );
    }

    // ---- createBookingRequest tests ----

    @Test
    void createBookingRequest_hasCredits_createsRequestedBooking() {
        PlayerProfile player = makePlayer(PLAYER_ID, PARENT_ID);
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);
        CoachAvailabilityWindow window = makeCoveringWindow(COACH_ID);
        Booking savedBooking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "REQUESTED");

        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
        when(coachAvailabilityWindowRepository.findByCoachId(COACH_ID)).thenReturn(List.of(window));
        when(sessionPackPurchasedRepository.findActivePacksForDeduction(PLAYER_ID, COACH_ID)).thenReturn(List.of());
        when(sessionPackService.hasCredits(PLAYER_ID, COACH_ID)).thenReturn(true);
        when(sessionPackService.getCreditsRemaining(PLAYER_ID, COACH_ID)).thenReturn(3);
        when(bookingRepository.countInFlightBookings(PLAYER_ID, COACH_ID)).thenReturn(0L);
        when(bookingRepository.save(any(Booking.class))).thenReturn(savedBooking);
        when(userRepository.findById(COACH_USER_ID)).thenReturn(Optional.of(makeUser("coach@test.com")));

        CreateBookingRequest req = makeValidRequest(COACH_ID, PLAYER_ID, window);
        BookingResponse response = bookingService.createBookingRequest(PARENT_ID, req);

        assertThat(response).isNotNull();
        verify(bookingRepository).save(any(Booking.class));
        verify(eventPublisher).publishEvent(any(BookingRequestedEvent.class));
    }

    @Test
    void createBookingRequest_noCredits_throwsOperationNotAllowedException() {
        PlayerProfile player = makePlayer(PLAYER_ID, PARENT_ID);
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);
        CoachAvailabilityWindow window = makeCoveringWindow(COACH_ID);

        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
        when(coachAvailabilityWindowRepository.findByCoachId(COACH_ID)).thenReturn(List.of(window));
        when(sessionPackPurchasedRepository.findActivePacksForDeduction(PLAYER_ID, COACH_ID)).thenReturn(List.of());
        when(sessionPackService.hasCredits(PLAYER_ID, COACH_ID)).thenReturn(false);

        CreateBookingRequest req = makeValidRequest(COACH_ID, PLAYER_ID, window);

        assertThatThrownBy(() -> bookingService.createBookingRequest(PARENT_ID, req))
            .isInstanceOf(OperationNotAllowedException.class);
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void createBookingRequest_playerNotOwnedByParent_throwsOperationNotAllowedException() {
        PlayerProfile player = makePlayer(PLAYER_ID, 999L);  // owned by different parent

        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));

        CreateBookingRequest req = makeValidRequest(COACH_ID, PLAYER_ID, makeCoveringWindow(COACH_ID));

        assertThatThrownBy(() -> bookingService.createBookingRequest(PARENT_ID, req))
            .isInstanceOf(OperationNotAllowedException.class);
    }

    @Test
    void createBookingRequest_coachInDraftStatus_throwsOperationNotAllowedException() {
        PlayerProfile player = makePlayer(PLAYER_ID, PARENT_ID);
        CoachProfile coach = makeDraftCoach(COACH_ID, COACH_USER_ID);

        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));

        CreateBookingRequest req = makeValidRequest(COACH_ID, PLAYER_ID, makeCoveringWindow(COACH_ID));

        assertThatThrownBy(() -> bookingService.createBookingRequest(PARENT_ID, req))
            .isInstanceOf(OperationNotAllowedException.class);
    }

    @Test
    void createBookingRequest_slotOutsideAvailabilityWindows_throwsOperationNotAllowedException() {
        PlayerProfile player = makePlayer(PLAYER_ID, PARENT_ID);
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);

        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
        when(coachAvailabilityWindowRepository.findByCoachId(COACH_ID)).thenReturn(Collections.emptyList());

        CreateBookingRequest req = makeValidRequest(COACH_ID, PLAYER_ID, makeCoveringWindow(COACH_ID));

        assertThatThrownBy(() -> bookingService.createBookingRequest(PARENT_ID, req))
            .isInstanceOf(OperationNotAllowedException.class);
    }

    @Test
    void createBookingRequest_requestedStartTimeInPast_throwsOperationNotAllowedException() {
        PlayerProfile player = makePlayer(PLAYER_ID, PARENT_ID);
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);

        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));

        // Request with past start time — service throws before reaching window check
        Instant pastTime = Instant.now().minusSeconds(3600);
        CreateBookingRequest req = new CreateBookingRequest(
            COACH_ID, PLAYER_ID, pastTime, pastTime.plusSeconds(3600),
            "Europe/Berlin", null
        );

        assertThatThrownBy(() -> bookingService.createBookingRequest(PARENT_ID, req))
            .isInstanceOf(OperationNotAllowedException.class);
    }

    // ---- acceptBooking tests ----

    @Test
    void acceptBooking_requestedBooking_transitionsToConfirmed() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "REQUESTED");
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        when(bookingRepository.save(any(Booking.class))).thenReturn(booking);
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(makeUser("parent@test.com")));
        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(makePlayer(PLAYER_ID, PARENT_ID)));

        BookingResponse response = bookingService.acceptBooking(booking.getId(), COACH_USER_ID);

        assertThat(booking.getStatus()).isEqualTo("CONFIRMED");
        verify(eventPublisher).publishEvent(any(BookingConfirmedEvent.class));
    }

    @Test
    void acceptBooking_alreadyDeclined_throwsOperationNotAllowedException() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "DECLINED");
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));

        assertThatThrownBy(() -> bookingService.acceptBooking(booking.getId(), COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class);
    }

    // ---- declineBooking tests ----

    @Test
    void declineBooking_requestedBooking_transitionsToDeclined() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "REQUESTED");
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        when(bookingRepository.save(any(Booking.class))).thenReturn(booking);
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(makeUser("parent@test.com")));

        bookingService.declineBooking(booking.getId(), COACH_USER_ID);

        assertThat(booking.getStatus()).isEqualTo("DECLINED");
        verify(eventPublisher).publishEvent(any(BookingDeclinedEvent.class));
    }

    @Test
    void declineBooking_confirmedBooking_throwsOperationNotAllowedException() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "CONFIRMED");
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));

        assertThatThrownBy(() -> bookingService.declineBooking(booking.getId(), COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class);
    }

    @Test
    void transition_invalidTransition_throwsIllegalState() {
        Booking booking = makeBooking(PARENT_ID, PLAYER_ID, COACH_ID, "UPCOMING");
        CoachProfile coach = makeActiveCoach(COACH_ID, COACH_USER_ID);

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));

        assertThatThrownBy(() -> bookingService.declineBooking(booking.getId(), COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class);
    }

    // ---- helpers ----

    private PlayerProfile makePlayer(Long id, Long parentId) {
        PlayerProfile p = new PlayerProfile();
        p.setName("Test Player");
        try {
            var f = com.softropic.skillars.infrastructure.persistence.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(p, id);
        } catch (Exception ignored) {}
        p.setParentId(parentId);
        return p;
    }

    private CoachProfile makeActiveCoach(UUID coachId, Long userId) {
        CoachProfile c = new CoachProfile();
        try {
            var f = CoachProfile.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(c, coachId);
        } catch (Exception ignored) {}
        c.setUserId(userId);
        c.setDisplayName("Test Coach");
        c.setStatus(CoachProfileStatus.ACTIVE);
        c.setCanonicalTimezone("Europe/Berlin");
        return c;
    }

    private CoachProfile makeDraftCoach(UUID coachId, Long userId) {
        CoachProfile c = makeActiveCoach(coachId, userId);
        c.setStatus(CoachProfileStatus.DRAFT);
        return c;
    }

    private CoachAvailabilityWindow makeCoveringWindow(UUID coachId) {
        // Creates a window covering the next Monday 10:00–12:00 in Europe/Berlin
        CoachAvailabilityWindow w = new CoachAvailabilityWindow();
        w.setCoachId(coachId);
        // Use all 7 days to ensure coverage
        ZonedDateTime futureSlot = ZonedDateTime.now(ZoneId.of("Europe/Berlin")).plusDays(1);
        w.setDayOfWeek((short) futureSlot.getDayOfWeek().getValue());
        w.setStartTime(LocalTime.of(8, 0));
        w.setEndTime(LocalTime.of(18, 0));
        w.setCanonicalTimezone("Europe/Berlin");
        return w;
    }

    private CreateBookingRequest makeValidRequest(UUID coachId, Long playerId, CoachAvailabilityWindow window) {
        ZonedDateTime slotStart = ZonedDateTime.now(ZoneId.of("Europe/Berlin"))
            .plusDays(1)
            .withHour(10).withMinute(0).withSecond(0).withNano(0);
        Instant start = slotStart.toInstant();
        Instant end = slotStart.plusHours(1).toInstant();
        return new CreateBookingRequest(coachId, playerId, start, end, "Europe/Berlin", "test notes");
    }

    private Booking makeBooking(Long parentId, Long playerId, UUID coachId, String status) {
        Booking b = new Booking();
        try {
            var f = Booking.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(b, UUID.randomUUID());
        } catch (Exception ignored) {}
        b.setParentId(parentId);
        b.setPlayerId(playerId);
        b.setCoachId(coachId);
        b.setStatus(status);
        b.setRequestedStartTime(Instant.now().plusSeconds(7200));
        b.setRequestedEndTime(Instant.now().plusSeconds(10800));
        b.setCanonicalTimezone("Europe/Berlin");
        return b;
    }

    private User makeUser(String email) {
        User u = new User();
        try {
            var emailField = User.class.getDeclaredField("login");
            emailField.setAccessible(true);
            emailField.set(u, email);
            var emailField2 = User.class.getDeclaredField("email");
            emailField2.setAccessible(true);
            emailField2.set(u, email);
        } catch (Exception ignored) {}
        return u;
    }
}
