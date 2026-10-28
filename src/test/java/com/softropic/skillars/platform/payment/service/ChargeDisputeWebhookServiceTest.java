package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.payment.contract.CoachPayoutStatus;
import com.softropic.skillars.platform.payment.contract.event.PaymentIntegrityAlertEvent;
import com.softropic.skillars.platform.payment.repo.BookingPayment;
import com.softropic.skillars.platform.payment.repo.BookingPaymentRepository;
import com.softropic.skillars.platform.payment.repo.ChargeDispute;
import com.softropic.skillars.platform.payment.repo.ChargeDisputeRepository;
import com.softropic.skillars.platform.payment.repo.CoachPayout;
import com.softropic.skillars.platform.payment.repo.CoachPayoutFreezeRepository;
import com.softropic.skillars.platform.payment.repo.CoachPayoutRepository;
import com.stripe.model.Charge;
import com.stripe.model.Dispute;
import com.stripe.model.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** fix/payment-payouts-disputes-webhooks item 1: chargebacks and refunds made outside the app. */
@ExtendWith(MockitoExtension.class)
class ChargeDisputeWebhookServiceTest {

    @Mock ChargeDisputeRepository chargeDisputeRepository;
    @Mock CoachPayoutRepository coachPayoutRepository;
    @Mock BookingPaymentRepository bookingPaymentRepository;
    @Mock CoachPayoutFreezeRepository coachPayoutFreezeRepository;
    @Mock CoachPayoutOutboxSupport coachPayoutOutboxSupport;
    @Mock ApplicationEventPublisher eventPublisher;

