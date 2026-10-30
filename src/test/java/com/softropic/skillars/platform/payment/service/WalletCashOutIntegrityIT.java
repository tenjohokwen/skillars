package com.softropic.skillars.platform.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.payment.BasePaymentIT;
import com.softropic.skillars.platform.payment.contract.CoachPayoutStatus;
import com.softropic.skillars.platform.payment.contract.CreditLedgerEntryType;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.payment.repo.CashOutReconciliation;
import com.softropic.skillars.platform.payment.repo.CashOutReconciliationRepository;
import com.softropic.skillars.platform.payment.repo.CoachPayout;
import com.softropic.skillars.platform.payment.repo.CoachPayoutRepository;
import com.softropic.skillars.platform.payment.service.RefundOutboxSupport.BookingRefundPayload;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

/**
 * fix/audit3-wallet-cashout-integrity, against the real ledger:
 * <ol>
 *   <li>spend-only credit spent on a booking comes back spend-only when the booking is refunded (or credited
 *       by an admin dispute) — it used to come back cashable and could be cashed out to a card;</li>
 *   <li>a cash-out is split across the charges that funded the credit, each leg its own refund, record and
 *       compensation;</li>
 *   <li>a parent-side restore that reaches a pack which expired meanwhile is refunded as spend-only credit;</li>
 *   <li>a new coach's payout is held {@code payment.payout.newCoach.holdHours}.</li>
 * </ol>
 */
class WalletCashOutIntegrityIT extends BasePaymentIT {

    private static final long PARENT_ID = 98731L;
    private static final long PLAYER_ID = 98732L;
    private static final long COACH_USER_ID = 98733L;

    @Autowired CreditWalletService creditWalletService;
    @Autowired CreditWalletRefundOutboxHandler refundHandler;
    @Autowired DisputeCreditService disputeCreditService;
    @Autowired CashOutService cashOutService;
    @Autowired CashOutReconciliationRepository reconciliationRepository;
    @Autowired PackSessionService packSessionService;
    @Autowired CoachPayoutOutboxSupport coachPayoutOutboxSupport;
    @Autowired CoachPayoutRepository coachPayoutRepository;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void seed() {
        insertTestParent(PARENT_ID, "wallet_integrity_parent@test.com");
        transactionTemplate.execute(s -> jdbcTemplate.update(
            "INSERT INTO payment.stripe_customers (parent_id, stripe_customer_id, last_payment_intent_id) "
                + "VALUES (?, 'cus_integrity', 'pi_latest') ON CONFLICT (parent_id) DO NOTHING", PARENT_ID));
    }

