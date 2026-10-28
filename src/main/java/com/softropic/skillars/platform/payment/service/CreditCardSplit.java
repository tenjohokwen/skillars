package com.softropic.skillars.platform.payment.service;

import java.math.BigDecimal;

/**
 * feat/coach-workflow-gaps A22: the single rule for how a booking amount is split between the payer's €
 * credit wallet and their card — "use as much wallet credit as covers the amount, charge the rest". Shared by
 * the batch and single-booking settlements in {@link PaymentLifecycleService} and the pre-submit
 * {@link BatchPaymentQuoteService} preview, so the figure a parent reviews cannot drift from the one they are
 * charged.
 */
public record CreditCardSplit(BigDecimal creditApplied, BigDecimal cardCharge) {

    /**
     * fix/payment-agreed-price-lock: a negative (or null) balance is clamped to zero — a negative
     * {@code creditApplied} used to inflate the card charge to amount + |balance| with no ledger entry
     * recording it. Also the single-booking split in {@code PaymentLifecycleService}.
     */
    public static CreditCardSplit of(BigDecimal walletBalance, BigDecimal amount) {
        BigDecimal usable = walletBalance == null || walletBalance.signum() < 0 ? BigDecimal.ZERO : walletBalance;
        BigDecimal creditToUse = usable.min(amount);
        return new CreditCardSplit(creditToUse, amount.subtract(creditToUse));
    }
}
