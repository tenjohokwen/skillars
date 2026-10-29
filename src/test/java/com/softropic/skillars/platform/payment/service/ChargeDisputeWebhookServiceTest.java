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
import com.softropic.skillars.platform.payment.repo.ParentCreditLedgerRepository;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchase;
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
import static org.mockito.ArgumentMatchers.eq;
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
    @Mock ParentCreditLedgerRepository ledgerRepository;
    @Mock CreditWalletService creditWalletService;
    @Mock PackSessionService packSessionService;

    private ChargeDisputeWebhookService service;
    private final UUID bookingA = UUID.randomUUID();
    private final UUID bookingB = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        CoachPayoutAdjustmentService adjustment = new CoachPayoutAdjustmentService(coachPayoutRepository,
            coachPayoutFreezeRepository, coachPayoutOutboxSupport,
            new com.softropic.skillars.platform.payment.service.CoachPayoutDebtService(org.mockito.Mockito.mock(com.softropic.skillars.platform.payment.repo.CoachPayoutDebtRepository.class), new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), eventPublisher));
        ChargebackCreditReversalService credit = new ChargebackCreditReversalService(ledgerRepository,
            chargeDisputeRepository, creditWalletService, eventPublisher);
        service = new ChargeDisputeWebhookService(chargeDisputeRepository, coachPayoutRepository,
            bookingPaymentRepository, adjustment, credit, eventPublisher, packSessionService);
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
        verify(chargeDisputeRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
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

    // ── fix/payment-dispute-credit-trace-and-lost-dispute: wallet credit the disputed charge funded ──

    private static ParentCreditLedgerRepository.TracedCredit traced(long parentId, String amount) {
        return new ParentCreditLedgerRepository.TracedCredit() {
            @Override public Long getParentId() { return parentId; }
            @Override public BigDecimal getAmount() { return new BigDecimal(amount); }
        };
    }

    private List<PaymentIntegrityAlertEvent> capturedAlerts() {
        ArgumentCaptor<PaymentIntegrityAlertEvent> c = ArgumentCaptor.forClass(PaymentIntegrityAlertEvent.class);
        verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(c.capture());
        return c.getAllValues();
    }

    @Test
    void created_reversesTheUnspentCreditTheChargeFunded_andAlertsTheSpentPart() {
        batchOfTwo();
        when(ledgerRepository.findCreditFundedByPaymentIntent("pi_1")).thenReturn(List.of(traced(7L, "30.00")));
        UUID ref = ChargebackCreditReversalService.reference("dp_1");
        when(creditWalletService.debitChargebackCredit(eq(7L), eq(ref), eq(new BigDecimal("30.00")), any()))
            .thenReturn(new BigDecimal("20.00"));

        service.onDisputeCreated(dispute("needs_response", 10_000));

        ArgumentCaptor<ChargeDispute> saved = ArgumentCaptor.forClass(ChargeDispute.class);
        verify(chargeDisputeRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertThat(saved.getValue().getCreditReversalTarget()).isEqualByComparingTo("30.00");
        List<PaymentIntegrityAlertEvent> alerts = capturedAlerts();
        assertThat(alerts).anySatisfy(a -> {
            assertThat(a.getReferenceId()).isEqualTo("dp_1");
            assertThat(a.getReason()).isEqualTo("CREDIT_SHORTFALL OPENED 10.00 of 30.00");
        });
        assertThat(alerts).anySatisfy(a -> {
            assertThat(a.getReferenceId()).isEqualTo("pi_1");
            assertThat(a.getReason()).startsWith("OPENED").endsWith("credit=-20.00");
        });
    }

    @Test
    void created_redelivered_doesNotDebitAgain() {
        ChargeDispute open = new ChargeDispute();
        open.setStripeDisputeId("dp_1");
        open.setPaymentIntentId("pi_1");
        open.setAmount(new BigDecimal("100.00"));
        open.setStatus(ChargeDispute.STATUS_OPEN);
        open.setCreditReversalTarget(new BigDecimal("30.00"));
        when(chargeDisputeRepository.findByIdForUpdate("dp_1")).thenReturn(Optional.of(open));

        service.onDisputeCreated(dispute("needs_response", 10_000));

        verify(creditWalletService, never()).debitChargebackCredit(any(), any(), any(), any());
    }

    @Test
    void closedWon_restoresTheReversedCredit() {
        batchOfTwo();
        ChargeDispute open = new ChargeDispute();
        open.setStripeDisputeId("dp_1");
        open.setPaymentIntentId("pi_1");
        open.setAmount(new BigDecimal("100.00"));
        open.setStatus(ChargeDispute.STATUS_OPEN);
        open.setCreditReversalTarget(new BigDecimal("30.00"));
        when(chargeDisputeRepository.findByIdForUpdate("dp_1")).thenReturn(Optional.of(open));
        UUID ref = ChargebackCreditReversalService.reference("dp_1");
        com.softropic.skillars.platform.payment.repo.ParentCreditLedger reversal =
            new com.softropic.skillars.platform.payment.repo.ParentCreditLedger();
        reversal.setParentId(7L);
        reversal.setAmount(new BigDecimal("-20.00"));
        when(ledgerRepository.findByReferenceIdAndTypeOrderByParentId(ref,
            com.softropic.skillars.platform.payment.contract.CreditLedgerEntryType.CHARGEBACK_CREDIT_REVERSAL))
            .thenReturn(List.of(reversal));
        when(creditWalletService.restoreChargebackCredit(7L, ref, new BigDecimal("20.00"), BigDecimal.ZERO)).thenReturn(true);

        service.onDisputeClosed(dispute("won", 10_000));

        verify(creditWalletService).restoreChargebackCredit(7L, ref, new BigDecimal("20.00"), BigDecimal.ZERO);
        assertThat(capturedAlert().getReason()).startsWith("WON").endsWith("restored=20.00");
    }

    @Test
    void closedLost_disputedAmountCoveredByTheReversedCredit_leavesThePayoutWhole() {
        batchOfTwo();
        ChargeDispute open = new ChargeDispute();
        open.setStripeDisputeId("dp_1");
        open.setPaymentIntentId("pi_1");
        open.setAmount(new BigDecimal("50.00"));
        open.setStatus(ChargeDispute.STATUS_OPEN);
        open.setCreditReversalTarget(new BigDecimal("50.00"));
        when(chargeDisputeRepository.findByIdForUpdate("dp_1")).thenReturn(Optional.of(open));
        // One booking of the 100.00 batch was refunded to credit (50.00), and exactly that was disputed.
        when(ledgerRepository.findCreditFundedByPaymentIntent("pi_1")).thenReturn(List.of(traced(7L, "50.00")));
        when(chargeDisputeRepository.sumChargedByPaymentIntent("pi_1")).thenReturn(new BigDecimal("100.00"));
        CoachPayout frozen = payout(bookingA, CoachPayoutStatus.FROZEN);
        frozen.setChargebackFrozen(true);
        when(coachPayoutRepository.findFundedByPaymentIntentForUpdate("pi_1")).thenReturn(List.of(frozen));

        service.onDisputeClosed(dispute("lost", 5_000));

        assertThat(open.getPayoutLossFraction()).isEqualByComparingTo("0");
        assertThat(frozen.getNetAmount()).isEqualByComparingTo("45.00");
        assertThat(frozen.getStatus()).isEqualTo(CoachPayoutStatus.PENDING_RELEASE);
        verify(creditWalletService, never()).debitChargebackCredit(any(), any(), any(), any());
    }

    @Test
    void closedLost_beforeCreated_tracesAndReversesTheCredit_thenCutsTheRestProRata() {
        batchOfTwo();
        when(chargeDisputeRepository.findByIdForUpdate("dp_1")).thenReturn(Optional.empty());
        when(ledgerRepository.findCreditFundedByPaymentIntent("pi_1")).thenReturn(List.of(traced(7L, "50.00")));
        UUID ref = ChargebackCreditReversalService.reference("dp_1");
        when(creditWalletService.debitChargebackCredit(eq(7L), eq(ref), eq(new BigDecimal("50.00")), any()))
            .thenReturn(new BigDecimal("50.00"));
        when(chargeDisputeRepository.sumChargedByPaymentIntent("pi_1")).thenReturn(new BigDecimal("100.00"));
        CoachPayout frozen = payout(bookingA, CoachPayoutStatus.FROZEN);
        frozen.setChargebackFrozen(true);
        when(coachPayoutRepository.findFundedByPaymentIntentForUpdate("pi_1")).thenReturn(List.of(frozen));
        BookingPayment card = new BookingPayment();
        card.setStripeCharged(new BigDecimal("50.00"));
        when(bookingPaymentRepository.findById(bookingA)).thenReturn(Optional.of(card));

        // 75.00 disputed: 50.00 is set against the credit, the other 25.00 against the 50.00 that paid for sessions.
        service.onDisputeClosed(dispute("lost", 7_500));

        assertThat(frozen.getNetAmount()).isEqualByComparingTo("22.50");
        assertThat(frozen.getStatus()).isEqualTo(CoachPayoutStatus.PENDING_RELEASE);
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
        // fix/payment-pack-integrity: a partial external refund only alerts — no pack is revoked.
        verify(packSessionService, never()).revokeForCharge(any(), any());
    }

    // ---- fix/payment-pack-integrity: a pack bought with the disputed charge ---------------------

    private void packCharge() {
        when(chargeDisputeRepository.findPackPurchaseIdsByPaymentIntent("pi_1")).thenReturn(List.of(UUID.randomUUID()));
    }

    @Test
    void created_onAPackCharge_freezesThePack() {
        packCharge();
        when(packSessionService.freezeForDispute("pi_1")).thenReturn(true);

        service.onDisputeCreated(dispute("needs_response", 10_000));

        verify(packSessionService).freezeForDispute("pi_1");
        assertThat(capturedAlert().getReason()).contains("pack=frozen");
    }

    @Test
    void closedLost_onAPackCharge_revokesTheRemainingSessions() {
        packCharge();
        when(chargeDisputeRepository.findByIdForUpdate("dp_1")).thenReturn(Optional.empty());
        when(chargeDisputeRepository.sumChargedByPaymentIntent("pi_1")).thenReturn(new BigDecimal("100.00"));
        when(packSessionService.revokeForCharge("pi_1", SessionPackPurchase.REVOKED_CHARGEBACK_LOST)).thenReturn(7);

        service.onDisputeClosed(dispute("lost", 10_000));

        verify(packSessionService).revokeForCharge("pi_1", SessionPackPurchase.REVOKED_CHARGEBACK_LOST);
        verify(packSessionService, never()).liftDisputeFreeze(any());
        assertThat(capturedAlert().getReason()).contains("packSessionsRevoked=7");
    }

    @Test
    void closedWon_onAPackCharge_liftsThePackFreeze_unlessAnotherDisputeIsOpen() {
        packCharge();
        when(chargeDisputeRepository.findByIdForUpdate("dp_1")).thenReturn(Optional.empty());

        service.onDisputeClosed(dispute("won", 10_000));

        verify(packSessionService).liftDisputeFreeze("pi_1");
        verify(packSessionService, never()).revokeForCharge(any(), any());
    }

    @Test
    void closedWon_anotherDisputeStillOpen_keepsThePackFrozen() {
        packCharge();
        when(chargeDisputeRepository.findByIdForUpdate("dp_1")).thenReturn(Optional.empty());
        when(chargeDisputeRepository.existsByPaymentIntentIdAndStatus("pi_1", ChargeDispute.STATUS_OPEN)).thenReturn(true);

        service.onDisputeClosed(dispute("won", 10_000));

        verify(packSessionService, never()).liftDisputeFreeze(any());
    }

    @Test
    void refunded_inFullOutsideTheApp_revokesThePack() {
        packCharge();
        Charge full = charge();
        full.setAmountRefunded(5_000L);
        when(packSessionService.revokeForCharge("pi_1", SessionPackPurchase.REVOKED_EXTERNAL_REFUND)).thenReturn(3);

        service.onChargeRefunded(full, eventWithKey(null));

        assertThat(capturedAlert().getReason()).isEqualTo("refunded 50.00 of 50.00 packSessionsRevoked=3");
    }
}
