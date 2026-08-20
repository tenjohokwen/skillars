package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.platform.admin.contract.AdminDisputeDetailDto;
import com.softropic.skillars.platform.admin.repo.AdminActionLogRepository;
import com.softropic.skillars.platform.admin.repo.AdminAlertRepository;
import com.softropic.skillars.platform.admin.repo.Dispute;
import com.softropic.skillars.platform.admin.repo.DisputeRepository;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.repo.BookingPayment;
import com.softropic.skillars.platform.payment.repo.BookingPaymentRepository;
import com.softropic.skillars.platform.payment.repo.CoachCancellationHistoryRepository;
import com.softropic.skillars.platform.payment.service.CreditWalletService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
}
