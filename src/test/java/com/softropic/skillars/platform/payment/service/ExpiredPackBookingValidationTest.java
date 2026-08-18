package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.contract.CreateBookingRequest;
import com.softropic.skillars.platform.booking.repo.BookingBatchRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.booking.repo.BookingRescheduleRequestRepository;
import com.softropic.skillars.platform.booking.service.BookingService;
import com.softropic.skillars.platform.booking.service.BookingStateMachine;
import com.softropic.skillars.platform.booking.service.SessionDurationResolver;
import com.softropic.skillars.platform.marketplace.contract.CoachProfileStatus;
import com.softropic.skillars.platform.marketplace.repo.CoachAvailabilityWindow;
import com.softropic.skillars.platform.marketplace.repo.CoachAvailabilityWindowRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachPricingRepository;
import com.softropic.skillars.platform.payment.contract.PaymentGateway;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.payment.repo.BookingPaymentRepository;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchase;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchaseRepository;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import jakarta.persistence.EntityManager;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.DayOfWeek;
import java.time.Duration;
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
    @Mock CoachProfileRepository coachProfileRepository;
    @Mock PaymentGateway paymentGateway;
    @Mock CoachAvailabilityWindowRepository coachAvailabilityWindowRepository;
    @Mock PlayerProfileRepository playerProfileRepository;
    @Mock UserRepository userRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock BookingRescheduleRequestRepository rescheduleRequestRepository;
    @Mock BookingBatchRepository batchRepository;
    @Mock SessionPackPurchaseRepository sessionPackPurchaseRepository;
    @Mock CoachPricingRepository coachPricingRepository;
    @Mock SessionDurationResolver sessionDurationResolver;
    // UAT.3 AC2 added this dependency to BookingService. This class only exercises
    // createBookingRequest, which never reaches it — but @InjectMocks would otherwise inject null
    // silently, which is exactly the twelfth-file class of miss UAT.2 recorded.
    @Mock BookingPaymentRepository bookingPaymentRepository;
    // Deferred-12 AC3: createBookingRequest re-reads the locked coach row via EntityManager.refresh
    // before the overlap check; a mock keeps that a no-op here. The real re-read is proven against a
    // live database in BookingServiceConcurrencyIT.
    @Mock EntityManager entityManager;

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
        setupCommonMocks(tomorrow.getDayOfWeek());

        // Expired pack: expired yesterday. parentId must be set: the skillars-deferred-30 code review
        // moved the pack-ownership check AHEAD of the expiry check (an expired pack belonging to
        // someone else used to answer "packExpired", which distinguished it from an unowned unexpired
        // one and leaked another parent's pack state). Without an owner this fixture now fails on
        // ownership and never reaches the branch under test.
        SessionPackPurchase expiredPack = new SessionPackPurchase();
        expiredPack.setParentId(PARENT_ID);
        expiredPack.setExpiresAt(Instant.now().minus(1, ChronoUnit.DAYS));
        when(sessionPackPurchaseRepository.findById(EXPIRED_PACK_ID)).thenReturn(Optional.of(expiredPack));

        CreateBookingRequest req = new CreateBookingRequest(
            COACH_ID, PLAYER_ID, start, end, null, EXPIRED_PACK_ID);

        assertThatThrownBy(() -> bookingService.createBookingRequest(PARENT_ID, req))
            .isInstanceOf(PaymentGatewayException.class)
            .hasMessageContaining("payment.packExpired");
    }

    @Test
    void createBookingRequest_expiredPackOwnedByAnotherParent_reportsMissingRightsNotExpiry() {
        // skillars-deferred-30 code review, Decision 2. Ownership must be decided BEFORE any state
        // check on the pack, so that a pack id the caller does not own yields exactly one answer
        // regardless of that pack's state. Before the reorder this returned payment.packExpired,
        // which — combined with MISSING_RIGHTS for an unowned unexpired pack and 404 for an unknown
        // id — let a caller probe other parents' pack state. Reverting the reorder fails this test.
        LocalDate tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1);
        Instant start = tomorrow.atTime(LocalTime.of(10, 0)).toInstant(ZoneOffset.UTC);
        Instant end = tomorrow.atTime(LocalTime.of(11, 0)).toInstant(ZoneOffset.UTC);
        setupCommonMocks(tomorrow.getDayOfWeek());

        // Expired AND owned by somebody else: the two rejections the old ordering let a caller tell
        // apart. Coach and remaining-session fields are deliberately left unset — neither check may
        // be reached, so a change that reordered them forward would fail here too.
        SessionPackPurchase foreignExpiredPack = new SessionPackPurchase();
        foreignExpiredPack.setParentId(PARENT_ID + 1);
        foreignExpiredPack.setExpiresAt(Instant.now().minus(1, ChronoUnit.DAYS));
        when(sessionPackPurchaseRepository.findById(EXPIRED_PACK_ID)).thenReturn(Optional.of(foreignExpiredPack));

        CreateBookingRequest req = new CreateBookingRequest(
            COACH_ID, PLAYER_ID, start, end, null, EXPIRED_PACK_ID);

        assertThatThrownBy(() -> bookingService.createBookingRequest(PARENT_ID, req))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasMessageContaining("Pack does not belong to this parent");
    }

    @Test
    void createBookingRequest_validPackProvided_noPackValidationExceptionThrown() {
        // P1: fix — set all required pack fields so the three new Group-4 ownership checks pass
        LocalDate tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1);
        Instant start = tomorrow.atTime(LocalTime.of(10, 0)).toInstant(ZoneOffset.UTC);
        Instant end = tomorrow.atTime(LocalTime.of(11, 0)).toInstant(ZoneOffset.UTC);
        setupCommonMocks(tomorrow.getDayOfWeek());

        UUID validPackId = UUID.randomUUID();
        SessionPackPurchase validPack = new SessionPackPurchase();
        validPack.setExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
        validPack.setParentId(PARENT_ID);   // must match requesting parent
        validPack.setCoachId(COACH_ID);      // must match requested coach
        validPack.setRemainingSessions(5);   // must be > 0
        when(sessionPackPurchaseRepository.findById(validPackId)).thenReturn(Optional.of(validPack));

        CreateBookingRequest req = new CreateBookingRequest(
            COACH_ID, PLAYER_ID, start, end, null, validPackId);

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
            COACH_ID, PLAYER_ID, start, end, null, packId);

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
            COACH_ID, PLAYER_ID, start, end, null, packId);

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
            COACH_ID, PLAYER_ID, start, end, null, packId);

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
        coach.setCanonicalTimezone("UTC");
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
        when(paymentGateway.isCoachPaymentReady(COACH_ID)).thenReturn(true);
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));

        CoachAvailabilityWindow window = new CoachAvailabilityWindow();
        window.setCanonicalTimezone("UTC");
        window.setDayOfWeek((short) dow.getValue());
        window.setStartTime(LocalTime.of(0, 0));
        window.setEndTime(LocalTime.of(23, 59));
        when(coachAvailabilityWindowRepository.findByCoachId(COACH_ID)).thenReturn(List.of(window));

        // UAT.2 AC3: every fixture in this class books exactly 10:00-11:00, which is the platform
        // default session length. The check sits before the window lookup, so it must be stubbed
        // for any test that reaches the pack validation this class is about.
        when(sessionDurationResolver.resolve(COACH_ID)).thenReturn(Duration.ofHours(1));
    }
}