    private void refund(BookingRefundPayload payload) {
        transactionTemplate.execute(s -> {
            try {
                refundHandler.handle(objectMapper.writeValueAsString(payload));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
    }

    /**
     * The exploit: an expired-pack refund (€40 spend-only) → a €40 session paid from the wallet → cancelled
     * >24h → the €40 refund used to be cashable and was cashed out to a card. Now it is spend-only again.
     */
    @Test
    void spendOnlyCreditSpentOnABooking_comesBackSpendOnlyOnCancellation_andCannotBeCashedOut() {
        UUID booking = UUID.randomUUID();
        transactionTemplate.execute(s -> {
            creditWalletService.writeLedgerEntry(PARENT_ID, new BigDecimal("40.00"), CreditLedgerEntryType.BOOKING_REFUND,
                UUID.randomUUID(), "expired pack refund", null, null, new BigDecimal("40.00"));
            creditWalletService.debitForBooking(PARENT_ID, new BigDecimal("40.00"), booking, "Session booking deduction");
            return null;
        });
        assertThat(creditWalletService.getWalletBalance(PARENT_ID).total()).isEqualByComparingTo("0.00");

        refund(new BookingRefundPayload(PARENT_ID, new BigDecimal("40.00"), booking, "Parent cancellation >24h"));

        CreditWalletService.WalletBalance after = creditWalletService.getWalletBalance(PARENT_ID);
        assertThat(after.total()).isEqualByComparingTo("40.00");
        assertThat(after.spendOnly()).isEqualByComparingTo("40.00");
        assertThat(after.cashable()).isEqualByComparingTo("0.00");
        assertThatThrownBy(() -> cashOutService.processCashOut(PARENT_ID, new BigDecimal("40.00")))
            .isInstanceOf(PaymentGatewayException.class)
            .hasMessageContaining("payment.cashOutExceedsCashable");

        // An admin dispute credit on the same booking cannot launder it either — the spend-only share was
        // already given back by the refund, so a further credit is cashable only beyond it.
        UUID booking2 = UUID.randomUUID();
        transactionTemplate.execute(s -> {
            creditWalletService.debitForBooking(PARENT_ID, new BigDecimal("40.00"), booking2, "Session booking deduction");
            disputeCreditService.credit(PARENT_ID, booking2, UUID.randomUUID(), new BigDecimal("30.00"), "Dispute credit");
            return null;
        });
        CreditWalletService.WalletBalance afterDispute = creditWalletService.getWalletBalance(PARENT_ID);
        assertThat(afterDispute.total()).isEqualByComparingTo("30.00");
        assertThat(afterDispute.cashable()).isEqualByComparingTo("0.00");
    }

    /** A booking paid part spend-only, part cashable credit: only the spend-only share comes back spend-only. */
    @Test
    void mixedBooking_refundSplitsBackIntoItsPools() {
        UUID booking = UUID.randomUUID();
        transactionTemplate.execute(s -> {
            creditWalletService.writeLedgerEntry(PARENT_ID, new BigDecimal("15.00"), CreditLedgerEntryType.BOOKING_REFUND,
                UUID.randomUUID(), "expired pack refund", null, null, new BigDecimal("15.00"));
            creditWalletService.writeLedgerEntry(PARENT_ID, new BigDecimal("35.00"), CreditLedgerEntryType.BOOKING_REFUND,
                UUID.randomUUID(), "card booking refund");
            creditWalletService.debitForBooking(PARENT_ID, new BigDecimal("50.00"), booking, "Session booking deduction");
            return null;
        });

        refund(new BookingRefundPayload(PARENT_ID, new BigDecimal("50.00"), booking, "Coach cancellation — full refund"));
        // A re-drive is still a no-op.
        refund(new BookingRefundPayload(PARENT_ID, new BigDecimal("50.00"), booking, "Coach cancellation — full refund"));

        CreditWalletService.WalletBalance after = creditWalletService.getWalletBalance(PARENT_ID);
        assertThat(after.total()).isEqualByComparingTo("50.00");
        assertThat(after.spendOnly()).isEqualByComparingTo("15.00");
        assertThat(after.cashable()).isEqualByComparingTo("35.00");
    }

    private void seedFundedCredit(String pi, String amount) {
        transactionTemplate.execute(s -> {
            creditWalletService.writeLedgerEntry(PARENT_ID, new BigDecimal(amount), CreditLedgerEntryType.BOOKING_REFUND,
                UUID.randomUUID(), "refund funded by " + pi, pi, new BigDecimal(amount));
            return null;
        });
    }

    private List<CashOutReconciliation> myCashOuts() {
        return reconciliationRepository.findAll().stream()
            .filter(r -> r.getParentId().equals(PARENT_ID))
            .sorted(Comparator.comparing(CashOutReconciliation::getNetAmount).reversed())
            .toList();
    }

    /**
     * Neither funding charge could take the whole cash-out, so it used to go onto the latest, unrelated charge —
     * leaving both funding charges fully disputable. It is now split across them, one refund leg each.
     */
    @Test
    void cashOut_isSplitAcrossTheFundingCharges_eachLegItsOwnRecord() {
        seedFundedCredit("pi_fund_a", "60.00");
        seedFundedCredit("pi_fund_b", "50.00");
        doReturn(new BigDecimal("60.00")).when(paymentGateway).getRefundableAmount("pi_fund_a");
        doReturn(new BigDecimal("50.00")).when(paymentGateway).getRefundableAmount("pi_fund_b");

        cashOutService.processCashOut(PARENT_ID, new BigDecimal("100.00"));

        List<CashOutReconciliation> legs = myCashOuts();
        assertThat(legs).hasSize(2).allMatch(r -> CashOutReconciliation.COMPLETED.equals(r.getStatus()));
        assertThat(legs).extracting(CashOutReconciliation::getPaymentIntentId)
            .containsExactlyInAnyOrder("pi_fund_a", "pi_fund_b");
        assertThat(legs).noneMatch(r -> "pi_latest".equals(r.getPaymentIntentId()));
        BigDecimal requested = legs.stream().map(r -> r.getNetAmount().add(r.getFeeAmount()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(requested).isEqualByComparingTo("100.00");
        assertThat(creditWalletService.getBalance(PARENT_ID)).isEqualByComparingTo("10.00");
    }

    /** One leg rejected by Stripe: only that leg's net + fee is restored; the leg that reached the card stays. */
    @Test
    void cashOut_oneLegRejected_restoresOnlyThatLeg() {
        seedFundedCredit("pi_fund_c", "60.00");
        seedFundedCredit("pi_fund_d", "50.00");
        doReturn(new BigDecimal("60.00")).when(paymentGateway).getRefundableAmount("pi_fund_c");
        doReturn(new BigDecimal("50.00")).when(paymentGateway).getRefundableAmount("pi_fund_d");
        doThrow(new PaymentGatewayException("payment.refundFailed"))
            .when(paymentGateway).refund(eq("pi_fund_d"), any(), any());

        assertThatThrownBy(() -> cashOutService.processCashOut(PARENT_ID, new BigDecimal("100.00")))
            .hasMessageContaining("payment.cashOutPartiallyRefunded");

        CashOutReconciliation reached = myCashOuts().stream()
            .filter(r -> "pi_fund_c".equals(r.getPaymentIntentId())).findFirst().orElseThrow();
        CashOutReconciliation rejected = myCashOuts().stream()
            .filter(r -> "pi_fund_d".equals(r.getPaymentIntentId())).findFirst().orElseThrow();
        assertThat(reached.getStatus()).isEqualTo(CashOutReconciliation.COMPLETED);
        assertThat(rejected.getStatus()).isEqualTo(CashOutReconciliation.REVERSED);
        // 110 − (the completed leg's net + fee); the rejected leg's debit is fully restored, exactly once.
        assertThat(creditWalletService.getBalance(PARENT_ID))
            .isEqualByComparingTo(new BigDecimal("110.00").subtract(reached.getNetAmount()).subtract(reached.getFeeAmount()));
    }

    /**
     * A parent's >24h cancel of a pack booking restores the session — but if the pack expired before the restore
     * ran, the session used to be restored to a worthless pack. It is refunded as spend-only credit.
     */
    @Test
    void parentCancelRestore_reachingAnExpiredPack_isRefundedSpendOnly() {
        UUID coachId = insertTestCoach(COACH_USER_ID, "wallet_integrity_coach@test.com", "Integrity Coach");
        insertTestPlayer(PLAYER_ID, PARENT_ID);
        UUID tierId = UUID.randomUUID();
        UUID packId = UUID.randomUUID();
        UUID booking = UUID.randomUUID();
        transactionTemplate.execute(s -> {
            jdbcTemplate.update("INSERT INTO payment.session_pack_tiers (pack_tier_id, coach_id, label, session_count, "
                + "total_price, price_per_session, is_active, version, created_at) "
                + "VALUES (?, ?, '5-Pack', 5, 200.00, 40.00, true, 0, now())", tierId, coachId);
            jdbcTemplate.update("INSERT INTO payment.session_pack_purchases (purchase_id, parent_id, player_id, coach_id, "
                + "pack_tier_id, price_per_session, remaining_sessions, expires_at, version, created_at) "
                + "VALUES (?, ?, ?, ?, ?, 40.00, 2, ?, 0, now())",
                packId, PARENT_ID, PLAYER_ID, coachId, tierId, Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)));
            return null;
        });

        transactionTemplate.execute(s -> {
            packSessionService.restoreSessionForBooking(packId, booking, "PARENT_CANCELLATION");
            return null;
        });

        CreditWalletService.WalletBalance after = creditWalletService.getWalletBalance(PARENT_ID);
        assertThat(after.total()).isEqualByComparingTo("40.00");
        assertThat(after.spendOnly()).isEqualByComparingTo("40.00");
        assertThat(jdbcTemplate.queryForObject("SELECT remaining_sessions FROM payment.session_pack_purchases "
            + "WHERE purchase_id = ?", Integer.class, packId)).isEqualTo(2);
    }

    @Autowired com.softropic.skillars.platform.config.service.ConfigService configService;

    private void releasedPayout(UUID coachId, String status, String net, String reversed) {
        jdbcTemplate.update("INSERT INTO payment.coach_payouts (booking_id, coach_id, gross_amount, commission_amount, "
                + "net_amount, reversed_amount, currency, status, release_after, released_at, attempts, created_at, "
                + "updated_at) VALUES (?, ?, ?::numeric, 0, ?::numeric, ?::numeric, 'eur', ?, now(), now(), 0, now(), now())",
            UUID.randomUUID(), coachId, net, net, reversed, status);
    }

    /**
     * Review fix: only RELEASED payouts the coach kept at least payment.payout.newCoach.minReleasedNet (20) of count
     * toward the threshold — a reversed payout, a tiny one, or one reversed below the minimum does not.
     */
    @Test
    void newCoachThreshold_countsOnlyReleasedPayoutsOfAtLeastTheMinimumNet() {
        UUID coachId = UUID.randomUUID();
        transactionTemplate.execute(s -> {
            releasedPayout(coachId, "RELEASED", "45.00", "0.00");   // counts
            releasedPayout(coachId, "RELEASED", "20.00", "0.00");   // counts (at the minimum)
            releasedPayout(coachId, "RELEASED", "5.00", "0.00");    // tiny
            releasedPayout(coachId, "RELEASED", "45.00", "30.00");  // kept 15
            releasedPayout(coachId, "REVERSED", "45.00", "45.00");  // reversed
            return null;
        });

        CoachPayoutOutboxSupport.PayoutHold hold =
            CoachPayoutOutboxSupport.resolveHold(configService, coachPayoutRepository, coachId);

        assertThat(hold.releasedCount()).isEqualTo(2L);
        assertThat(hold.newCoach()).isTrue();
        assertThat(hold.newCoachPayoutsRemaining()).isEqualTo(3L);
        assertThat(hold.minReleasedNet()).isEqualTo(20L);
    }

    /** A coach with no payout released yet is held 168h (the default), not the normal 48h. */
    @Test
    void newCoach_payoutIsHeldTheLongerHold() {
        UUID coachId = UUID.randomUUID();
        UUID booking = UUID.randomUUID();
        transactionTemplate.execute(s -> {
            jdbcTemplate.update("INSERT INTO payment.booking_payments (booking_id, credit_debited, stripe_charged, status, "
                + "captured_at, commission_rate) VALUES (?, 0, 50.00, 'CAPTURED', now(), 0.10)", booking);
            coachPayoutOutboxSupport.enqueuePayout(booking, coachId);
            return null;
        });

        CoachPayout row = coachPayoutRepository.findById(booking).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(CoachPayoutStatus.PENDING_RELEASE);
        assertThat(row.getReleaseAfter()).isAfter(Instant.now().plus(167, ChronoUnit.HOURS));
    }
}
