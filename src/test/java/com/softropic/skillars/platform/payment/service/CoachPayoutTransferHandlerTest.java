package com.softropic.skillars.platform.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.payment.contract.CoachPayoutStatus;
import com.softropic.skillars.platform.payment.contract.CoachPayoutTransferPayload;
import com.softropic.skillars.platform.payment.contract.PaymentGateway;
import com.softropic.skillars.platform.payment.contract.event.PaymentIntegrityAlertEvent;
import com.softropic.skillars.platform.payment.contract.exception.CoachPayoutTransferException;
import com.softropic.skillars.platform.payment.repo.CoachPayout;
import com.softropic.skillars.platform.payment.repo.CoachPayoutDebt;
import com.softropic.skillars.platform.payment.repo.CoachPayoutDebtRepository;
import com.softropic.skillars.platform.payment.repo.CoachPayoutRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * skillars-deferred-106 AC7 / AC8 / AC15.1: {@code CoachPayoutTransferHandler} idempotency + the
 * retryable-vs-HOLD split.
 */
@ExtendWith(MockitoExtension.class)
class CoachPayoutTransferHandlerTest {

    @Mock CoachPayoutRepository coachPayoutRepository;
    @Mock com.softropic.skillars.platform.payment.repo.CoachPayoutFreezeRepository coachPayoutFreezeRepository;
    @Mock PaymentGateway paymentGateway;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock CoachPayoutDebtRepository debtRepository;
    @Mock com.softropic.skillars.platform.payment.repo.CoachStripeAccountRepository coachStripeAccountRepository;
    @Mock CoachPayoutOutboxSupport coachPayoutOutboxSupport;
    @Mock RelatedPartyService relatedPartyService;
    @Mock org.springframework.beans.factory.ObjectProvider<CoachPayoutOutboxSupport> outboxSupportProvider;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private CoachPayoutTransferHandler handler;
    private final UUID bookingId = UUID.randomUUID();
    private final UUID coachId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        handler = new CoachPayoutTransferHandler(coachPayoutRepository, coachPayoutFreezeRepository, paymentGateway,
            meterRegistry, objectMapper, eventPublisher,
            new CoachPayoutDebtService(debtRepository, meterRegistry, eventPublisher),
            coachStripeAccountRepository, relatedPartyService, outboxSupportProvider);
    }

    private String payload() throws Exception {
        return objectMapper.writeValueAsString(new CoachPayoutTransferPayload(bookingId));
    }

    private CoachPayout row(String status, Instant releaseAfter) {
        CoachPayout r = new CoachPayout();
        r.setBookingId(bookingId);
        r.setCoachId(coachId);
        r.setCurrency("eur");
        r.setGrossAmount(new BigDecimal("50.00"));
        r.setCommissionAmount(new BigDecimal("5.00"));
        r.setNetAmount(new BigDecimal("45.00"));
        r.setReleaseAfter(releaseAfter);
        r.setStatus(status);
        return r;
    }

    @Test
    void happyPath_pendingRelease_pastHold_transfersOnceAndReleases() throws Exception {
        CoachPayout r = row(CoachPayoutStatus.PENDING_RELEASE, Instant.now().minus(1, ChronoUnit.HOURS));
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(r));
        when(paymentGateway.transferToCoach(bookingId, coachId, new BigDecimal("45.00"), "eur"))
            .thenReturn("tr_1");

        handler.handle(payload());

        verify(paymentGateway, times(1)).transferToCoach(any(), any(), any(), any());
        assertThat(r.getStatus()).isEqualTo(CoachPayoutStatus.RELEASED);
        assertThat(r.getStripeTransferId()).isEqualTo("tr_1");
        assertThat(r.getReleasedAt()).isNotNull();
    }

    @Test
    void recentlyChangedPayoutAccount_defersTransferAndReEnqueuesForTheEndOfTheCoolingOff() throws Exception {
        CoachPayout r = row(CoachPayoutStatus.PENDING_RELEASE, Instant.now().minus(1, ChronoUnit.HOURS));
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(r));
        when(outboxSupportProvider.getObject()).thenReturn(coachPayoutOutboxSupport);
        Instant holdUntil = Instant.now().plus(40, ChronoUnit.HOURS);
        var account = new com.softropic.skillars.platform.payment.repo.CoachStripeAccount();
        account.setPayoutHoldUntil(holdUntil);
        when(coachStripeAccountRepository.findById(coachId)).thenReturn(Optional.of(account));

        handler.handle(payload());

        verify(paymentGateway, never()).transferToCoach(any(), any(), any(), any());
        verify(coachPayoutOutboxSupport).enqueueTransfer(bookingId, holdUntil);
        assertThat(r.getStatus()).isEqualTo(CoachPayoutStatus.PENDING_RELEASE);
    }

    @Test
    void relatedPartyBooking_flaggedAfterTheEnqueue_defersTransferUntilTheLongHoldEnds() throws Exception {
        CoachPayout r = row(CoachPayoutStatus.PENDING_RELEASE, Instant.now().minus(1, ChronoUnit.HOURS));
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(r));
        when(outboxSupportProvider.getObject()).thenReturn(coachPayoutOutboxSupport);
        Instant holdUntil = Instant.now().plus(30, ChronoUnit.DAYS);
        when(relatedPartyService.findPayoutHoldUntil(bookingId)).thenReturn(Optional.of(holdUntil));

        handler.handle(payload());

        verify(paymentGateway, never()).transferToCoach(any(), any(), any(), any());
        verify(coachPayoutOutboxSupport).enqueueTransfer(bookingId, holdUntil);
        assertThat(r.getStatus()).isEqualTo(CoachPayoutStatus.PENDING_RELEASE);
    }

    @Test
    void relatedPartyHoldThatHasPassed_doesNotDelayTheTransfer() throws Exception {
        CoachPayout r = row(CoachPayoutStatus.PENDING_RELEASE, Instant.now().minus(1, ChronoUnit.HOURS));
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(r));
        when(relatedPartyService.findPayoutHoldUntil(bookingId))
            .thenReturn(Optional.of(Instant.now().minus(1, ChronoUnit.HOURS)));
        when(paymentGateway.transferToCoach(bookingId, coachId, new BigDecimal("45.00"), "eur")).thenReturn("tr_1");

        handler.handle(payload());

        assertThat(r.getStatus()).isEqualTo(CoachPayoutStatus.RELEASED);
    }

    @Test
    void expiredPayoutAccountHold_doesNotDelayTheTransfer() throws Exception {
        CoachPayout r = row(CoachPayoutStatus.PENDING_RELEASE, Instant.now().minus(1, ChronoUnit.HOURS));
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(r));
        var account = new com.softropic.skillars.platform.payment.repo.CoachStripeAccount();
        account.setPayoutHoldUntil(Instant.now().minus(1, ChronoUnit.HOURS));
        when(coachStripeAccountRepository.findById(coachId)).thenReturn(Optional.of(account));
        when(paymentGateway.transferToCoach(bookingId, coachId, new BigDecimal("45.00"), "eur")).thenReturn("tr_1");

        handler.handle(payload());

        assertThat(r.getStatus()).isEqualTo(CoachPayoutStatus.RELEASED);
        verify(coachPayoutOutboxSupport, never()).enqueueTransfer(any(), any());
    }

    // fix/payment-payout-recovery (M5): an open coach debt is repaid from the next transfer, oldest first.
    @Test
    void openCoachDebt_isWithheldFromTheTransfer_andMarkedRepaid() throws Exception {
        CoachPayout r = row(CoachPayoutStatus.PENDING_RELEASE, Instant.now().minus(1, ChronoUnit.HOURS));
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(r));
        CoachPayoutDebt older = CoachPayoutDebt.open(coachId, UUID.randomUUID(), new BigDecimal("10.00"), "X");
        CoachPayoutDebt newer = CoachPayoutDebt.open(coachId, UUID.randomUUID(), new BigDecimal("20.00"), "Y");
        when(debtRepository.findOpenByCoachIdForUpdate(coachId)).thenReturn(java.util.List.of(older, newer));
        when(paymentGateway.transferToCoach(bookingId, coachId, new BigDecimal("15.00"), "eur")).thenReturn("tr_1");

        handler.handle(payload());

        assertThat(r.getStatus()).isEqualTo(CoachPayoutStatus.RELEASED);
        assertThat(r.getDebtOffsetAmount()).isEqualByComparingTo("30.00");
        assertThat(r.getNetAmount()).as("the coach still earned the whole net").isEqualByComparingTo("45.00");
        assertThat(older.getStatus()).isEqualTo(CoachPayoutDebt.STATUS_RECOVERED);
        assertThat(newer.getStatus()).isEqualTo(CoachPayoutDebt.STATUS_RECOVERED);
    }

    @Test
    void debtCoveringTheWholeNet_releasesWithoutAStripeTransfer() throws Exception {
        CoachPayout r = row(CoachPayoutStatus.PENDING_RELEASE, Instant.now().minus(1, ChronoUnit.HOURS));
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(r));
        CoachPayoutDebt big = CoachPayoutDebt.open(coachId, UUID.randomUUID(), new BigDecimal("60.00"), "X");
        when(debtRepository.findOpenByCoachIdForUpdate(coachId)).thenReturn(java.util.List.of(big));

        handler.handle(payload());

        verify(paymentGateway, never()).transferToCoach(any(), any(), any(), any());
        assertThat(r.getStatus()).isEqualTo(CoachPayoutStatus.RELEASED);
        assertThat(r.getStripeTransferId()).isNull();
        assertThat(r.getDebtOffsetAmount()).isEqualByComparingTo("45.00");
        assertThat(big.getOutstanding()).isEqualByComparingTo("15.00");
        assertThat(big.getStatus()).isEqualTo(CoachPayoutDebt.STATUS_OPEN);
    }

    @Test
    void holdAfterPlannedOffset_repaysNothing() throws Exception {
        CoachPayout r = row(CoachPayoutStatus.PENDING_RELEASE, Instant.now().minus(1, ChronoUnit.HOURS));
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(r));
        CoachPayoutDebt debt = CoachPayoutDebt.open(coachId, UUID.randomUUID(), new BigDecimal("10.00"), "X");
        when(debtRepository.findOpenByCoachIdForUpdate(coachId)).thenReturn(java.util.List.of(debt));
        when(paymentGateway.transferToCoach(any(), any(), any(), any()))
            .thenThrow(new CoachPayoutTransferException("payment.coachStripeNotConfigured", false, "ACCOUNT_NOT_READY",
                new IllegalStateException("x")));

        handler.handle(payload());

        assertThat(r.getStatus()).isEqualTo(CoachPayoutStatus.HOLD);
        assertThat(r.getDebtOffsetAmount()).isEqualByComparingTo("0");
        assertThat(debt.getOutstanding()).isEqualByComparingTo("10.00");
    }

    @Test
    void coachUnderSafetyHold_atReleaseTime_freezesInsteadOfTransferring() throws Exception {
        CoachPayout r = row(CoachPayoutStatus.PENDING_RELEASE, Instant.now().minus(1, ChronoUnit.HOURS));
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(r));
        when(coachPayoutFreezeRepository.isFrozenForShare(coachId)).thenReturn(true);

        handler.handle(payload());

        verify(paymentGateway, never()).transferToCoach(any(), any(), any(), any());
        assertThat(r.getStatus()).isEqualTo(CoachPayoutStatus.FROZEN);
        assertThat(r.getLastError()).isEqualTo(CoachPayoutFreezeService.REASON_SAFETY_HOLD);
        assertThat(meterRegistry.counter(CoachPayoutTransferHandler.METRIC_FROZEN).count()).isEqualTo(1.0);
    }

    @Test
    void reDriveOfFrozenRow_isNoOp() throws Exception {
        when(coachPayoutRepository.findByIdForUpdate(bookingId))
            .thenReturn(Optional.of(row(CoachPayoutStatus.FROZEN, Instant.now().minusSeconds(1))));

        handler.handle(payload());

        verify(paymentGateway, never()).transferToCoach(any(), any(), any(), any());
    }

    @Test
    void reDriveOfReleasedRow_isNoOp() throws Exception {
        when(coachPayoutRepository.findByIdForUpdate(bookingId))
            .thenReturn(Optional.of(row(CoachPayoutStatus.RELEASED, Instant.now().minusSeconds(1))));

        handler.handle(payload());

        verify(paymentGateway, never()).transferToCoach(any(), any(), any(), any());
    }

    @Test
    void reDriveOfHoldRow_isNoOp() throws Exception {
        when(coachPayoutRepository.findByIdForUpdate(bookingId))
            .thenReturn(Optional.of(row(CoachPayoutStatus.HOLD, Instant.now().minusSeconds(1))));

        handler.handle(payload());

        verify(paymentGateway, never()).transferToCoach(any(), any(), any(), any());
    }

    @Test
    void reDriveOfCancelledRow_isNoOp() throws Exception {
        when(coachPayoutRepository.findByIdForUpdate(bookingId))
            .thenReturn(Optional.of(row(CoachPayoutStatus.CANCELLED, Instant.now().minusSeconds(1))));

        handler.handle(payload());

        verify(paymentGateway, never()).transferToCoach(any(), any(), any(), any());
    }

    @Test
    void reDriveOfFailedPermanentRow_isNoOp() throws Exception {
        when(coachPayoutRepository.findByIdForUpdate(bookingId))
            .thenReturn(Optional.of(row(CoachPayoutStatus.FAILED_PERMANENT, Instant.now().minusSeconds(1))));

        handler.handle(payload());

        verify(paymentGateway, never()).transferToCoach(any(), any(), any(), any());
    }

    @Test
    void missingRow_isNoOp() throws Exception {
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.empty());

        handler.handle(payload());

        verify(paymentGateway, never()).transferToCoach(any(), any(), any(), any());
    }

    @Test
    void releaseAfterInFuture_throwsSoOutboxDefers() throws Exception {
        when(coachPayoutRepository.findByIdForUpdate(bookingId))
            .thenReturn(Optional.of(row(CoachPayoutStatus.PENDING_RELEASE, Instant.now().plus(2, ChronoUnit.HOURS))));

        assertThatThrownBy(() -> handler.handle(payload())).isInstanceOf(IllegalStateException.class);

        verify(paymentGateway, never()).transferToCoach(any(), any(), any(), any());
    }

    @Test
    void retryableTransferFailure_isRethrown_rowStaysPendingRelease() throws Exception {
        CoachPayout r = row(CoachPayoutStatus.PENDING_RELEASE, Instant.now().minusSeconds(1));
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(r));
        when(paymentGateway.transferToCoach(any(), any(), any(), any()))
            .thenThrow(new CoachPayoutTransferException("payment.coachTransferFailed", true, "NETWORK", null));

        assertThatThrownBy(() -> handler.handle(payload())).isInstanceOf(CoachPayoutTransferException.class);

        assertThat(r.getStatus()).isEqualTo(CoachPayoutStatus.PENDING_RELEASE);
    }

    @Test
    void nonRetryableTransferFailure_movesRowToHold_incrementsMetric_doesNotThrow() throws Exception {
        CoachPayout r = row(CoachPayoutStatus.PENDING_RELEASE, Instant.now().minusSeconds(1));
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(r));
        when(paymentGateway.transferToCoach(any(), any(), any(), any()))
            .thenThrow(new CoachPayoutTransferException(
                "payment.coachTransferFailed", false, "INVALID_DESTINATION", new RuntimeException("acct gone")));

        handler.handle(payload()); // must not throw

        assertThat(r.getStatus()).isEqualTo(CoachPayoutStatus.HOLD);
        assertThat(r.getLastError()).contains("INVALID_DESTINATION");
        assertThat(meterRegistry.get(CoachPayoutTransferHandler.METRIC_HELD)
            .tag("reason", "INVALID_DESTINATION").counter().count()).isEqualTo(1.0);
        // fix/payment-payouts-disputes-webhooks item 8: an admin alert per booking.
        ArgumentCaptor<PaymentIntegrityAlertEvent> alert = ArgumentCaptor.forClass(PaymentIntegrityAlertEvent.class);
        verify(eventPublisher).publishEvent(alert.capture());
        assertThat(alert.getValue().getKind()).isEqualTo(PaymentIntegrityAlertEvent.Kind.PAYOUT_HELD);
        assertThat(alert.getValue().getReferenceId()).isEqualTo(bookingId.toString());
    }

    // fix/payment-payouts-disputes-webhooks item 4: an unusable account is classified non-retryable by the
    // gateway, so the handler holds the row and counts the attempt instead of re-driving forever.
    @Test
    void accountNotReady_movesRowToHold_andCountsTheAttempt() throws Exception {
        CoachPayout r = row(CoachPayoutStatus.PENDING_RELEASE, Instant.now().minusSeconds(1));
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(r));
        when(paymentGateway.transferToCoach(any(), any(), any(), any()))
            .thenThrow(new CoachPayoutTransferException("payment.coachStripeNotConfigured", false, "ACCOUNT_NOT_READY",
                new IllegalStateException("transfers=inactive")));

        handler.handle(payload());

        assertThat(r.getStatus()).isEqualTo(CoachPayoutStatus.HOLD);
        assertThat(r.getAttempts()).isEqualTo(1);
        assertThat(r.getLastError()).startsWith("ACCOUNT_NOT_READY");
    }

    // fix/payment-payouts-disputes-webhooks item 2: a dispute freeze stops the transfer even if the row was
    // put back to PENDING_RELEASE by hand.
    @Test
    void disputeFrozenRow_isFrozen_noTransfer() throws Exception {
        CoachPayout r = row(CoachPayoutStatus.PENDING_RELEASE, Instant.now().minusSeconds(1));
        r.setDisputeFrozen(true);
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(r));

        handler.handle(payload());

        assertThat(r.getStatus()).isEqualTo(CoachPayoutStatus.FROZEN);
        assertThat(r.getLastError()).isEqualTo(CoachPayoutAdjustmentService.REASON_DISPUTE_OPEN);
        verify(paymentGateway, never()).transferToCoach(any(), any(), any(), any());
    }
}
