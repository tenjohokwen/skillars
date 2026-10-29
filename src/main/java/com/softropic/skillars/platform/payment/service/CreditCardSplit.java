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

    /**
     * fix/payment-charge-idempotency (L3): as {@link #of(BigDecimal, BigDecimal)}, but never leaves a card
     * charge Stripe would reject as {@code amount_too_small} (e.g. EUR 0.20 after EUR 49.80 of credit). When
     * the card part would be positive but below {@code minimumCardCharge}, the card takes the minimum and the
     * credit part shrinks by the difference — the payer pays the same total and keeps that bit of credit. An
     * amount itself below the minimum cannot be card-charged at all and is left as {@code of} splits it.
     * A null / non-positive minimum disables the adjustment.
     */
    public static CreditCardSplit of(BigDecimal walletBalance, BigDecimal amount, BigDecimal minimumCardCharge) {
        CreditCardSplit split = of(walletBalance, amount);
        if (minimumCardCharge == null || minimumCardCharge.signum() <= 0) {
            return split;
        }
        BigDecimal card = split.cardCharge();
        if (card.signum() > 0 && card.compareTo(minimumCardCharge) < 0 && amount.compareTo(minimumCardCharge) >= 0) {
            return new CreditCardSplit(amount.subtract(minimumCardCharge), minimumCardCharge);
        }
        return split;
    }
}
