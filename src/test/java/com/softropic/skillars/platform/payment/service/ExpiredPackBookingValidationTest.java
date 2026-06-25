package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.contract.CreateBookingRequest;
import com.softropic.skillars.platform.booking.repo.BookingBatchRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.booking.repo.BookingRescheduleRequestRepository;
import com.softropic.skillars.platform.booking.repo.SessionPackPurchasedRepository;
import com.softropic.skillars.platform.booking.service.BookingService;
import com.softropic.skillars.platform.booking.service.BookingStateMachine;
import com.softropic.skillars.platform.booking.service.SessionPackService;
import com.softropic.skillars.platform.marketplace.contract.CoachProfileStatus;
import com.softropic.skillars.platform.marketplace.repo.CoachAvailabilityWindow;
import com.softropic.skillars.platform.marketplace.repo.CoachAvailabilityWindowRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachPricingRepository;
import com.softropic.skillars.platform.payment.contract.PaymentGateway;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchase;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchaseRepository;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Verifies that BookingService rejects booking requests that reference an expired session pack.
 */
@ExtendWith(MockitoExtension.class)
class ExpiredPackBookingValidationTest {

    @Mock BookingRepository bookingRepository;
    @Mock BookingStateMachine bookingStateMachine;
    @Mock SessionPackService sessionPackService;
    @Mock CoachProfileRepository coachProfileRepository;
    @Mock PaymentGateway paymentGateway;
    @Mock CoachAvailabilityWindowRepository coachAvailabilityWindowRepository;
    @Mock PlayerProfileRepository playerProfileRepository;
    @Mock UserRepository userRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock SessionPackPurchasedRepository sessionPackPurchasedRepository;
    @Mock BookingRescheduleRequestRepository rescheduleRequestRepository;
    @Mock BookingBatchRepository batchRepository;
    @Mock SessionPackPurchaseRepository sessionPackPurchaseRepository;
    @Mock CoachPricingRepository coachPricingRepository;

    @InjectMocks BookingService bookingService;

    private static final Long PARENT_ID = 8001L;
    private static final Long PLAYER_ID = 8002L;
    private static final UUID COACH_ID = UUID.randomUUID();
    private static final UUID EXPIRED_PACK_ID = UUID.randomUUID();

    @Test
    void createBookingRequest_expiredPackProvided_throws() {
        // Tomorrow at 10:00 UTC — always in the future
        LocalDate tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1);
        Instant start = tomorrow.atTime(LocalTime.of(10, 0)).toInstant(ZoneOffset.UTC);
        Instant end = tomorrow.atTime(LocalTime.of(11, 0)).toInstant(ZoneOffset.UTC);
        DayOfWeek dow = tomorrow.getDayOfWeek();

