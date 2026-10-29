package com.softropic.skillars.platform.payment.repo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * fix/payment-pack-refund-rounding: property-style checks of the pack settlement ledger
 * ({@link SessionPackPurchase#nextSettlementValue}). Every session of a pack settles once — paid to the coach
 * or refunded to the payer — or is never settled (unused at expiry: the platform's). For every pack size
 * n = 1..12, a set of awkward totals, and EVERY order of payouts and refunds of up to n sessions, the coach
 * gross + payer refunds of a fully-settled pack must equal what the pack was charged to the cent, and the
 * refunds of the sessions not consumed must equal amount charged minus the value consumed.
 */
class SessionPackSettlementPropertyTest {

    private static final BigDecimal COMMISSION_RATE = new BigDecimal("0.1500");

    /** Awkward totals: 10.00/3, 9.99/7, 0.01/2, 1000.00/3, single cents, and amounts below one cent a session. */
    private static final List<String> TOTALS = List.of(
        "10.00", "9.99", "0.01", "0.07", "0.11", "1000.00", "999.00", "200.00", "123.45", "1.00", "99999.99");

    enum Op { PAY, REFUND }

    static Stream<Arguments> packs() {
        return TOTALS.stream().flatMap(t -> IntStream.rangeClosed(1, 12).mapToObj(n -> Arguments.of(t, n)));
    }

    private static SessionPackPurchase pack(String amount, int sessions) {
        SessionPackPurchase p = new SessionPackPurchase();
        p.setAmountCharged(new BigDecimal(amount));
        p.setSessionCount(sessions);
        p.setRemainingSessions(sessions);
        p.setPricePerSession(new BigDecimal(amount).divide(BigDecimal.valueOf(sessions), 2, RoundingMode.HALF_UP));
        p.setCommissionRate(COMMISSION_RATE);
        return p;
    }

    /** Outcome of one simulated settlement sequence. */
    private record Outcome(BigDecimal coachGross, BigDecimal commission, BigDecimal coachNet, BigDecimal refunded,
                           List<BigDecimal> values) {
    }

    private static Outcome settle(SessionPackPurchase p, List<Op> ops) {
        BigDecimal amount = p.getAmountCharged();
        BigDecimal share = p.perSessionShare();
        BigDecimal coachGross = BigDecimal.ZERO;
        BigDecimal commission = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal refunded = BigDecimal.ZERO;
        List<BigDecimal> values = new ArrayList<>();
        for (Op op : ops) {
            BigDecimal v = p.nextSettlementValue();
            assertThat(v.signum()).as("settlement value is never negative").isGreaterThanOrEqualTo(0);
            assertThat(v).as("only the last session may exceed the rounded-down share").satisfiesAnyOf(
                x -> assertThat(x).isEqualByComparingTo(share),
                x -> assertThat(p.settledSessions() + 1).isEqualTo(p.getSessionCount()));
            values.add(v);
            if (op == Op.PAY) {
                p.recordPayoutAllocation(v);
                BigDecimal c = v.multiply(COMMISSION_RATE).setScale(2, RoundingMode.HALF_UP);
                coachGross = coachGross.add(v);
                commission = commission.add(c);
                net = net.add(v.subtract(c));
            } else {
                p.recordRefund(v);
                refunded = refunded.add(v);
            }
            assertThat(p.settledGross()).as("never settles more than was charged").isLessThanOrEqualTo(amount);
        }
        return new Outcome(coachGross, commission, net, refunded, values);
    }

    private static void assertInvariants(SessionPackPurchase p, List<Op> ops, Outcome o) {
        BigDecimal amount = p.getAmountCharged();
        int n = p.getSessionCount();
        BigDecimal share = p.perSessionShare();
        // Coach net + platform commission is exactly the coach gross allocated.
        assertThat(o.coachNet().add(o.commission())).isEqualByComparingTo(o.coachGross());
        assertThat(p.getPayoutGrossAllocated()).isEqualByComparingTo(o.coachGross());
        assertThat(p.getRefundGrossIssued()).isEqualByComparingTo(o.refunded());
        if (ops.size() == n) {
            // Fully settled: payouts (net + commission) + refunds reconcile to the purchase total exactly, and
            // the payer got back exactly what was charged minus what was consumed.
            assertThat(o.coachNet().add(o.commission()).add(o.refunded())).as("%s/%d %s", amount, n, ops)
                .isEqualByComparingTo(amount);
            assertThat(o.refunded()).isEqualByComparingTo(amount.subtract(o.coachGross()));
            assertThat(p.unsettledValue()).isEqualByComparingTo("0");
            assertThat(p.nextSettlementValue()).isEqualByComparingTo("0");
        } else {
            // Sessions never settled (unused at expiry) are the platform's: at least their shares, and with
            // them the rounding remainder.
            BigDecimal unsettled = p.unsettledValue();
            assertThat(unsettled).isEqualByComparingTo(amount.subtract(o.coachGross()).subtract(o.refunded()));
            assertThat(unsettled).isGreaterThanOrEqualTo(share.multiply(BigDecimal.valueOf(n - ops.size())));
        }
    }

    @ParameterizedTest(name = "{0} / {1} sessions")
    @MethodSource("packs")
    void everyOrderOfPayoutsAndRefunds_reconcilesToTheCent(String total, int n) {
        // Every sequence of k = 0..n settlements, each a payout or a refund: sum over k of 2^k (8191 at n = 12).
        for (int k = 0; k <= n; k++) {
            for (int mask = 0; mask < (1 << k); mask++) {
                List<Op> ops = new ArrayList<>(k);
                for (int i = 0; i < k; i++) {
                    ops.add(((mask >> i) & 1) == 1 ? Op.REFUND : Op.PAY);
                }
                SessionPackPurchase p = pack(total, n);
                Outcome o = settle(p, ops);
                assertInvariants(p, ops, o);
            }
        }
    }

    @ParameterizedTest(name = "{0} / {1} sessions")
    @MethodSource("packs")
    void refundingEveryRemainingSession_returnsChargedMinusConsumed(String total, int n) {
        for (int consumed = 0; consumed <= n; consumed++) {
            SessionPackPurchase p = pack(total, n);
            BigDecimal coach = BigDecimal.ZERO;
            for (int i = 0; i < consumed; i++) {
                BigDecimal v = p.nextSettlementValue();
                p.recordPayoutAllocation(v);
                coach = coach.add(v);
            }
            BigDecimal expected = p.unsettledValue();
            BigDecimal refunded = BigDecimal.ZERO;
            for (int i = consumed; i < n; i++) {
                BigDecimal v = p.nextSettlementValue();
                p.recordRefund(v);
                refunded = refunded.add(v);
            }
            assertThat(refunded).as("%s/%d after %d consumed", total, n, consumed)
                .isEqualByComparingTo(new BigDecimal(total).subtract(coach))
                .isEqualByComparingTo(expected);
        }
    }

    /**
     * Interleavings with partial refunds already issued and clawed back (an UPHOLD_COACH dispute un-settles a
     * refunded session, which is then paid to the coach): random but seeded, so a failure reproduces.
     */
    @ParameterizedTest(name = "{0} / {1} sessions")
    @MethodSource("packs")
    void refundsClawedBackAndRepaid_stillReconcile(String total, int n) {
        Random random = new Random(31L * n + total.hashCode());
        for (int run = 0; run < 300; run++) {
            SessionPackPurchase p = pack(total, n);
            List<BigDecimal> openRefunds = new ArrayList<>();
            BigDecimal coach = BigDecimal.ZERO;
            BigDecimal refunded = BigDecimal.ZERO;
            int guard = 0;
            while (p.settledSessions() < n && guard++ < 4 * n) {
                int choice = random.nextInt(3);
                if (choice == 2 && !openRefunds.isEmpty()) {
                    // Claw back an earlier refund and pay that session to the coach instead.
                    BigDecimal back = openRefunds.remove(random.nextInt(openRefunds.size()));
                    p.releaseRefund(back);
                    refunded = refunded.subtract(back);
                    BigDecimal v = p.nextSettlementValue();
                    p.recordPayoutAllocation(v);
                    coach = coach.add(v);
                } else if (choice == 1) {
                    BigDecimal v = p.nextSettlementValue();
                    p.recordRefund(v);
                    openRefunds.add(v);
                    refunded = refunded.add(v);
                } else {
                    BigDecimal v = p.nextSettlementValue();
                    p.recordPayoutAllocation(v);
                    coach = coach.add(v);
                }
                assertThat(p.settledGross()).isLessThanOrEqualTo(p.getAmountCharged());
                assertThat(p.settledGross()).isEqualByComparingTo(coach.add(refunded));
            }
            assertThat(p.settledSessions()).isEqualTo(n);
            assertThat(coach.add(refunded)).as("%s/%d run %d", total, n, run).isEqualByComparingTo(total);
        }
    }

    @Test
    void theOldRoundedDownRefundsLostCents_theLastSettlementNowTakesThem() {
        // 1000.00 / 3: three refunds used to be 333.33 x 3 = 999.99.
        SessionPackPurchase p = pack("1000.00", 3);
        List<BigDecimal> refunds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            BigDecimal v = p.nextSettlementValue();
            p.recordRefund(v);
            refunds.add(v);
        }
        assertThat(refunds).extracting(BigDecimal::toPlainString).containsExactly("333.33", "333.33", "333.34");

        // 9.99 / 7: shares of 1.42, the seventh takes 1.47 (6 cents rounded away).
        SessionPackPurchase q = pack("9.99", 7);
        Outcome o = settle(q, List.of(Op.PAY, Op.REFUND, Op.PAY, Op.PAY, Op.REFUND, Op.PAY, Op.REFUND));
        assertThat(o.values().get(6)).isEqualByComparingTo("1.47");
        assertThat(o.coachGross().add(o.refunded())).isEqualByComparingTo("9.99");

        // 0.01 / 2: the first session is worth nothing, the second the cent.
        SessionPackPurchase r = pack("0.01", 2);
        Outcome o2 = settle(r, List.of(Op.REFUND, Op.REFUND));
        assertThat(o2.values()).extracting(BigDecimal::toPlainString).containsExactly("0.00", "0.01");
    }

    @Test
    void legacyRowWithoutStampedAmount_fallsBackToThePricePerSession() {
        SessionPackPurchase p = new SessionPackPurchase();
        p.setPricePerSession(new BigDecimal("33.33"));
        assertThat(p.nextSettlementValue()).isEqualByComparingTo("33.33");
        assertThat(p.unsettledValue()).isNull();
    }
}
