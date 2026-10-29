package com.softropic.skillars.platform.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.payment.contract.CoachPayoutReversalPayload;
import com.softropic.skillars.platform.payment.contract.CoachPayoutStatus;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** skillars-deferred-106 AC8.3 / AC10.2. */
@ExtendWith(MockitoExtension.class)
class CoachPayoutReversalHandlerTest {

    @Mock CoachPayoutRepository coachPayoutRepository;
    @Mock PaymentGateway paymentGateway;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock CoachPayoutDebtRepository debtRepository;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private CoachPayoutReversalHandler handler;

    private final UUID bookingId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        handler = new CoachPayoutReversalHandler(coachPayoutRepository, paymentGateway, meterRegistry, objectMapper,
            eventPublisher, new CoachPayoutDebtService(debtRepository, meterRegistry, eventPublisher));
    }

    private final UUID reversalId = UUID.randomUUID();

    private String payload() throws Exception {
        return payload("45.00");
    }

    private String payload(String amount) throws Exception {
        return objectMapper.writeValueAsString(new CoachPayoutReversalPayload(bookingId, new BigDecimal(amount), reversalId));
    }

    private CoachPayout row(String status, String transferId) {
        CoachPayout r = new CoachPayout();
        r.setBookingId(bookingId);
        r.setCoachId(UUID.randomUUID());
        r.setStatus(status);
        r.setStripeTransferId(transferId);
        r.setNetAmount(new BigDecimal("45.00"));
        return r;
    }

    @Test
    void releasedRow_reversesAndMarksReversed() throws Exception {
        CoachPayout r = row(CoachPayoutStatus.RELEASED, "tr_1");
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(r));

        handler.handle(payload());

        verify(paymentGateway).reverseTransfer("tr_1", new BigDecimal("45.00"), "reversal-tr_1-" + reversalId);
        assertThat(r.getStatus()).isEqualTo(CoachPayoutStatus.REVERSED);
        assertThat(r.getReversedAt()).isNotNull();
        assertThat(r.getReversedAmount()).isEqualByComparingTo("45.00");
    }

    // fix/payment-payouts-disputes-webhooks item 6: a partial reversal keeps the row RELEASED so the
    // coach's remaining net still counts; a second partial reversal gets its own Stripe key.
    @Test
    void partialReversal_keepsReleased_tracksReversedAmount_andEachReversalHasItsOwnKey() throws Exception {
        CoachPayout r = row(CoachPayoutStatus.RELEASED, "tr_1");
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(r));

        handler.handle(payload("15.00"));
        assertThat(r.getStatus()).isEqualTo(CoachPayoutStatus.RELEASED);
        assertThat(r.getReversedAmount()).isEqualByComparingTo("15.00");
        assertThat(r.getEffectiveNetAmount()).isEqualByComparingTo("30.00");

        UUID second = UUID.randomUUID();
        handler.handle(objectMapper.writeValueAsString(
            new CoachPayoutReversalPayload(bookingId, new BigDecimal("40.00"), second)));
        // Capped at what is still with the coach (30.00), then the whole net is reversed.
        verify(paymentGateway).reverseTransfer("tr_1", new BigDecimal("15.00"), "reversal-tr_1-" + reversalId);
        verify(paymentGateway).reverseTransfer("tr_1", new BigDecimal("30.00"), "reversal-tr_1-" + second);
        assertThat(r.getStatus()).isEqualTo(CoachPayoutStatus.REVERSED);
        assertThat(r.getReversedAmount()).isEqualByComparingTo("45.00");
    }

    @Test
    void legacyPayloadWithoutReversalId_usesPerTransferKey() throws Exception {
        CoachPayout r = row(CoachPayoutStatus.RELEASED, "tr_1");
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(r));

        handler.handle("{\"bookingId\":\"" + bookingId + "\",\"amount\":45.00}");

        verify(paymentGateway).reverseTransfer("tr_1", new BigDecimal("45.00"), "reversal-tr_1");
    }

    @Test
    void nonReleasedRow_isNoOp() throws Exception {
        when(coachPayoutRepository.findByIdForUpdate(bookingId))
            .thenReturn(Optional.of(row(CoachPayoutStatus.CANCELLED, null)));

        handler.handle(payload());

        verify(paymentGateway, never()).reverseTransfer(any(), any(), any());
    }

    @Test
    void releasedRowWithoutTransferId_marksReversalFailed_noStripeCall() throws Exception {
        CoachPayout r = row(CoachPayoutStatus.RELEASED, null);
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(r));

        handler.handle(payload());

        verify(paymentGateway, never()).reverseTransfer(any(), any(), any());
        assertThat(r.getStatus()).isEqualTo(CoachPayoutStatus.REVERSAL_FAILED);
    }

    @Test
    void retryableReversalFailure_isRethrown() throws Exception {
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(row(CoachPayoutStatus.RELEASED, "tr_1")));
        doThrow(new CoachPayoutTransferException("payment.coachTransferReversalFailed", true, "NETWORK", null))
            .when(paymentGateway).reverseTransfer(any(), any(), any());

        assertThatThrownBy(() -> handler.handle(payload())).isInstanceOf(CoachPayoutTransferException.class);
    }

    // fix/payment-payout-recovery (M5 / stripe M5): a refused reversal is coach debt, offset from future payouts.
    @Test
    void nonRetryableReversalFailure_recordsCoachDebt_alerts_keepsRowWorkable_doesNotThrow() throws Exception {
        CoachPayout r = row(CoachPayoutStatus.RELEASED, "tr_1");
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(r));
        doThrow(new CoachPayoutTransferException(
            "payment.coachTransferReversalFailed", false, "COACH_BALANCE_INSUFFICIENT", new RuntimeException("no balance")))
            .when(paymentGateway).reverseTransfer(any(), any(), any());

        handler.handle(payload("20.00"));

        assertThat(r.getStatus()).as("not parked in REVERSAL_FAILED any more").isEqualTo(CoachPayoutStatus.RELEASED);
        assertThat(r.getReversedAmount()).isEqualByComparingTo("20.00");
        assertThat(r.getLastError()).startsWith("REVERSAL_DEBT COACH_BALANCE_INSUFFICIENT");
        ArgumentCaptor<CoachPayoutDebt> debt = ArgumentCaptor.forClass(CoachPayoutDebt.class);
        verify(debtRepository).save(debt.capture());
        assertThat(debt.getValue().getAmount()).isEqualByComparingTo("20.00");
        assertThat(debt.getValue().getCoachId()).isEqualTo(r.getCoachId());
        assertThat(debt.getValue().getStatus()).isEqualTo(CoachPayoutDebt.STATUS_OPEN);
        assertThat(meterRegistry.get(CoachPayoutReversalHandler.METRIC_REVERSAL_FAILED)
            .tag("reason", "COACH_BALANCE_INSUFFICIENT").counter().count()).isEqualTo(1.0);
        ArgumentCaptor<PaymentIntegrityAlertEvent> alert = ArgumentCaptor.forClass(PaymentIntegrityAlertEvent.class);
        verify(eventPublisher).publishEvent(alert.capture());
        assertThat(alert.getValue().getKind()).isEqualTo(PaymentIntegrityAlertEvent.Kind.PAYOUT_REVERSAL_FAILED);
        assertThat(alert.getValue().getReferenceId()).isEqualTo(bookingId.toString());
        assertThat(alert.getValue().getReason()).startsWith("COACH_DEBT 20.00");
    }

    @Test
    void reversalOnReversalFailedRow_isRecordedAsDebtAndAlerted_notIgnored() throws Exception {
        CoachPayout r = row(CoachPayoutStatus.REVERSAL_FAILED, "tr_1");
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(r));

        handler.handle(payload("10.00"));

        verify(paymentGateway, never()).reverseTransfer(any(), any(), any());
        ArgumentCaptor<CoachPayoutDebt> debt = ArgumentCaptor.forClass(CoachPayoutDebt.class);
        verify(debtRepository).save(debt.capture());
        assertThat(debt.getValue().getAmount()).isEqualByComparingTo("10.00");
        assertThat(debt.getValue().getReason()).isEqualTo("AFTER_REVERSAL_FAILED");
        verify(eventPublisher).publishEvent(any(PaymentIntegrityAlertEvent.class));
    }

    @Test
    void netPartlyWithheldForDebt_reversesOnlyWhatReachedStripe_restBecomesDebt() throws Exception {
        CoachPayout r = row(CoachPayoutStatus.RELEASED, "tr_1");
        r.setDebtOffsetAmount(new BigDecimal("15.00")); // the transfer carried 30.00 of the 45.00 net
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(r));

        handler.handle(payload("45.00"));

        verify(paymentGateway).reverseTransfer("tr_1", new BigDecimal("30.00"), "reversal-tr_1-" + reversalId);
        assertThat(r.getStatus()).isEqualTo(CoachPayoutStatus.REVERSED);
        ArgumentCaptor<CoachPayoutDebt> debt = ArgumentCaptor.forClass(CoachPayoutDebt.class);
        verify(debtRepository).save(debt.capture());
        assertThat(debt.getValue().getAmount()).isEqualByComparingTo("15.00");
        assertThat(debt.getValue().getReason()).isEqualTo("NOTHING_LEFT_AT_STRIPE");
    }

    @Test
    void wholeNetWithheldForDebt_noTransferId_isDebtNotReversalFailed() throws Exception {
        CoachPayout r = row(CoachPayoutStatus.RELEASED, null);
        r.setDebtOffsetAmount(new BigDecimal("45.00"));
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(r));

        handler.handle(payload("45.00"));

        verify(paymentGateway, never()).reverseTransfer(any(), any(), any());
        assertThat(r.getStatus()).isEqualTo(CoachPayoutStatus.REVERSED);
        verify(debtRepository).save(any(CoachPayoutDebt.class));
    }
}
