package com.softropic.skillars.platform.admin.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.softropic.skillars.platform.admin.contract.AdminDisputeDetailDto;
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
import com.softropic.skillars.platform.payment.service.CreditWalletService;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;

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
    @Mock private CreditWalletService creditWalletService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private DisputeService service;

    private final UUID disputeId = UUID.randomUUID();
    private final UUID bookingId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DisputeService(
            disputeRepository, bookingRepository, bookingPaymentRepository, coachProfileRepository,
            coachCancellationHistoryRepository, adminAlertRepository, adminActionLogRepository,
            configService, creditWalletService, eventPublisher
        );
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
        when(configService.getLong("disputes.submissionWindowDays", 14L)).thenReturn(14L);
        when(disputeRepository.findOpenByBookingId(bookingId)).thenReturn(Optional.empty());
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

        verify(creditWalletService).writeLedgerEntry(
            eq(booking.getParentId()), eq(new BigDecimal("30.00")), eq("BOOKING_REFUND"),
            eq(booking.getId()), any());
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

        verifyNoInteractions(creditWalletService);
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
        verifyNoInteractions(creditWalletService);
    }
}