        PlayerProfile player = new PlayerProfile();
        player.setParentId(PARENT_ID);
        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));

        CoachProfile coach = new CoachProfile();
        coach.setId(COACH_ID);
        coach.setStatus(CoachProfileStatus.ACTIVE);
        coach.setUserId(9001L);
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
        when(paymentGateway.isCoachPaymentReady(COACH_ID)).thenReturn(true);

        // Availability window covers the full day in UTC
        CoachAvailabilityWindow window = new CoachAvailabilityWindow();
        window.setCanonicalTimezone("UTC");
        window.setDayOfWeek((short) dow.getValue());
        window.setStartTime(LocalTime.of(0, 0));
        window.setEndTime(LocalTime.of(23, 59));
        when(coachAvailabilityWindowRepository.findByCoachId(COACH_ID)).thenReturn(List.of(window));

        // Expired pack: expired yesterday
        SessionPackPurchase expiredPack = new SessionPackPurchase();
        expiredPack.setExpiresAt(Instant.now().minus(1, ChronoUnit.DAYS));
        when(sessionPackPurchaseRepository.findById(EXPIRED_PACK_ID)).thenReturn(Optional.of(expiredPack));

        CreateBookingRequest req = new CreateBookingRequest(
            COACH_ID, PLAYER_ID, start, end, "UTC", null, EXPIRED_PACK_ID);

        assertThatThrownBy(() -> bookingService.createBookingRequest(PARENT_ID, req))
            .isInstanceOf(PaymentGatewayException.class)
            .hasMessageContaining("payment.packExpired");
    }

    @Test
    void createBookingRequest_validPackProvided_noPackValidationExceptionThrown() {
        // P1: fix — set all required pack fields so the three new Group-4 ownership checks pass
        LocalDate tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1);
        Instant start = tomorrow.atTime(LocalTime.of(10, 0)).toInstant(ZoneOffset.UTC);
        Instant end = tomorrow.atTime(LocalTime.of(11, 0)).toInstant(ZoneOffset.UTC);
        DayOfWeek dow = tomorrow.getDayOfWeek();

        PlayerProfile player = new PlayerProfile();
        player.setParentId(PARENT_ID);
        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));

        CoachProfile coach = new CoachProfile();
        coach.setId(COACH_ID);
        coach.setStatus(CoachProfileStatus.ACTIVE);
        coach.setUserId(9001L);
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
        when(paymentGateway.isCoachPaymentReady(COACH_ID)).thenReturn(true);

        CoachAvailabilityWindow window = new CoachAvailabilityWindow();
        window.setCanonicalTimezone("UTC");
        window.setDayOfWeek((short) dow.getValue());
        window.setStartTime(LocalTime.of(0, 0));
        window.setEndTime(LocalTime.of(23, 59));
        when(coachAvailabilityWindowRepository.findByCoachId(COACH_ID)).thenReturn(List.of(window));

        UUID validPackId = UUID.randomUUID();
        SessionPackPurchase validPack = new SessionPackPurchase();
        validPack.setExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
        validPack.setParentId(PARENT_ID);   // must match requesting parent
        validPack.setCoachId(COACH_ID);      // must match requested coach
        validPack.setRemainingSessions(5);   // must be > 0
        when(sessionPackPurchaseRepository.findById(validPackId)).thenReturn(Optional.of(validPack));

        CreateBookingRequest req = new CreateBookingRequest(
            COACH_ID, PLAYER_ID, start, end, "UTC", null, validPackId);

        // A valid non-expired pack with correct ownership must not throw any pack validation exception
        try {
            bookingService.createBookingRequest(PARENT_ID, req);
        } catch (PaymentGatewayException e) {
            throw new AssertionError("Valid pack must not throw PaymentGatewayException: " + e.getMessage(), e);
        } catch (OperationNotAllowedException e) {
            throw new AssertionError("Valid pack must not throw OperationNotAllowedException: " + e.getMessage(), e);
        }
    }

    // ── P7: AC 8 ownership / coach / sessions validation tests ────────────────

    @Test
    void createBookingRequest_packBelongsToDifferentParent_throwsOperationNotAllowed() {
        LocalDate tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1);
        Instant start = tomorrow.atTime(LocalTime.of(10, 0)).toInstant(ZoneOffset.UTC);
        Instant end = tomorrow.atTime(LocalTime.of(11, 0)).toInstant(ZoneOffset.UTC);
        setupCommonMocks(tomorrow.getDayOfWeek());

        UUID packId = UUID.randomUUID();
        SessionPackPurchase pack = new SessionPackPurchase();
        pack.setExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
        pack.setParentId(9999L);   // wrong parent
        pack.setCoachId(COACH_ID);
        pack.setRemainingSessions(5);
        when(sessionPackPurchaseRepository.findById(packId)).thenReturn(Optional.of(pack));

        CreateBookingRequest req = new CreateBookingRequest(
            COACH_ID, PLAYER_ID, start, end, "UTC", null, packId);

        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> bookingService.createBookingRequest(PARENT_ID, req))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasMessageContaining("Pack does not belong to this parent");
    }

    @Test
    void createBookingRequest_packIsForDifferentCoach_throwsPackCoachMismatch() {
        LocalDate tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1);
        Instant start = tomorrow.atTime(LocalTime.of(10, 0)).toInstant(ZoneOffset.UTC);
        Instant end = tomorrow.atTime(LocalTime.of(11, 0)).toInstant(ZoneOffset.UTC);
        setupCommonMocks(tomorrow.getDayOfWeek());

        UUID packId = UUID.randomUUID();
        SessionPackPurchase pack = new SessionPackPurchase();
        pack.setExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
        pack.setParentId(PARENT_ID);
        pack.setCoachId(UUID.randomUUID());  // wrong coach
        pack.setRemainingSessions(5);
        when(sessionPackPurchaseRepository.findById(packId)).thenReturn(Optional.of(pack));

        CreateBookingRequest req = new CreateBookingRequest(
            COACH_ID, PLAYER_ID, start, end, "UTC", null, packId);

        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> bookingService.createBookingRequest(PARENT_ID, req))
            .isInstanceOf(PaymentGatewayException.class)
            .hasMessageContaining("payment.packCoachMismatch");
    }

    @Test
    void createBookingRequest_packExhausted_throwsPackExhausted() {
        LocalDate tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1);
        Instant start = tomorrow.atTime(LocalTime.of(10, 0)).toInstant(ZoneOffset.UTC);
        Instant end = tomorrow.atTime(LocalTime.of(11, 0)).toInstant(ZoneOffset.UTC);
        setupCommonMocks(tomorrow.getDayOfWeek());

        UUID packId = UUID.randomUUID();
        SessionPackPurchase pack = new SessionPackPurchase();
        pack.setExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
        pack.setParentId(PARENT_ID);
        pack.setCoachId(COACH_ID);
        pack.setRemainingSessions(0);   // exhausted
        when(sessionPackPurchaseRepository.findById(packId)).thenReturn(Optional.of(pack));

        CreateBookingRequest req = new CreateBookingRequest(
            COACH_ID, PLAYER_ID, start, end, "UTC", null, packId);

        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> bookingService.createBookingRequest(PARENT_ID, req))
            .isInstanceOf(PaymentGatewayException.class)
            .hasMessageContaining("payment.packExhausted");
    }

    private void setupCommonMocks(DayOfWeek dow) {
        PlayerProfile player = new PlayerProfile();
        player.setParentId(PARENT_ID);
        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));

        CoachProfile coach = new CoachProfile();
        coach.setId(COACH_ID);
        coach.setStatus(CoachProfileStatus.ACTIVE);
        coach.setUserId(9001L);
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
        when(paymentGateway.isCoachPaymentReady(COACH_ID)).thenReturn(true);

        CoachAvailabilityWindow window = new CoachAvailabilityWindow();
        window.setCanonicalTimezone("UTC");
        window.setDayOfWeek((short) dow.getValue());
        window.setStartTime(LocalTime.of(0, 0));
        window.setEndTime(LocalTime.of(23, 59));
        when(coachAvailabilityWindowRepository.findByCoachId(COACH_ID)).thenReturn(List.of(window));
    }
}
