package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.platform.config.service.ConfigBounds;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.softropic.skillars.platform.admin.contract.AdminDisputeDetailDto;
import com.softropic.skillars.platform.admin.contract.DisputeError;
import com.softropic.skillars.platform.admin.repo.AdminActionLogRepository;
import com.softropic.skillars.platform.admin.repo.AdminAlertRepository;
import com.softropic.skillars.platform.admin.repo.Dispute;
import com.softropic.skillars.platform.admin.repo.DisputeRepository;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.contract.CoachProfileStatus;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.repo.BookingPayment;
import com.softropic.skillars.platform.payment.repo.BookingPaymentRepository;
import com.softropic.skillars.platform.payment.repo.CoachCancellationHistoryRepository;
import com.softropic.skillars.platform.payment.service.DisputeCreditService;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DisputeServiceTest {

    @Mock private DisputeRepository disputeRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private BookingPaymentRepository bookingPaymentRepository;
    @Mock private CoachProfileRepository coachProfileRepository;
    @Mock private CoachCancellationHistoryRepository coachCancellationHistoryRepository;
    @Mock private AdminAlertRepository adminAlertRepository;
    @Mock private AdminActionLogRepository adminActionLogRepository;
    @Mock private ConfigService configService;
    @Mock private DisputeCreditService disputeCreditService;
    @Mock private com.softropic.skillars.platform.payment.repo.CoachPayoutRepository coachPayoutRepository;
    @Mock private com.softropic.skillars.platform.payment.service.CoachPayoutOutboxSupport coachPayoutOutboxSupport;
    @Mock private com.softropic.skillars.platform.payment.repo.CoachPayoutFreezeRepository coachPayoutFreezeRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private PlayerProfileRepository playerProfileRepository;
    @Mock private AdminNotificationRecipientResolver recipientResolver;
    @Mock private com.softropic.skillars.platform.payment.service.RefundClawbackService refundClawbackService;

    private DisputeService service;

    private final UUID disputeId = UUID.randomUUID();
    private final UUID bookingId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DisputeService(
            disputeRepository, bookingRepository, bookingPaymentRepository, coachProfileRepository,
            coachCancellationHistoryRepository, adminAlertRepository, adminActionLogRepository,
            configService, coachPayoutRepository,
            new com.softropic.skillars.platform.payment.service.CoachPayoutAdjustmentService(
                coachPayoutRepository, coachPayoutFreezeRepository, coachPayoutOutboxSupport,
                new com.softropic.skillars.platform.payment.service.CoachPayoutDebtService(org.mockito.Mockito.mock(com.softropic.skillars.platform.payment.repo.CoachPayoutDebtRepository.class), new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), eventPublisher)),
            eventPublisher,
            playerProfileRepository, recipientResolver, refundClawbackService, disputeCreditService
        );
        // fix/payment-wallet-dispute-integrity: by default nothing was refunded yet — the whole price remains.
        org.mockito.Mockito.lenient().when(disputeCreditService.remainingRefundable(any(), any()))
            .thenAnswer(inv -> inv.getArgument(1));
        // fix/audit3-completion-dispute-integrity: the per-raiser cap at its default (2); no earlier disputes.
        org.mockito.Mockito.lenient().when(configService.getBoundedLong(ConfigBounds.DISPUTES_MAX_PER_BOOKING_PER_RAISER))
            .thenReturn(2L);
    }

    private Dispute buildDispute() {
        Dispute dispute = new Dispute();
        dispute.setId(disputeId);
        dispute.setBookingId(bookingId);
        dispute.setRaisedBy(1L);
        dispute.setRaisedByRole("PARENT");
        dispute.setReason("SESSION_QUALITY");
        dispute.setDetails("details");
        dispute.setStatus("OPEN");
        return dispute;
    }

    private Booking buildBooking() {
        Booking booking = new Booking();
        booking.setId(bookingId);
        booking.setParentId(1L);
        booking.setPlayerId(2L);
        booking.setCoachId(UUID.randomUUID());
        booking.setStatus("COMPLETED");
        return booking;
    }

    private BookingPayment buildPayment(String status) {
        BookingPayment payment = new BookingPayment();
        payment.setBookingId(bookingId);
        payment.setStatus(status);
        payment.setCreditDebited(new BigDecimal("10.00"));
        payment.setStripeCharged(new BigDecimal("20.00"));
        return payment;
    }

    // ── raiseDispute (Deferred-63 AC5: coach ownerEligible widening) ──

    @Test
    void raiseDispute_coachOwnsBooking_isEligible() {
        Booking booking = buildBooking();
        booking.setUpdatedAt(Instant.now());
        Long coachUserId = 500L;
        CoachProfile coachProfile = new CoachProfile();
        coachProfile.setId(booking.getCoachId());
        coachProfile.setUserId(coachUserId);

        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(coachProfileRepository.findById(booking.getCoachId())).thenReturn(Optional.of(coachProfile));
        when(configService.getBoundedLong(ConfigBounds.DISPUTES_SUBMISSION_WINDOW_DAYS)).thenReturn(14L);
        when(disputeRepository.existsOpenByBookingIdAndRaisedByRoleIn(eq(bookingId), any())).thenReturn(false);
        when(disputeRepository.save(any(Dispute.class))).thenAnswer(inv -> {
            Dispute d = inv.getArgument(0);
            d.setId(UUID.randomUUID());
            return d;
        });

        UUID disputeId = service.raiseDispute(bookingId, "OTHER", "details", coachUserId, "COACH");

        assertThat(disputeId).isNotNull();
        verify(disputeRepository).save(any(Dispute.class));
    }

    @Test
    void raiseDispute_callerIsNotTheOwningCoach_throwsNotEligible() {
        Booking booking = buildBooking();
        booking.setUpdatedAt(Instant.now());
        Long someOtherCoachUserId = 999L;
        CoachProfile coachProfile = new CoachProfile();
        coachProfile.setId(booking.getCoachId());
        coachProfile.setUserId(500L);

        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(coachProfileRepository.findById(booking.getCoachId())).thenReturn(Optional.of(coachProfile));

        assertThatThrownBy(() -> service.raiseDispute(bookingId, "OTHER", "details", someOtherCoachUserId, "COACH"))
            .isInstanceOf(OperationNotAllowedException.class);

        verify(disputeRepository, never()).save(any(Dispute.class));
    }

    @Test
    void raiseDispute_coachOwnsBookingButSuspended_throwsNotEligible() {
        // Code review (2026-08-25): mirrors BookingDuplicationServiceTest's
        // duplicateNextWeek_suspendedCoach_throwsCoachUnavailable — a suspended coach must not be able
        // to raise a dispute either.
        Booking booking = buildBooking();
        booking.setUpdatedAt(Instant.now());
        Long coachUserId = 500L;
        CoachProfile coachProfile = new CoachProfile();
        coachProfile.setId(booking.getCoachId());
        coachProfile.setUserId(coachUserId);
        coachProfile.setStatus(CoachProfileStatus.SUSPENDED);

        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(coachProfileRepository.findById(booking.getCoachId())).thenReturn(Optional.of(coachProfile));

        assertThatThrownBy(() -> service.raiseDispute(bookingId, "OTHER", "details", coachUserId, "COACH"))
            .isInstanceOf(OperationNotAllowedException.class);

        verify(disputeRepository, never()).save(any(Dispute.class));
    }

    // ── raiseDispute (skillars-deferred-148 Finding 2: player-ownership ID-space fix) ──

    @Test
    void raiseDispute_selfRegisteredPlayerOwnsBooking_isEligible() {
        Booking booking = buildBooking();
        booking.setUpdatedAt(Instant.now());
        Long playerUserId = 777L;
        PlayerProfile playerProfile = new PlayerProfile();
        playerProfile.setId(booking.getPlayerId());
        playerProfile.setUserId(playerUserId);
        playerProfile.setParentId(null);

        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(playerProfileRepository.findById(booking.getPlayerId())).thenReturn(Optional.of(playerProfile));
        when(configService.getBoundedLong(ConfigBounds.DISPUTES_SUBMISSION_WINDOW_DAYS)).thenReturn(14L);
        when(disputeRepository.existsOpenByBookingIdAndRaisedByRoleIn(eq(bookingId), any())).thenReturn(false);
        when(disputeRepository.save(any(Dispute.class))).thenAnswer(inv -> {
            Dispute d = inv.getArgument(0);
            d.setId(UUID.randomUUID());
            return d;
        });

        UUID disputeId = service.raiseDispute(bookingId, "OTHER", "details", playerUserId, "PLAYER");

        assertThat(disputeId).isNotNull();
        verify(disputeRepository).save(any(Dispute.class));
    }

    @Test
    void raiseDispute_callerIsADifferentSelfRegisteredPlayer_throwsNotEligible() {
        // Proves the fix resolves ownership against the booking's ACTUAL player, not any player —
        // a different self-registered player's id (not the coach, not the resolved player) must
        // still be rejected.
        Booking booking = buildBooking();
        PlayerProfile playerProfile = new PlayerProfile();
        playerProfile.setId(booking.getPlayerId());
        playerProfile.setUserId(777L);
        playerProfile.setParentId(null);
        Long someOtherPlayerUserId = 888L;

        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(playerProfileRepository.findById(booking.getPlayerId())).thenReturn(Optional.of(playerProfile));

        assertThatThrownBy(() -> service.raiseDispute(bookingId, "OTHER", "details", someOtherPlayerUserId, "PLAYER"))
            .isInstanceOf(OperationNotAllowedException.class)
            .extracting(t -> ((OperationNotAllowedException) t).getErrorCode())
            .isEqualTo(DisputeError.NOT_ELIGIBLE);

        verify(disputeRepository, never()).save(any(Dispute.class));
    }

    /**
     * Code review 2026-10-07: Design B's {@code .orElse(false)} correctly lets a booking whose
     * {@code player_id} has no backing {@code player_profiles} row (no FK enforces one) fall
     * through to the coach-eligibility disjunct, but nothing pinned that fall-through directly.
     * Here the coach IS the rightful owner — proving the orphaned lookup doesn't itself block
     * eligibility, it just contributes nothing, leaving the coach disjunct to decide.
     */
    @Test
    void raiseDispute_bookingPlayerIdHasNoPlayerProfile_coachDisjunctStillDecidesEligibility() {
        Booking booking = buildBooking();
        booking.setUpdatedAt(Instant.now());
        Long coachUserId = 500L;
        CoachProfile coachProfile = new CoachProfile();
        coachProfile.setId(booking.getCoachId());
        coachProfile.setUserId(coachUserId);

        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(playerProfileRepository.findById(booking.getPlayerId())).thenReturn(Optional.empty());
        when(coachProfileRepository.findById(booking.getCoachId())).thenReturn(Optional.of(coachProfile));
        when(configService.getBoundedLong(ConfigBounds.DISPUTES_SUBMISSION_WINDOW_DAYS)).thenReturn(14L);
        when(disputeRepository.existsOpenByBookingIdAndRaisedByRoleIn(eq(bookingId), any())).thenReturn(false);
        when(disputeRepository.save(any(Dispute.class))).thenAnswer(inv -> {
            Dispute d = inv.getArgument(0);
            d.setId(UUID.randomUUID());
            return d;
        });

        UUID disputeId = service.raiseDispute(bookingId, "OTHER", "details", coachUserId, "COACH");

        assertThat(disputeId).isNotNull();
        verify(disputeRepository).save(any(Dispute.class));
    }

    // ── getAdminDisputeDetail ────────────────────────────────────

    @Test
    void adminDisputeDetail_capturedPayment_includesAmounts() {
        Dispute dispute = buildDispute();
        Booking booking = buildBooking();
        when(disputeRepository.findById(disputeId)).thenReturn(Optional.of(dispute));
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(coachProfileRepository.findById(booking.getCoachId())).thenReturn(Optional.empty());
        when(bookingPaymentRepository.findById(bookingId)).thenReturn(Optional.of(buildPayment("CAPTURED")));
        when(coachCancellationHistoryRepository.findByBookingId(bookingId)).thenReturn(List.of());

        AdminDisputeDetailDto dto = service.getAdminDisputeDetail(disputeId);

        assertThat(dto.creditDebited()).isEqualByComparingTo("10.00");
        assertThat(dto.stripeCharged()).isEqualByComparingTo("20.00");
        assertThat(dto.sessionPrice()).isEqualByComparingTo("30.00");
    }

    @Test
    void adminDisputeDetail_nonCapturedPayment_treatedAsAbsent() {
        Dispute dispute = buildDispute();
        Booking booking = buildBooking();
        when(disputeRepository.findById(disputeId)).thenReturn(Optional.of(dispute));
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(coachProfileRepository.findById(booking.getCoachId())).thenReturn(Optional.empty());
        when(bookingPaymentRepository.findById(bookingId)).thenReturn(Optional.of(buildPayment("CAPTURE_PENDING")));
        when(coachCancellationHistoryRepository.findByBookingId(bookingId)).thenReturn(List.of());

        AdminDisputeDetailDto dto = service.getAdminDisputeDetail(disputeId);

        assertThat(dto.creditDebited()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(dto.stripeCharged()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(dto.sessionPrice()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ── resolveDispute ───────────────────────────────────────────

    @Test
    void resolveDispute_capturedPayment_fullCreditRefundsSessionPrice() {
        Dispute dispute = buildDispute();
        Booking booking = buildBooking();
        when(disputeRepository.findById(disputeId)).thenReturn(Optional.of(dispute));
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(bookingPaymentRepository.findById(bookingId)).thenReturn(Optional.of(buildPayment("CAPTURED")));
        when(adminAlertRepository.findFirstByReferenceIdAndTypeAndStatus(any(), any(), any()))
            .thenReturn(Optional.empty());

        service.resolveDispute(disputeId, "FULL_CREDIT", null, "note", 99L);

        verify(disputeCreditService).credit(
            eq(booking.getParentId()), eq(booking.getId()), eq(disputeId), eq(new BigDecimal("30.00")), any());
    }

    /**
     * feat/coach-payouts-pack-and-credit: a pack session has no per-booking charge, so its price is the
     * pack share its coach payout was computed from — the parent is refunded that, and the payout
     * (here frozen by a safety hold) is cancelled, never released later.
     */
    @Test
    void resolveDispute_packSession_fullCreditRefundsPackShare_andCancelsFrozenPayout() {
        Dispute dispute = buildDispute();
        Booking booking = buildBooking();
        booking.setSessionPackPurchaseId(UUID.randomUUID());
        BookingPayment packPayment = buildPayment("CAPTURED");
        packPayment.setCreditDebited(BigDecimal.ZERO);
        packPayment.setStripeCharged(BigDecimal.ZERO);
        com.softropic.skillars.platform.payment.repo.CoachPayout payout =
            new com.softropic.skillars.platform.payment.repo.CoachPayout();
        payout.setBookingId(bookingId);
        payout.setGrossAmount(new BigDecimal("33.33"));
        payout.setNetAmount(new BigDecimal("30.00"));
        payout.setStatus(com.softropic.skillars.platform.payment.contract.CoachPayoutStatus.FROZEN);
        when(disputeRepository.findById(disputeId)).thenReturn(Optional.of(dispute));
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(bookingPaymentRepository.findById(bookingId)).thenReturn(Optional.of(packPayment));
        when(coachPayoutRepository.findGrossAmountByBookingId(bookingId)).thenReturn(Optional.of(new BigDecimal("33.33")));
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(payout));
        when(adminAlertRepository.findFirstByReferenceIdAndTypeAndStatus(any(), any(), any()))
            .thenReturn(Optional.empty());

        service.resolveDispute(disputeId, "FULL_CREDIT", null, "note", 99L);

        verify(disputeCreditService).credit(
            eq(booking.getParentId()), eq(booking.getId()), eq(disputeId), eq(new BigDecimal("33.33")), any());
        assertThat(payout.getStatus())
            .isEqualTo(com.softropic.skillars.platform.payment.contract.CoachPayoutStatus.CANCELLED);
        verify(coachPayoutOutboxSupport, never()).enqueueReversal(any(), any());
    }

    // ── fix/payment-payouts-disputes-webhooks items 2 + 3 ─────────────────

    private com.softropic.skillars.platform.payment.repo.CoachPayout payout(String status) {
        com.softropic.skillars.platform.payment.repo.CoachPayout p = new com.softropic.skillars.platform.payment.repo.CoachPayout();
        p.setBookingId(bookingId);
        p.setCoachId(UUID.randomUUID());
        p.setGrossAmount(new BigDecimal("30.00"));
        p.setCommissionAmount(new BigDecimal("3.00"));
        p.setNetAmount(new BigDecimal("27.00"));
        p.setReleaseAfter(Instant.now().minusSeconds(60));
        p.setStatus(status);
        return p;
    }

    @Test
    void raiseDispute_freezesTheNotYetReleasedPayout() {
        Booking booking = buildBooking();
        booking.setUpdatedAt(Instant.now());
        com.softropic.skillars.platform.payment.repo.CoachPayout payout =
            payout(com.softropic.skillars.platform.payment.contract.CoachPayoutStatus.PENDING_RELEASE);
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(configService.getBoundedLong(ConfigBounds.DISPUTES_SUBMISSION_WINDOW_DAYS)).thenReturn(14L);
        when(disputeRepository.existsOpenByBookingIdAndRaisedByRoleIn(eq(bookingId), any())).thenReturn(false);
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(payout));

        service.raiseDispute(bookingId, "OTHER", "details", 1L, "PARENT");

        assertThat(payout.getStatus()).isEqualTo(com.softropic.skillars.platform.payment.contract.CoachPayoutStatus.FROZEN);
        assertThat(payout.isDisputeFrozen()).isTrue();
    }

    /** fix/booking-completion-integrity: the parent can dispute a started session (it cannot be cancelled). */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"IN_PROGRESS", "PAUSED"})
    void raiseDispute_parentOnALiveSession_isEligible(String status) {
        Booking booking = buildBooking();
        booking.setStatus(status);
        booking.setUpdatedAt(Instant.now());
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(configService.getBoundedLong(ConfigBounds.DISPUTES_SUBMISSION_WINDOW_DAYS)).thenReturn(14L);
        when(disputeRepository.existsOpenByBookingIdAndRaisedByRoleIn(eq(bookingId), any())).thenReturn(false);

        service.raiseDispute(bookingId, "COACH_NO_SHOW", "started then paused, never coached", 1L, "PARENT");

        verify(disputeRepository).save(any(Dispute.class));
        assertThat(booking.getStatus()).as("the booking keeps its status").isEqualTo(status);
    }

    /** fix/booking-completion-integrity: ... but not the coach, who controls the live session. */
    @Test
    void raiseDispute_coachOnALiveSession_throwsNotEligible() {
        Booking booking = buildBooking();
        booking.setStatus("PAUSED");
        booking.setUpdatedAt(Instant.now());
        Long coachUserId = 500L;
        CoachProfile coachProfile = new CoachProfile();
        coachProfile.setId(booking.getCoachId());
        coachProfile.setUserId(coachUserId);
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(coachProfileRepository.findById(booking.getCoachId())).thenReturn(Optional.of(coachProfile));

        assertThatThrownBy(() -> service.raiseDispute(bookingId, "OTHER", "details", coachUserId, "COACH"))
            .isInstanceOf(OperationNotAllowedException.class)
            .extracting(e -> ((OperationNotAllowedException) e).getErrorCode())
            .isEqualTo(DisputeError.NOT_ELIGIBLE);
        verify(disputeRepository, never()).save(any(Dispute.class));
    }

    /**
     * fix/booking-completion-integrity: a dispute on a session that has not completed yet is not resolvable —
     * there is no payout to reconcile a credit against, and the coach would be paid in full on completion.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"IN_PROGRESS", "PAUSED", "COMPLETED_PENDING_CONFIRMATION"})
    void resolveDispute_bookingNotSettledYet_isRefusedBeforeAnyWrite(String status) {
        Dispute dispute = buildDispute();
        Booking booking = buildBooking();
        booking.setStatus(status);
        when(disputeRepository.findById(disputeId)).thenReturn(Optional.of(dispute));
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.resolveDispute(disputeId, "FULL_CREDIT", null, "note", 99L))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasMessageContaining(DisputeError.BOOKING_NOT_SETTLED.getErrorCode());
        verifyNoInteractions(disputeCreditService);
        verify(disputeRepository, never()).save(any());
        assertThat(dispute.getStatus()).isEqualTo("OPEN");
    }

    @Test
    void raiseDispute_releasedPayout_isLeftAsIs() {
        Booking booking = buildBooking();
        booking.setUpdatedAt(Instant.now());
        com.softropic.skillars.platform.payment.repo.CoachPayout payout =
            payout(com.softropic.skillars.platform.payment.contract.CoachPayoutStatus.RELEASED);
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(configService.getBoundedLong(ConfigBounds.DISPUTES_SUBMISSION_WINDOW_DAYS)).thenReturn(14L);
        when(disputeRepository.existsOpenByBookingIdAndRaisedByRoleIn(eq(bookingId), any())).thenReturn(false);
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(payout));

        service.raiseDispute(bookingId, "OTHER", "details", 1L, "PARENT");

        assertThat(payout.getStatus()).isEqualTo(com.softropic.skillars.platform.payment.contract.CoachPayoutStatus.RELEASED);
        assertThat(payout.isDisputeFrozen()).isFalse();
    }

    /** Item 3: a partial credit on a not-yet-released payout cuts it pro rata and lets it release. */
    @Test
    void resolveDispute_partialCredit_onFrozenPayout_cutsProRata_andReleases() {
        Dispute dispute = buildDispute();
        Booking booking = buildBooking();
        com.softropic.skillars.platform.payment.repo.CoachPayout payout =
            payout(com.softropic.skillars.platform.payment.contract.CoachPayoutStatus.FROZEN);
        payout.setDisputeFrozen(true);
        when(disputeRepository.findById(disputeId)).thenReturn(Optional.of(dispute));
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(bookingPaymentRepository.findById(bookingId)).thenReturn(Optional.of(buildPayment("CAPTURED")));
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(payout));
        when(adminAlertRepository.findFirstByReferenceIdAndTypeAndStatus(any(), any(), any()))
            .thenReturn(Optional.empty());

        // 10.00 of 30.00 refunded -> the coach bears a third of their 27.00 net.
        service.resolveDispute(disputeId, "PARTIAL_CREDIT", new BigDecimal("10.00"), "note", 99L);

        assertThat(payout.getStatus())
            .isEqualTo(com.softropic.skillars.platform.payment.contract.CoachPayoutStatus.PENDING_RELEASE);
        assertThat(payout.isDisputeFrozen()).isFalse();
        assertThat(payout.getNetAmount()).isEqualByComparingTo("18.00");
        assertThat(payout.getGrossAmount()).isEqualByComparingTo("20.00");
        assertThat(payout.getCommissionAmount()).isEqualByComparingTo("2.00");
        verify(coachPayoutOutboxSupport).enqueueTransfer(eq(bookingId), any());
        verify(coachPayoutOutboxSupport, never()).enqueueReversal(any(), any());
    }

    /** Item 3: the same partial credit after release reverses the same share — same outcome either way. */
    @Test
    void resolveDispute_partialCredit_onReleasedPayout_reversesTheSameShare() {
        Dispute dispute = buildDispute();
        Booking booking = buildBooking();
        com.softropic.skillars.platform.payment.repo.CoachPayout payout =
            payout(com.softropic.skillars.platform.payment.contract.CoachPayoutStatus.RELEASED);
        when(disputeRepository.findById(disputeId)).thenReturn(Optional.of(dispute));
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(bookingPaymentRepository.findById(bookingId)).thenReturn(Optional.of(buildPayment("CAPTURED")));
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(payout));
        when(adminAlertRepository.findFirstByReferenceIdAndTypeAndStatus(any(), any(), any()))
            .thenReturn(Optional.empty());

        service.resolveDispute(disputeId, "PARTIAL_CREDIT", new BigDecimal("10.00"), "note", 99L);

        verify(coachPayoutOutboxSupport).enqueueReversal(bookingId, new BigDecimal("9.00"));
        assertThat(payout.getStatus()).isEqualTo(com.softropic.skillars.platform.payment.contract.CoachPayoutStatus.RELEASED);
    }

    /** Item 2: a dispute freeze lifted while a safety hold still stands keeps the payout FROZEN. */
    @Test
    void dismissDispute_liftsDisputeFreeze_butSafetyHoldKeepsItFrozen() {
        Dispute dispute = buildDispute();
        com.softropic.skillars.platform.payment.repo.CoachPayout payout =
            payout(com.softropic.skillars.platform.payment.contract.CoachPayoutStatus.FROZEN);
        payout.setDisputeFrozen(true);
        when(disputeRepository.findById(disputeId)).thenReturn(Optional.of(dispute));
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(payout));
        when(coachPayoutFreezeRepository.isFrozenForShare(payout.getCoachId())).thenReturn(true);
        when(adminAlertRepository.findFirstByReferenceIdAndTypeAndStatus(any(), any(), any()))
            .thenReturn(Optional.empty());

        service.dismissDispute(disputeId, "no merit", 99L);

        assertThat(payout.isDisputeFrozen()).isFalse();
        assertThat(payout.getStatus()).isEqualTo(com.softropic.skillars.platform.payment.contract.CoachPayoutStatus.FROZEN);
        assertThat(payout.getLastError()).isEqualTo("SAFETY_HOLD");
        verify(coachPayoutOutboxSupport, never()).enqueueTransfer(any(), any());
    }

    @Test
    void resolveDispute_nonCapturedPayment_fullCreditIssuesNoRefund() {
        Dispute dispute = buildDispute();
        Booking booking = buildBooking();
        when(disputeRepository.findById(disputeId)).thenReturn(Optional.of(dispute));
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(bookingPaymentRepository.findById(bookingId)).thenReturn(Optional.of(buildPayment("CAPTURE_PENDING")));
        when(adminAlertRepository.findFirstByReferenceIdAndTypeAndStatus(any(), any(), any()))
            .thenReturn(Optional.empty());

        service.resolveDispute(disputeId, "FULL_CREDIT", null, "note", 99L);

        verify(disputeCreditService, never()).credit(any(), any(), any(), any(), any());
    }

    @Test
    void resolveDispute_frozenPayment_logsDistinguishingWarnRegardlessOfResolutionBranch() {
        Dispute dispute = buildDispute();
        Booking booking = buildBooking();
        when(disputeRepository.findById(disputeId)).thenReturn(Optional.of(dispute));
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(bookingPaymentRepository.findById(bookingId)).thenReturn(Optional.of(buildPayment("FROZEN")));
        when(adminAlertRepository.findFirstByReferenceIdAndTypeAndStatus(any(), any(), any()))
            .thenReturn(Optional.empty());

        Logger serviceLogger = (Logger) LoggerFactory.getLogger(DisputeService.class);
        ListAppender<ILoggingEvent> logCapture = new ListAppender<>();
        logCapture.start();
        serviceLogger.addAppender(logCapture);
        try {
            // NO_ACTION never touches creditWalletService — proves the WARN fires unconditionally,
            // not just inside the FULL_CREDIT branch.
            service.resolveDispute(disputeId, "NO_ACTION", null, "note", 99L);
        } finally {
            serviceLogger.detachAppender(logCapture);
        }

        assertThat(logCapture.list)
            .as("must warn distinctly, naming the actual non-CAPTURED status found")
            .anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                    .contains("FROZEN")
                    .contains(disputeId.toString())
                    .contains(bookingId.toString());
            });
        verify(disputeCreditService, never()).credit(any(), any(), any(), any(), any());
    }

    // ── B6: dismissal audit type and outcome notifications ─────────────────

    @Test
    void dismissDispute_auditsAsDisputeDismiss_andPublishesOutcomeForTheRaiser() {
        Dispute dispute = buildDispute();
        Booking booking = buildBooking();
        when(disputeRepository.findById(disputeId)).thenReturn(Optional.of(dispute));
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(adminAlertRepository.findFirstByReferenceIdAndTypeAndStatus(any(), any(), any()))
            .thenReturn(Optional.empty());
        com.softropic.skillars.platform.admin.contract.AdminNotificationRecipient raiser =
            new com.softropic.skillars.platform.admin.contract.AdminNotificationRecipient("p@x.test", "en");
        when(recipientResolver.forUser(1L)).thenReturn(Optional.of(raiser));

        service.dismissDispute(disputeId, "not substantiated", 99L);

        var logged = org.mockito.ArgumentCaptor.forClass(com.softropic.skillars.platform.admin.repo.AdminActionLog.class);
        verify(adminActionLogRepository).save(logged.capture());
        assertThat(logged.getValue().getActionType())
            .isEqualTo(com.softropic.skillars.platform.admin.contract.AdminActionType.DISPUTE_DISMISS);

        var published = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(published.capture());
        assertThat(published.getValue()).isInstanceOfSatisfying(
            com.softropic.skillars.platform.admin.contract.DisputeDismissedEvent.class, e -> {
                assertThat(e.disputeId()).isEqualTo(disputeId);
                assertThat(e.reason()).isEqualTo("not substantiated");
                assertThat(e.recipient()).isEqualTo(raiser);
            });
    }

    @Test
    void resolveDispute_publishesOutcomeWithCreditAndRaiserAddress_auditsAsDisputeResolve() {
        Dispute dispute = buildDispute();
        Booking booking = buildBooking();
        when(disputeRepository.findById(disputeId)).thenReturn(Optional.of(dispute));
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(bookingPaymentRepository.findById(bookingId)).thenReturn(Optional.of(buildPayment("CAPTURED")));
        when(adminAlertRepository.findFirstByReferenceIdAndTypeAndStatus(any(), any(), any()))
            .thenReturn(Optional.empty());
        com.softropic.skillars.platform.admin.contract.AdminNotificationRecipient raiser =
            new com.softropic.skillars.platform.admin.contract.AdminNotificationRecipient("p@x.test", "fr");
        when(recipientResolver.forUser(1L)).thenReturn(Optional.of(raiser));

        service.resolveDispute(disputeId, "PARTIAL_CREDIT", new BigDecimal("5.00"), "half", 99L);

        var logged = org.mockito.ArgumentCaptor.forClass(com.softropic.skillars.platform.admin.repo.AdminActionLog.class);
        verify(adminActionLogRepository).save(logged.capture());
        assertThat(logged.getValue().getActionType())
            .isEqualTo(com.softropic.skillars.platform.admin.contract.AdminActionType.DISPUTE_RESOLVE);
        var published = org.mockito.ArgumentCaptor.forClass(org.springframework.context.ApplicationEvent.class);
        verify(eventPublisher).publishEvent(published.capture());
        assertThat(published.getValue()).isInstanceOfSatisfying(
            com.softropic.skillars.platform.admin.contract.DisputeResolvedEvent.class, e -> {
                assertThat(e.getResolution()).isEqualTo("PARTIAL_CREDIT");
                assertThat(e.getCreditAmount()).isEqualByComparingTo("5.00");
                assertThat(e.getResolutionNote()).isEqualTo("half");
                assertThat(e.getRecipient()).isEqualTo(raiser);
            });
    }

    // ── feat/payment-forfeit-payouts-and-clawback: UPHOLD_COACH ─────────────────

    private void stubUpholdCoachBase(Dispute dispute, Booking booking) {
        when(disputeRepository.findById(disputeId)).thenReturn(Optional.of(dispute));
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(bookingPaymentRepository.findById(bookingId)).thenReturn(Optional.of(buildPayment("CAPTURED")));
    }

    @Test
    void resolveDispute_upholdCoach_withoutARefund_isRejectedBeforeAnyWrite() {
        Dispute dispute = buildDispute();
        Booking booking = buildBooking();
        booking.setStatus("NO_SHOW_COACH");
        stubUpholdCoachBase(dispute, booking);
        when(refundClawbackService.clawBack(bookingId, null)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveDispute(disputeId, "UPHOLD_COACH", null, "session happened", 99L))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasMessageContaining("disputes.noRefundToReverse");

        verify(disputeRepository, never()).save(any());
        verify(disputeRepository, never()).saveAndFlush(any());
        verifyNoInteractions(coachPayoutOutboxSupport);
    }

    @Test
    void resolveDispute_upholdCoach_secondTimeOnTheSameBooking_isRejected() {
        Dispute dispute = buildDispute();
        Booking booking = buildBooking();
        booking.setStatus("NO_SHOW_COACH");
        stubUpholdCoachBase(dispute, booking);
        when(disputeRepository.existsByBookingIdAndResolution(bookingId, "UPHOLD_COACH")).thenReturn(true);

        assertThatThrownBy(() -> service.resolveDispute(disputeId, "UPHOLD_COACH", null, "again", 99L))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasMessageContaining("disputes.noRefundToReverse");
        verifyNoInteractions(refundClawbackService);
    }

    @Test
    void resolveDispute_upholdCoach_clawsBackPartially_paysCoachInFull_andTellsBothSides() {
        Dispute dispute = buildDispute();
        dispute.setRaisedByRole("COACH");
        dispute.setRaisedBy(7L);
        Booking booking = buildBooking();
        booking.setStatus("NO_SHOW_COACH");
        stubUpholdCoachBase(dispute, booking);
        when(adminAlertRepository.findFirstByReferenceIdAndTypeAndStatus(any(), any(), any()))
            .thenReturn(Optional.empty());
        when(refundClawbackService.clawBack(bookingId, null)).thenReturn(Optional.of(
            new com.softropic.skillars.platform.payment.service.RefundClawbackService.RefundClawback(
                new BigDecimal("30.00"), new BigDecimal("12.00"), false, false)));
        // No payout row (a refunded booking never completed): a new one is computed and enqueued.
        com.softropic.skillars.platform.payment.repo.CoachPayout created = payout(
            com.softropic.skillars.platform.payment.contract.CoachPayoutStatus.PENDING_RELEASE);
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.empty());
        when(coachPayoutRepository.findById(bookingId)).thenReturn(Optional.of(created));
        var coachAddress = new com.softropic.skillars.platform.admin.contract.AdminNotificationRecipient("c@x.test", "en");
        var parentAddress = new com.softropic.skillars.platform.admin.contract.AdminNotificationRecipient("p@x.test", "de");
        when(recipientResolver.forUser(7L)).thenReturn(Optional.of(coachAddress));
        when(recipientResolver.forUser(1L)).thenReturn(Optional.of(parentAddress));

        service.resolveDispute(disputeId, "UPHOLD_COACH", new BigDecimal("99.00"), "session happened", 99L);

        assertThat(dispute.getStatus()).isEqualTo("RESOLVED");
        assertThat(dispute.getResolution()).isEqualTo("UPHOLD_COACH");
        assertThat(dispute.getCreditAmount()).isNull(); // a stray amount is never stored for this resolution
        assertThat(dispute.getRecoveredAmount()).isEqualByComparingTo("12.00");
        assertThat(dispute.getAbsorbedAmount()).isEqualByComparingTo("18.00");
        // The dispute is flushed RESOLVED before the payout is computed, so the payout is not frozen for it.
        var order = org.mockito.Mockito.inOrder(disputeRepository, coachPayoutOutboxSupport);
        order.verify(disputeRepository).saveAndFlush(dispute);
        order.verify(coachPayoutOutboxSupport).enqueuePayout(bookingId, booking.getCoachId());
        verify(disputeCreditService, never()).credit(any(), any(), any(), any(), any());

        var resolved = org.mockito.ArgumentCaptor.forClass(org.springframework.context.ApplicationEvent.class);
        verify(eventPublisher).publishEvent(resolved.capture());
        assertThat(resolved.getValue()).isInstanceOfSatisfying(
            com.softropic.skillars.platform.admin.contract.DisputeResolvedEvent.class, e -> {
                assertThat(e.getRecipient()).isEqualTo(coachAddress);
                assertThat(e.getAudience())
                    .isEqualTo(com.softropic.skillars.platform.admin.contract.UpholdCoachOutcome.Audience.COACH);
                assertThat(e.getUpholdOutcome().coachNet()).isEqualByComparingTo("27.00");
                assertThat(e.getUpholdOutcome().recovered()).isEqualByComparingTo("12.00");
            });
        var other = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(other.capture());
        assertThat(other.getValue()).isInstanceOfSatisfying(
            com.softropic.skillars.platform.admin.contract.DisputeCoachUpheldEvent.class, e -> {
                assertThat(e.recipient()).isEqualTo(parentAddress);
                assertThat(e.audience())
                    .isEqualTo(com.softropic.skillars.platform.admin.contract.UpholdCoachOutcome.Audience.PARENT);
                assertThat(e.outcome().absorbed()).isEqualByComparingTo("18.00");
            });
    }

    @Test
    void resolveDispute_upholdCoach_reinstatesACancelledPayout() {
        Dispute dispute = buildDispute();
        Booking booking = buildBooking();
        booking.setStatus("NO_SHOW_PLAYER");
        stubUpholdCoachBase(dispute, booking);
        when(adminAlertRepository.findFirstByReferenceIdAndTypeAndStatus(any(), any(), any()))
            .thenReturn(Optional.empty());
        when(refundClawbackService.clawBack(bookingId, null)).thenReturn(Optional.of(
            new com.softropic.skillars.platform.payment.service.RefundClawbackService.RefundClawback(
                new BigDecimal("30.00"), new BigDecimal("30.00"), false, false)));
        // An earlier FULL_CREDIT dispute cut the forfeit payout to nothing.
        com.softropic.skillars.platform.payment.repo.CoachPayout cancelled = payout(
            com.softropic.skillars.platform.payment.contract.CoachPayoutStatus.CANCELLED);
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(cancelled));
        when(coachPayoutFreezeRepository.isFrozenForShare(cancelled.getCoachId())).thenReturn(false);

        service.resolveDispute(disputeId, "UPHOLD_COACH", null, "session happened", 99L);

        assertThat(cancelled.getStatus())
            .isEqualTo(com.softropic.skillars.platform.payment.contract.CoachPayoutStatus.PENDING_RELEASE);
        verify(coachPayoutOutboxSupport).enqueueTransfer(eq(bookingId), any());
        verify(coachPayoutOutboxSupport, never()).enqueuePayout(any(), any());
        assertThat(dispute.getAbsorbedAmount()).isEqualByComparingTo("0.00");
    }

    // ── fix/payment-wallet-dispute-integrity: dispute credit capped at paid minus refunded ─────────────

    private void stubCapturedResolution(BigDecimal remaining) {
        when(disputeRepository.findById(disputeId)).thenReturn(Optional.of(buildDispute()));
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(buildBooking()));
        when(bookingPaymentRepository.findById(bookingId)).thenReturn(Optional.of(buildPayment("CAPTURED")));
        when(disputeCreditService.remainingRefundable(bookingId, new BigDecimal("30.00"))).thenReturn(remaining);
    }

    @Test
    void resolveDispute_fullCreditOnAnAlreadyRefundedBooking_is409_notAConstraintError() {
        stubCapturedResolution(BigDecimal.ZERO);

        assertThatThrownBy(() -> service.resolveDispute(disputeId, "FULL_CREDIT", null, "note", 99L))
            .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class, e -> {
                assertThat(e.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
                assertThat(e.getReason()).isEqualTo(DisputeError.ALREADY_REFUNDED.getErrorCode());
            });
        verify(disputeCreditService, never()).credit(any(), any(), any(), any(), any());
    }

    @Test
    void resolveDispute_fullCreditAfterAPartialRefund_creditsOnlyTheRemainder_andCutsTheCoachByIt() {
        stubCapturedResolution(new BigDecimal("12.00"));
        com.softropic.skillars.platform.payment.repo.CoachPayout row = payout("PENDING_RELEASE");
        row.setNetAmount(new BigDecimal("27.00"));
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(row));
        when(adminAlertRepository.findFirstByReferenceIdAndTypeAndStatus(any(), any(), any()))
            .thenReturn(Optional.empty());

        service.resolveDispute(disputeId, "FULL_CREDIT", null, "note", 99L);

        verify(disputeCreditService).credit(eq(1L), eq(bookingId), eq(disputeId), eq(new BigDecimal("12.00")), any());
        // The coach's net is cut by the 12.00 actually credited (12/30 of 27.00 = 10.80), not by the full price.
        assertThat(row.getNetAmount()).isEqualByComparingTo("16.20");
    }

    @Test
    void resolveDispute_partialCreditAboveWhatIsLeft_isRejected() {
        stubCapturedResolution(new BigDecimal("12.00"));

        assertThatThrownBy(() -> service.resolveDispute(disputeId, "PARTIAL_CREDIT", new BigDecimal("15.00"), "n", 99L))
            .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class, e ->
                assertThat(e.getReason()).isEqualTo(DisputeError.INVALID_CREDIT_AMOUNT.getErrorCode()));
        verify(disputeCreditService, never()).credit(any(), any(), any(), any(), any());
    }

    // ── fix/audit3-completion-dispute-integrity ──

    private void stubRaisable(Booking booking) {
        booking.setUpdatedAt(Instant.now());
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(configService.getBoundedLong(ConfigBounds.DISPUTES_SUBMISSION_WINDOW_DAYS)).thenReturn(14L);
        org.mockito.Mockito.lenient().when(disputeRepository.save(any(Dispute.class))).thenAnswer(inv -> {
            Dispute d = inv.getArgument(0);
            d.setId(UUID.randomUUID());
            return d;
        });
    }

    /** Exploit 1: a Quick Completed session awaiting confirmation is disputable by the family. */
    @Test
    void raiseDispute_parentOnASessionAwaitingConfirmation_isEligible_andFreezes() {
        Booking booking = buildBooking();
        booking.setStatus("COMPLETED_PENDING_CONFIRMATION");
        stubRaisable(booking);

        UUID id = service.raiseDispute(bookingId, "COACH_NO_SHOW", "never came", 1L, "PARENT");

        assertThat(id).isNotNull();
        verify(coachPayoutRepository).findByIdForUpdate(bookingId); // freezeForDispute
    }

    @Test
    void raiseDispute_coachOnASessionAwaitingConfirmation_isNotEligible() {
        Booking booking = buildBooking();
        booking.setStatus("COMPLETED_PENDING_CONFIRMATION");
        CoachProfile coachProfile = new CoachProfile();
        coachProfile.setId(booking.getCoachId());
        coachProfile.setUserId(500L);
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(coachProfileRepository.findById(booking.getCoachId())).thenReturn(Optional.of(coachProfile));

        assertThatThrownBy(() -> service.raiseDispute(bookingId, "OTHER", "x", 500L, "COACH"))
            .isInstanceOf(OperationNotAllowedException.class)
            .extracting(t -> ((OperationNotAllowedException) t).getErrorCode())
            .isEqualTo(com.softropic.skillars.platform.admin.contract.DisputeError.NOT_ELIGIBLE);
        verify(disputeRepository, never()).save(any());
    }

    /** Exploit 6: only the family side's own open dispute blocks a family raise — not the coach's. */
    @Test
    void raiseDispute_parent_checksOnlyTheFamilySideForAnOpenDispute() {
        Booking booking = buildBooking();
        stubRaisable(booking);
        when(disputeRepository.existsOpenByBookingIdAndRaisedByRoleIn(bookingId, List.of("PARENT", "PLAYER")))
            .thenReturn(false);

        service.raiseDispute(bookingId, "SESSION_QUALITY", "x", 1L, "PARENT");

        verify(disputeRepository).save(any(Dispute.class));
        verify(disputeRepository, never()).existsOpenByBookingIdAndRaisedByRoleIn(bookingId, List.of("COACH"));
    }

    @Test
    void raiseDispute_familySideAlreadyOpen_is409AlreadyRaised() {
        Booking booking = buildBooking();
        stubRaisable(booking);
        when(disputeRepository.existsOpenByBookingIdAndRaisedByRoleIn(bookingId, List.of("PARENT", "PLAYER")))
            .thenReturn(true);

        assertThatThrownBy(() -> service.raiseDispute(bookingId, "SESSION_QUALITY", "x", 1L, "PARENT"))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasMessageContaining("disputes.alreadyRaised");
        verify(disputeRepository, never()).save(any());
    }

    /** Exploit 5: a dismissed raiser can no longer refile (and re-freeze the payout) without limit. */
    @Test
    void raiseDispute_raiserAtTheCap_is409LimitReached_andFreezesNothing() {
        Booking booking = buildBooking();
        stubRaisable(booking);
        when(disputeRepository.countByBookingIdAndRaisedBy(bookingId, 1L)).thenReturn(2L);

        assertThatThrownBy(() -> service.raiseDispute(bookingId, "SESSION_QUALITY", "again", 1L, "PARENT"))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .satisfies(e -> assertThat(((org.springframework.web.server.ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(409))
            .hasMessageContaining("disputes.limitReached");
        verify(disputeRepository, never()).save(any());
        verify(coachPayoutRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void raiseDispute_raiserBelowTheCap_isAccepted() {
        Booking booking = buildBooking();
        stubRaisable(booking);
        when(disputeRepository.countByBookingIdAndRaisedBy(bookingId, 1L)).thenReturn(1L);

        assertThat(service.raiseDispute(bookingId, "SESSION_QUALITY", "again", 1L, "PARENT")).isNotNull();
    }

    /** Exploit 6: settling one side's dispute keeps the payout frozen while the other side's is open. */
    @Test
    void resolveDispute_withTheOtherSidesDisputeStillOpen_keepsThePayoutFrozen_andTheAlertOpen() {
        Dispute dispute = buildDispute();
        Booking booking = buildBooking();
        com.softropic.skillars.platform.payment.repo.CoachPayout payout =
            payout(com.softropic.skillars.platform.payment.contract.CoachPayoutStatus.FROZEN);
        payout.setDisputeFrozen(true);
        when(disputeRepository.findById(disputeId)).thenReturn(Optional.of(dispute));
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(bookingPaymentRepository.findById(bookingId)).thenReturn(Optional.of(buildPayment("CAPTURED")));
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(payout));
        when(disputeRepository.existsOtherOpenForBooking(bookingId, disputeId)).thenReturn(true);

        service.resolveDispute(disputeId, "NO_ACTION", null, "note", 99L);

        verify(disputeRepository).acquireBookingDisputeLock(bookingId.toString());
        assertThat(payout.isDisputeFrozen()).isTrue();
        assertThat(payout.getStatus()).isEqualTo(com.softropic.skillars.platform.payment.contract.CoachPayoutStatus.FROZEN);
        verify(coachPayoutOutboxSupport, never()).enqueueTransfer(any(), any());
        verify(adminAlertRepository, never()).findFirstByReferenceIdAndTypeAndStatus(any(), any(), any());
    }

    @Test
    void dismissDispute_withTheOtherSidesDisputeStillOpen_keepsThePayoutFrozen() {
        Dispute dispute = buildDispute();
        com.softropic.skillars.platform.payment.repo.CoachPayout payout =
            payout(com.softropic.skillars.platform.payment.contract.CoachPayoutStatus.FROZEN);
        payout.setDisputeFrozen(true);
        when(disputeRepository.findById(disputeId)).thenReturn(Optional.of(dispute));
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(payout));
        when(disputeRepository.existsOtherOpenForBooking(bookingId, disputeId)).thenReturn(true);

        service.dismissDispute(disputeId, "no merit", 99L);

        verify(disputeRepository).acquireBookingDisputeLock(bookingId.toString());
        assertThat(payout.isDisputeFrozen()).isTrue();
        verify(coachPayoutOutboxSupport, never()).enqueueTransfer(any(), any());
        verify(adminAlertRepository, never()).findFirstByReferenceIdAndTypeAndStatus(any(), any(), any());
    }
}
