package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.payment.contract.PaymentGateway;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.payment.repo.ParentCreditLedgerRepository;
import com.softropic.skillars.platform.payment.repo.StripeCustomer;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Converts wallet credit back to the payer's card as a Stripe refund, minus the processing fee.
 *
 * <p>Deliberately NOT {@code @Transactional}: the Stripe refund is an external call and must not run
 * while a DB transaction (and the per-payer wallet lock) is held. The shape is
 * validate → {@link CreditWalletService#debitForCashOut} (its own transaction: locked balance check +
 * debit + a PENDING {@code cash_out_reconciliations} record) → refund → record COMPLETED; on a refund
 * Stripe REJECTED, compensating {@code CASH_OUT_REVERSAL} rows written together with closing the record
 * ({@link CashOutReconciliationService#reverseAfterRejection}). On an AMBIGUOUS refund outcome (network
 * failure / 5xx — the refund may have been executed) it retries under the same idempotency key, and if
 * still unknown leaves the debit in place and the record UNKNOWN (admin alert, automatic re-check)
 * rather than risk paying the payer twice.
 *
 * <p>fix/payment-money-integrity: the payer asks for {@code requestedAmount}; the wallet is debited
 * exactly that ({@code net} as {@code CASH_OUT_DEBIT} + {@code fee} as {@code STRIPE_FEE_DEBIT}) and
 * the card receives {@code net}. Previously the wallet was debited {@code requested + fee}, charging
 * the fee twice and overdrawing a wallet cashed out in full.
 *
 * <p>fix/audit3-wallet-cashout-integrity: one request may be refunded as several legs across the charges that
 * funded the credit ({@link #planLegs}); each leg is debited, refunded, re-checked and compensated as a cash-out
 * of its own. If some legs reach the card and others are rejected, the rejected legs' credit is restored and the
 * request fails with {@code payment.cashOutPartiallyRefunded}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CashOutService {

    private final CreditWalletService creditWalletService;
    private final StripeCustomerRepository stripeCustomerRepository;
    private final PaymentGateway paymentGateway;
    private final ConfigService configService;
    private final CashOutReconciliationService cashOutReconciliationService;
    private final ParentCreditLedgerRepository ledgerRepository;

    static final String REFUND_OUTCOME_UNKNOWN = "payment.refundOutcomeUnknown";
    /** Total refund attempts under one idempotency key when Stripe's answer is ambiguous. */
    static final int MAX_REFUND_ATTEMPTS = 3;
    /**
     * Funding charges a cash-out may be split across (one Stripe read each) before the latest charge takes the
     * rest. fix/audit3-wallet-cashout-integrity: 3 → 10, now that a cash-out spreads over several of them.
     */
    static final int MAX_REFUND_TARGET_CANDIDATES = 10;

    public void processCashOut(Long parentId, BigDecimal requestedAmount) {
        BigDecimal feeRate = new BigDecimal(configService.getString("payment.stripe.feeRate"));
        BigDecimal feeFixed = new BigDecimal(configService.getString("payment.stripe.feeFixed"));
        BigDecimal requested = requestedAmount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal feeAmount = requested.multiply(feeRate).add(feeFixed).setScale(2, RoundingMode.HALF_UP);
        BigDecimal netAmount = requested.subtract(feeAmount);
        if (netAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new PaymentGatewayException("payment.cashOutTooSmall");
        }

        // Early, unlocked balance check: a clear error without a Stripe round-trip. The authoritative
        // check is the locked one in debitForCashOut. fix/payment-wallet-dispute-integrity: only the
        // cashable pool can be cashed out; spend-only credit stays for bookings.
        CreditWalletService.WalletBalance balance = creditWalletService.getWalletBalance(parentId);
        if (balance.total().compareTo(requested) < 0) {
            throw new PaymentGatewayException("payment.insufficientCredit");
        }
        if (balance.cashable().compareTo(requested) < 0) {
            throw new PaymentGatewayException("payment.cashOutExceedsCashable");
        }

        String lastPaymentIntentId = stripeCustomerRepository.findById(parentId)
            .map(StripeCustomer::getLastPaymentIntentId)
            .orElse(null);

        // fix/audit3-wallet-cashout-integrity: Stripe refuses to refund more than a charge has left, so a cash-out
        // is split into legs across the charges that funded the credit (see planLegs). Not re-checked under the
        // wallet lock: a concurrent cash-out can still consume the headroom between this read and the refund, in
        // which case Stripe rejects that leg and its own compensation restores its credit.
        List<CreditWalletService.CashOutLeg> legs = planLegs(parentId, netAmount, feeAmount, lastPaymentIntentId);

        // The debits and their PENDING cash_out_reconciliations records commit together: from here on, a crash
        // or an unconfirmed refund always leaves a durable row (CashOutReconciliationService re-checks it).
        creditWalletService.debitForCashOut(parentId, legs);

        // Each leg is refunded on its own and closes its own record, so one leg's rejection restores only that
        // leg's credit (never a sibling's that reached the card) and one leg's unknown outcome leaves only that
        // leg's debit standing for the re-check job.
        boolean anyUnknown = false;
        boolean anyRejected = false;
        boolean anyCompleted = false;
        for (CreditWalletService.CashOutLeg leg : legs) {
            switch (refundLeg(parentId, leg)) {
                case COMPLETED -> anyCompleted = true;
                case REJECTED -> anyRejected = true;
                case UNKNOWN -> anyUnknown = true;
            }
        }
        if (anyUnknown) {
            throw new PaymentGatewayException(REFUND_OUTCOME_UNKNOWN);
        }
        if (anyRejected) {
            throw new PaymentGatewayException(anyCompleted ? "payment.cashOutPartiallyRefunded" : "payment.refundFailed");
        }
    }

    private enum LegOutcome { COMPLETED, REJECTED, UNKNOWN }

    /**
     * One leg's refund. Per-leg idempotency key: unique per leg ({@code cashout-{legCashOutId}}), so two
     * cash-outs (or two legs) against the same PaymentIntent are two refunds at Stripe — never a replay of the
     * first. The same key makes the retries below (and the re-check job's) safe: Stripe replays a refund it
     * already executed.
     */
    private LegOutcome refundLeg(Long parentId, CreditWalletService.CashOutLeg leg) {
        UUID cashOutId = leg.cashOutId();
        String paymentIntentId = leg.paymentIntentId();
        BigDecimal netAmount = leg.netAmount();
        BigDecimal feeAmount = leg.feeAmount();
        String idempotencyKey = "cashout-" + cashOutId;
        for (int attempt = 1; ; attempt++) {
            try {
                paymentGateway.refund(paymentIntentId, netAmount, idempotencyKey);
            } catch (PaymentGatewayException e) {
                if (REFUND_OUTCOME_UNKNOWN.equals(e.getErrorCode())) {
                    if (attempt < MAX_REFUND_ATTEMPTS) {
                        continue;
                    }
                    // The refund may have reached the card. Restoring the credit now could pay the
                    // payer twice, so the debit stands; the record goes UNKNOWN (admin alert) and the
                    // re-check job retries it under the same key.
                    log.error("Cash-out refund outcome UNKNOWN after {} attempts — credit NOT restored, "
                        + "reconciliation record UNKNOWN: parentId={} cashOutId={} intentId={} net={} fee={} "
                        + "stripeIdempotencyKey={}", attempt, parentId, cashOutId, paymentIntentId,
                        netAmount, feeAmount, idempotencyKey);
                    recordUnknown(cashOutId, e.getErrorCode());
                    return LegOutcome.UNKNOWN;
                }
                // Stripe rejected the refund (4xx): no money moved, so restore the credit — together with
                // closing the record, so neither this request nor the re-check job can restore it twice.
                log.error("Cash-out refund rejected for parentId={} cashOutId={}, compensating credit", parentId, cashOutId);
                try {
                    cashOutReconciliationService.reverseAfterRejection(cashOutId, e.getErrorCode());
                } catch (RuntimeException reversalFailure) {
                    // The record stays PENDING and the re-check job replays the (rejected) refund and restores it.
                    log.error("Cash-out reversal FAILED after a rejected refund — wallet debited, card not refunded; "
                        + "the PENDING reconciliation record is re-checked automatically: parentId={} cashOutId={} "
                        + "net={} fee={}", parentId, cashOutId, netAmount, feeAmount, reversalFailure);
                    return LegOutcome.UNKNOWN;
                }
                return LegOutcome.REJECTED;
            } catch (RuntimeException e) {
                // Anything that is not a classified gateway result: the outcome is unknown, so the
                // same rule applies — never restore credit for a refund that may have happened.
                log.error("Cash-out refund threw unexpectedly — credit NOT restored, reconciliation record UNKNOWN: "
                    + "parentId={} cashOutId={} intentId={} net={} fee={} stripeIdempotencyKey={}",
                    parentId, cashOutId, paymentIntentId, netAmount, feeAmount, idempotencyKey, e);
                recordUnknown(cashOutId, String.valueOf(e));
                return LegOutcome.UNKNOWN;
            }
            try {
                cashOutReconciliationService.markCompleted(cashOutId);
            } catch (RuntimeException e) {
                // The refund went through; only the bookkeeping failed. The record stays PENDING and the
                // re-check job confirms it by replaying the refund under the same key (a no-op at Stripe).
                log.warn("Cash-out refund succeeded but its reconciliation record could not be closed — the "
                    + "re-check job will confirm it: cashOutId={}", cashOutId, e);
            }
            return LegOutcome.COMPLETED;
        }
    }

    /**
     * fix/audit3-wallet-cashout-integrity: where a cash-out's {@code netAmount} is refunded. Refunding the charge
     * a credit came from lowers that charge's disputable amount; a refund onto an unrelated charge left the
     * funding charge fully disputable, so a chargeback on it after the cash-out paid the payer twice. It used to
     * pick ONE funding charge that could take the whole amount, and otherwise fell back to the latest charge for
     * all of it. Now the amount is split across the funding charges (most recently credited first), each leg
     * capped at that charge's refundable amount; only the remainder no funding charge can take goes onto the
     * payer's latest charge (capped at what it has left after any leg already on it) — by then every funding
     * charge read has been refunded down to zero headroom, so it can no longer be disputed for that money.
     *
     * <p>Fails closed: when a funding charge cannot be read from Stripe the cash-out is refused
     * ({@code payment.cashOutTargetUnavailable}, nothing debited) rather than routed past it onto another
     * charge — the payer can retry later. The processing fee (a wallet-only debit) is split across the legs in
     * proportion to their net, the last leg taking the rounding remainder.
     */
    List<CreditWalletService.CashOutLeg> planLegs(Long parentId, BigDecimal netAmount, BigDecimal feeAmount,
                                                  String lastPaymentIntentId) {
        Map<String, BigDecimal> plan = new LinkedHashMap<>();
        BigDecimal remaining = netAmount;
        for (String candidate : ledgerRepository.findCashOutRefundTargets(parentId, MAX_REFUND_TARGET_CANDIDATES)) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal refundable;
            try {
                refundable = paymentGateway.getRefundableAmount(candidate);
            } catch (RuntimeException e) {
                log.warn("Cash-out refund target {} unreadable for parentId={} — cash-out refused", candidate, parentId, e);
                throw new PaymentGatewayException("payment.cashOutTargetUnavailable");
            }
            BigDecimal take = remaining.min(refundable == null ? BigDecimal.ZERO : refundable.max(BigDecimal.ZERO));
            if (take.signum() > 0) {
                plan.merge(candidate, take, BigDecimal::add);
                remaining = remaining.subtract(take);
            }
        }
        if (remaining.signum() > 0) {
            if (lastPaymentIntentId == null) {
                throw new PaymentGatewayException(plan.isEmpty() ? "payment.noPaymentMethod"
                    : "payment.cashOutExceedsRefundable");
            }
            // Wallet credit can come from many earlier charges, so cap explicitly with a dedicated error rather
            // than letting Stripe reject it as a generic refund failure.
            BigDecimal headroom = paymentGateway.getRefundableAmount(lastPaymentIntentId)
                .subtract(plan.getOrDefault(lastPaymentIntentId, BigDecimal.ZERO));
            if (remaining.compareTo(headroom) > 0) {
                throw new PaymentGatewayException("payment.cashOutExceedsRefundable");
            }
            plan.merge(lastPaymentIntentId, remaining, BigDecimal::add);
        }
        List<CreditWalletService.CashOutLeg> legs = new ArrayList<>();
        BigDecimal feeLeft = feeAmount;
        int i = 0;
        for (Map.Entry<String, BigDecimal> e : plan.entrySet()) {
            boolean last = ++i == plan.size();
            BigDecimal legFee = last ? feeLeft
                : feeAmount.multiply(e.getValue()).divide(netAmount, 2, RoundingMode.DOWN);
            feeLeft = feeLeft.subtract(legFee);
            legs.add(new CreditWalletService.CashOutLeg(UUID.randomUUID(), e.getKey(), e.getValue(), legFee));
        }
        return legs;
    }

    /** Best effort: if even this write fails, the record stays PENDING and the re-check job escalates it. */
    private void recordUnknown(UUID cashOutId, String error) {
        try {
            cashOutReconciliationService.markUnknown(cashOutId, error);
        } catch (RuntimeException e) {
            log.error("Could not mark cash-out {} UNKNOWN — the re-check job will pick up its PENDING record",
                cashOutId, e);
        }
    }
}
