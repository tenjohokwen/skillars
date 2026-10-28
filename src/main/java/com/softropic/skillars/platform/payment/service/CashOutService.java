package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.payment.contract.PaymentGateway;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.payment.repo.StripeCustomer;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * Converts wallet credit back to the payer's card as a Stripe refund, minus the processing fee.
 *
 * <p>Deliberately NOT {@code @Transactional}: the Stripe refund is an external call and must not run
 * while a DB transaction (and the per-payer wallet lock) is held. The shape is
 * validate → {@link CreditWalletService#debitForCashOut} (its own transaction, locked balance check +
 * debit) → refund → on a refund Stripe REJECTED, compensating {@code CASH_OUT_REVERSAL} rows
 * ({@link CreditWalletService#reverseCashOut}, one transaction). On an AMBIGUOUS refund outcome
 * (network failure / 5xx — the refund may have been executed) it retries under the same idempotency
 * key, and if still unknown leaves the debit in place for manual reconciliation rather than risk
 * paying the payer twice.
 *
 * <p>fix/payment-money-integrity: the payer asks for {@code requestedAmount}; the wallet is debited
 * exactly that ({@code net} as {@code CASH_OUT_DEBIT} + {@code fee} as {@code STRIPE_FEE_DEBIT}) and
 * the card receives {@code net}. Previously the wallet was debited {@code requested + fee}, charging
 * the fee twice and overdrawing a wallet cashed out in full.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CashOutService {

    private final CreditWalletService creditWalletService;
    private final StripeCustomerRepository stripeCustomerRepository;
    private final PaymentGateway paymentGateway;
    private final ConfigService configService;

    static final String REFUND_OUTCOME_UNKNOWN = "payment.refundOutcomeUnknown";
    /** Total refund attempts under one idempotency key when Stripe's answer is ambiguous. */
    static final int MAX_REFUND_ATTEMPTS = 3;

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
        // check is the locked one in debitForCashOut.
        if (creditWalletService.getBalance(parentId).compareTo(requested) < 0) {
            throw new PaymentGatewayException("payment.insufficientCredit");
        }

        String paymentIntentId = stripeCustomerRepository.findById(parentId)
            .map(StripeCustomer::getLastPaymentIntentId)
            .orElse(null);
        if (paymentIntentId == null) {
            throw new PaymentGatewayException("payment.noPaymentMethod");
        }

        // A cash-out refunds onto ONE PaymentIntent (the payer's most recent card charge), and Stripe
        // refuses to refund more than that charge has left. Wallet credit can come from many earlier
        // charges, so cap explicitly with a dedicated error rather than letting Stripe reject it as a
        // generic refund failure. Multi-PaymentIntent split refunds are not supported (PR design note).
        // Not re-checked under the wallet lock: a concurrent cash-out can still consume the headroom
        // between this read and the refund, in which case Stripe rejects the refund and the
        // compensation below restores the credit.
        BigDecimal refundable = paymentGateway.getRefundableAmount(paymentIntentId);
        if (netAmount.compareTo(refundable) > 0) {
            throw new PaymentGatewayException("payment.cashOutExceedsRefundable");
        }

        UUID cashOutId = UUID.randomUUID();
        creditWalletService.debitForCashOut(parentId, cashOutId, netAmount, feeAmount);

        // Per-cash-out idempotency key: unique per logical cash-out, so two cash-outs against the
        // same PaymentIntent are two refunds at Stripe — never a replay of the first. The same key
        // makes the retries below safe: Stripe replays a refund it already executed.
        String idempotencyKey = "cashout-" + cashOutId;
        for (int attempt = 1; ; attempt++) {
            try {
                paymentGateway.refund(paymentIntentId, netAmount, idempotencyKey);
                return;
            } catch (PaymentGatewayException e) {
                if (REFUND_OUTCOME_UNKNOWN.equals(e.getErrorCode())) {
                    if (attempt < MAX_REFUND_ATTEMPTS) {
                        continue;
                    }
                    // The refund may have reached the card. Restoring the credit now could pay the
                    // payer twice, so the debit stands and the cash-out is left for reconciliation
                    // against Stripe by its idempotency key.
                    log.error("Cash-out refund outcome UNKNOWN after {} attempts — MANUAL RECONCILIATION "
                        + "REQUIRED (credit NOT restored): parentId={} cashOutId={} intentId={} net={} fee={} "
                        + "stripeIdempotencyKey={}", attempt, parentId, cashOutId, paymentIntentId,
                        netAmount, feeAmount, idempotencyKey);
                    throw e;
                }
                // Stripe rejected the refund (4xx): no money moved, so restore the credit.
                log.error("Cash-out refund rejected for parentId={} cashOutId={}, compensating credit", parentId, cashOutId);
                try {
                    creditWalletService.reverseCashOut(parentId, cashOutId, netAmount, feeAmount);
                } catch (RuntimeException reversalFailure) {
                    log.error("Cash-out reversal FAILED after a rejected refund — MANUAL RECONCILIATION "
                        + "REQUIRED (wallet debited, card not refunded): parentId={} cashOutId={} net={} fee={}",
                        parentId, cashOutId, netAmount, feeAmount, reversalFailure);
                    throw reversalFailure;
                }
                throw new PaymentGatewayException("payment.refundFailed");
            } catch (RuntimeException e) {
                // Anything that is not a classified gateway result: the outcome is unknown, so the
                // same rule applies — never restore credit for a refund that may have happened.
                log.error("Cash-out refund threw unexpectedly — MANUAL RECONCILIATION REQUIRED (credit NOT "
                    + "restored): parentId={} cashOutId={} intentId={} net={} fee={} stripeIdempotencyKey={}",
                    parentId, cashOutId, paymentIntentId, netAmount, feeAmount, idempotencyKey, e);
                throw new PaymentGatewayException(REFUND_OUTCOME_UNKNOWN);
            }
        }
    }
}
