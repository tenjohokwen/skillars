package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.payment.repo.StripeRefundFailure;
import com.softropic.skillars.platform.payment.repo.StripeRefundFailureRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * skillars-deferred-99 AC1: persists a durable record when a pack-purchase compensating refund
 * cannot be issued.
 *
 * <p>{@link #recordRefundFailure} runs in its own {@code REQUIRES_NEW} transaction. That is
 * deliberate: {@code SessionPackPaymentService.purchasePack} is not {@code @Transactional} (it must
 * not hold a DB connection across the Stripe call), so this write needs its own boundary — and if
 * <em>this</em> write fails, the exception propagates so the caller wraps it as
 * {@code payment.lifecycleFailure} rather than silently dropping the reconciliation record, which is
 * the whole failure this class exists to prevent.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundReconciliationService {

    private final StripeRefundFailureRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRefundFailure(String paymentIntentId, BigDecimal amount, Long parentId,
                                    UUID packTierId, String error) {
        StripeRefundFailure row = repository.findByPaymentIntentId(paymentIntentId)
            .map(existing -> {
                existing.setAttempts(existing.getAttempts() + 1);
                existing.setError(error);
                return existing;
            })
            .orElseGet(() -> new StripeRefundFailure(paymentIntentId, amount, parentId, packTierId, error));
        repository.save(row);
        log.error("Recorded unrecoverable pack-purchase refund failure for reconciliation: "
            + "intentId={} amount={} parentId={} packTierId={} attempts={}",
            paymentIntentId, amount, parentId, packTierId, row.getAttempts());
    }
}
