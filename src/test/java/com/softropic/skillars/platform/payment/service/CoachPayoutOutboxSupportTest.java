package com.softropic.skillars.platform.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.outbox.service.OutboxService;
import com.softropic.skillars.platform.payment.contract.CoachPayoutStatus;
import com.softropic.skillars.platform.payment.repo.BookingPayment;
import com.softropic.skillars.platform.payment.repo.BookingPaymentRepository;
import com.softropic.skillars.platform.payment.repo.CoachPayout;
import com.softropic.skillars.platform.payment.repo.CoachPayoutRepository;
import com.softropic.skillars.platform.payment.repo.CoachStripeAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** skillars-deferred-106 AC4.2 / AC4.4 / AC15.3: net-split and rate-locked-at-capture. */
@ExtendWith(MockitoExtension.class)
class CoachPayoutOutboxSupportTest {

    @Mock CoachPayoutRepository coachPayoutRepository;
    @Mock BookingPaymentRepository bookingPaymentRepository;
    @Mock CoachStripeAccountRepository coachStripeAccountRepository;
    @Mock OutboxService outboxService;
    @Mock ConfigService configService;

    private CoachPayoutOutboxSupport support;
    private final UUID bookingId = UUID.randomUUID();
    private final UUID coachId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        support = new CoachPayoutOutboxSupport(coachPayoutRepository, bookingPaymentRepository,
            coachStripeAccountRepository, outboxService, configService, new ObjectMapper());
        lenient().when(coachPayoutRepository.existsById(bookingId)).thenReturn(false);
        lenient().when(configService.getString("platform.payment.currency")).thenReturn("eur");
        lenient().when(configService.getBoundedLong(eq("payment.payout.hold_hours"), anyLong(), anyLong(), anyLong()))
            .thenReturn(48L);
        lenient().when(coachStripeAccountRepository.findById(coachId)).thenReturn(Optional.empty());
    }

    private BookingPayment bp(String charged, String commissionRate) {
        BookingPayment b = new BookingPayment();
        b.setBookingId(bookingId);
        b.setStripeCharged(new BigDecimal(charged));
        b.setCreditDebited(BigDecimal.ZERO);
        b.setCommissionRate(commissionRate == null ? null : new BigDecimal(commissionRate));
        b.setStatus("CAPTURED");
        return b;
    }

    private CoachPayout captureSavedRow() {
        ArgumentCaptor<CoachPayout> c = ArgumentCaptor.forClass(CoachPayout.class);
        verify(coachPayoutRepository).save(c.capture());
        return c.getValue();
    }

    @Test
    void fullCardBooking_stampedRate_writesPendingReleaseRowAndEnqueuesWithFutureNotBefore() {
        when(bookingPaymentRepository.findById(bookingId)).thenReturn(Optional.of(bp("50.00", "0.10")));

        support.enqueuePayout(bookingId, coachId);

        CoachPayout row = captureSavedRow();
        assertThat(row.getStatus()).isEqualTo(CoachPayoutStatus.PENDING_RELEASE);
        assertThat(row.getGrossAmount()).isEqualByComparingTo("50.00");
        assertThat(row.getCommissionAmount()).isEqualByComparingTo("5.00");
        assertThat(row.getNetAmount()).isEqualByComparingTo("45.00");
        assertThat(row.getReleaseAfter()).isAfter(Instant.now().plusSeconds(47 * 3600));
        verify(outboxService).enqueue(eq(CoachPayoutOutboxSupport.AGGREGATE_TYPE_TRANSFER), any(), any(Instant.class));
        verify(outboxService).requestDrainAfterCommit();
    }

    @Test
    void nullStampedRate_fallsBackToLivePlatformRate() {
        when(bookingPaymentRepository.findById(bookingId)).thenReturn(Optional.of(bp("50.00", null)));
        when(configService.getString("platform.commission.rate")).thenReturn("0.20");

        support.enqueuePayout(bookingId, coachId);

        assertThat(captureSavedRow().getNetAmount()).isEqualByComparingTo("40.00"); // 50 - 50*0.20
    }

    @Test
    void rateChangedBetweenCaptureAndCompletion_usesStampedRate_notLive() {
        when(bookingPaymentRepository.findById(bookingId)).thenReturn(Optional.of(bp("50.00", "0.08")));
        // live rate is now 0.20 — must be ignored
        lenient().when(configService.getString("platform.commission.rate")).thenReturn("0.20");

        support.enqueuePayout(bookingId, coachId);

        assertThat(captureSavedRow().getNetAmount()).isEqualByComparingTo("46.00"); // 50 - 50*0.08
    }

    @Test
    void partialCreditSplit_netComputedOnCardChargedPortionOnly() {
        // price 50: 10 covered by credit, 40 on card; rate 0.10 -> coach net = 40 * 0.90
        BookingPayment b = bp("40.00", "0.10");
        b.setCreditDebited(new BigDecimal("10.00"));
        when(bookingPaymentRepository.findById(bookingId)).thenReturn(Optional.of(b));

        support.enqueuePayout(bookingId, coachId);

        CoachPayout row = captureSavedRow();
        assertThat(row.getGrossAmount()).isEqualByComparingTo("40.00");
        assertThat(row.getNetAmount()).isEqualByComparingTo("36.00");
    }

    @Test
    void fullyCreditOrPackFunded_writesCancelledRow_noEnqueue() {
        when(bookingPaymentRepository.findById(bookingId)).thenReturn(Optional.of(bp("0.00", "0.10")));

        support.enqueuePayout(bookingId, coachId);

        CoachPayout row = captureSavedRow();
        assertThat(row.getStatus()).isEqualTo(CoachPayoutStatus.CANCELLED);
        assertThat(row.getLastError()).isEqualTo("NO_STRIPE_CHARGE");
        verify(outboxService, never()).enqueue(any(), any(), any());
        verify(outboxService, never()).enqueue(any(), any());
    }

    @Test
    void rowAlreadyExists_isNoOp() {
        when(coachPayoutRepository.existsById(bookingId)).thenReturn(true);

        support.enqueuePayout(bookingId, coachId);

        verify(coachPayoutRepository, never()).save(any());
        verify(outboxService, never()).enqueue(any(), any(), any());
    }
}