    private ChargeDisputeWebhookService service;
    private final UUID bookingA = UUID.randomUUID();
    private final UUID bookingB = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        CoachPayoutAdjustmentService adjustment = new CoachPayoutAdjustmentService(coachPayoutRepository,
            coachPayoutFreezeRepository, coachPayoutOutboxSupport);
        service = new ChargeDisputeWebhookService(chargeDisputeRepository, coachPayoutRepository,
            bookingPaymentRepository, adjustment, eventPublisher);
    }

    private static Dispute dispute(String status, long amountCents) {
        Dispute d = new Dispute();
        d.setId("dp_1");
        d.setPaymentIntent("pi_1");
        d.setCharge("ch_1");
        d.setAmount(amountCents);
        d.setCurrency("eur");
        d.setStatus(status);
        return d;
    }

    private CoachPayout payout(UUID bookingId, String status) {
        CoachPayout r = new CoachPayout();
        r.setBookingId(bookingId);
        r.setCoachId(UUID.randomUUID());
        r.setGrossAmount(new BigDecimal("50.00"));
        r.setCommissionAmount(new BigDecimal("5.00"));
        r.setNetAmount(new BigDecimal("45.00"));
        r.setReleaseAfter(Instant.now().minusSeconds(10));
        r.setStatus(status);
        return r;
    }

    private void batchOfTwo() {
        when(chargeDisputeRepository.findBookingIdsByPaymentIntent("pi_1")).thenReturn(List.of(bookingA, bookingB));
    }

    private PaymentIntegrityAlertEvent capturedAlert() {
        ArgumentCaptor<PaymentIntegrityAlertEvent> c = ArgumentCaptor.forClass(PaymentIntegrityAlertEvent.class);
        verify(eventPublisher).publishEvent(c.capture());
        return c.getValue();
    }

    @Test
    void created_freezesEveryUnreleasedPayoutOfTheBatch_releasedOnesAreOnlyAlerted() {
        batchOfTwo();
        CoachPayout pending = payout(bookingA, CoachPayoutStatus.PENDING_RELEASE);
        CoachPayout released = payout(bookingB, CoachPayoutStatus.RELEASED);
        when(coachPayoutRepository.findFundedByPaymentIntentForUpdate("pi_1")).thenReturn(List.of(pending, released));

        service.onDisputeCreated(dispute("needs_response", 10_000));

        assertThat(pending.getStatus()).isEqualTo(CoachPayoutStatus.FROZEN);
        assertThat(pending.isChargebackFrozen()).isTrue();
        assertThat(released.getStatus()).isEqualTo(CoachPayoutStatus.RELEASED);
        verify(coachPayoutOutboxSupport, never()).enqueueReversal(any(), any());
        ArgumentCaptor<ChargeDispute> saved = ArgumentCaptor.forClass(ChargeDispute.class);
        verify(chargeDisputeRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ChargeDispute.STATUS_OPEN);
        assertThat(saved.getValue().getAmount()).isEqualByComparingTo("100.00");
        PaymentIntegrityAlertEvent alert = capturedAlert();
        assertThat(alert.getKind()).isEqualTo(PaymentIntegrityAlertEvent.Kind.CHARGEBACK);
        assertThat(alert.getReferenceId()).isEqualTo("pi_1");
        assertThat(alert.getReason()).contains("frozen=1").contains("released=1");
    }

    @Test
    void created_unknownIntent_alertsAndDoesNotThrow() {
        service.onDisputeCreated(dispute("needs_response", 1_000));

        assertThat(capturedAlert().getReason()).startsWith("OPENED_UNKNOWN_INTENT");
        verify(coachPayoutRepository, never()).findFundedByPaymentIntentForUpdate(any());
    }

    @Test
    void closedWon_liftsTheFreeze_andReleases() {
        batchOfTwo();
        ChargeDispute open = new ChargeDispute();
        open.setStripeDisputeId("dp_1");
        open.setPaymentIntentId("pi_1");
        open.setAmount(new BigDecimal("100.00"));
        open.setStatus(ChargeDispute.STATUS_OPEN);
        when(chargeDisputeRepository.findByIdForUpdate("dp_1")).thenReturn(Optional.of(open));
        CoachPayout frozen = payout(bookingA, CoachPayoutStatus.FROZEN);
        frozen.setChargebackFrozen(true);
        when(coachPayoutRepository.findFundedByPaymentIntentForUpdate("pi_1")).thenReturn(List.of(frozen));

        service.onDisputeClosed(dispute("won", 10_000));

        assertThat(open.getStatus()).isEqualTo(ChargeDispute.STATUS_WON);
        assertThat(frozen.isChargebackFrozen()).isFalse();
        assertThat(frozen.getStatus()).isEqualTo(CoachPayoutStatus.PENDING_RELEASE);
        verify(coachPayoutOutboxSupport).enqueueTransfer(any(), any());
    }

    @Test
    void closedLost_cutsPendingAndReversesReleased_proportionalToTheDisputedPart() {
        batchOfTwo();
        when(chargeDisputeRepository.findByIdForUpdate("dp_1")).thenReturn(Optional.empty());
        // The batch charged 100.00 (50 + 50); 50.00 of it was disputed and lost -> half of each booking's card part.
        when(chargeDisputeRepository.sumChargedByPaymentIntent("pi_1")).thenReturn(new BigDecimal("100.00"));
        CoachPayout frozen = payout(bookingA, CoachPayoutStatus.FROZEN);
        frozen.setChargebackFrozen(true);
        CoachPayout released = payout(bookingB, CoachPayoutStatus.RELEASED);
        released.setStripeTransferId("tr_b");
        when(coachPayoutRepository.findFundedByPaymentIntentForUpdate("pi_1")).thenReturn(List.of(frozen, released));
        BookingPayment card = new BookingPayment();
        card.setStripeCharged(new BigDecimal("50.00"));
        when(bookingPaymentRepository.findById(any())).thenReturn(Optional.of(card));

        service.onDisputeClosed(dispute("lost", 5_000));

        assertThat(frozen.getNetAmount()).isEqualByComparingTo("22.50");
        assertThat(frozen.getStatus()).isEqualTo(CoachPayoutStatus.PENDING_RELEASE);
        verify(coachPayoutOutboxSupport).enqueueReversal(bookingB, new BigDecimal("22.50"));
        assertThat(capturedAlert().getReason()).startsWith("LOST").contains("adjusted=2");
    }

    @Test
    void closedLost_fullDispute_cancelsThePendingPayout() {
        batchOfTwo();
        when(chargeDisputeRepository.findByIdForUpdate("dp_1")).thenReturn(Optional.empty());
        when(chargeDisputeRepository.sumChargedByPaymentIntent("pi_1")).thenReturn(new BigDecimal("50.00"));
        CoachPayout frozen = payout(bookingA, CoachPayoutStatus.FROZEN);
        frozen.setChargebackFrozen(true);
        when(coachPayoutRepository.findFundedByPaymentIntentForUpdate("pi_1")).thenReturn(List.of(frozen));
        BookingPayment card = new BookingPayment();
        card.setStripeCharged(new BigDecimal("50.00"));
        when(bookingPaymentRepository.findById(bookingA)).thenReturn(Optional.of(card));

        service.onDisputeClosed(dispute("lost", 5_000));

        assertThat(frozen.getStatus()).isEqualTo(CoachPayoutStatus.CANCELLED);
        verify(coachPayoutOutboxSupport, never()).enqueueTransfer(any(), any());
    }

    @Test
    void closedTwice_isAppliedOnce() {
        ChargeDispute closed = new ChargeDispute();
        closed.setStatus(ChargeDispute.STATUS_LOST);
        when(chargeDisputeRepository.findByIdForUpdate("dp_1")).thenReturn(Optional.of(closed));

        service.onDisputeClosed(dispute("lost", 5_000));

        verify(coachPayoutRepository, never()).findFundedByPaymentIntentForUpdate(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void closedBeforeCreated_createdIsIgnored() {
        ChargeDispute closed = new ChargeDispute();
        closed.setStatus(ChargeDispute.STATUS_WON);
        when(chargeDisputeRepository.findByIdForUpdate("dp_1")).thenReturn(Optional.of(closed));

        service.onDisputeCreated(dispute("needs_response", 5_000));

        verify(coachPayoutRepository, never()).findFundedByPaymentIntentForUpdate(any());
    }

    private static Event eventWithKey(String key) {
        Event e = new Event();
        Event.Request r = new Event.Request();
        r.setIdempotencyKey(key);
        e.setRequest(r);
        return e;
    }

    private static Charge charge() {
        Charge c = new Charge();
        c.setId("ch_1");
        c.setPaymentIntent("pi_1");
        c.setAmount(5_000L);
        c.setAmountRefunded(2_000L);
        return c;
    }

    @Test
    void refunded_byTheApp_isIgnored() {
        service.onChargeRefunded(charge(), eventWithKey("cashout-" + UUID.randomUUID()));
        service.onChargeRefunded(charge(), eventWithKey("refund-pi_1"));

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void refunded_outsideTheApp_alertsWithoutMovingMoney() {
        batchOfTwo();
        service.onChargeRefunded(charge(), eventWithKey(null));

        PaymentIntegrityAlertEvent alert = capturedAlert();
        assertThat(alert.getKind()).isEqualTo(PaymentIntegrityAlertEvent.Kind.EXTERNAL_REFUND);
        assertThat(alert.getReferenceId()).isEqualTo("pi_1");
        assertThat(alert.getReason()).isEqualTo("refunded 20.00 of 50.00");
        verify(coachPayoutRepository, never()).findFundedByPaymentIntentForUpdate(any());
    }
}
