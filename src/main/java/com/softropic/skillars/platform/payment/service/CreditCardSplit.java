package com.softropic.skillars.platform.payment.service;

import java.math.BigDecimal;

/**
 * feat/coach-workflow-gaps A22: the single rule for how a booking amount is split between the payer's €
 * credit wallet and their card — "use as much wallet credit as covers the amount, charge the rest". Shared by
 * the batch settlement in {@link PaymentLifecycleService#onBatchBookingAccepted} and the pre-submit
 * {@link BatchPaymentQuoteService} preview, so the figure a parent reviews cannot drift from the one they are
 * charged.
 */
public record CreditCardSplit(BigDecimal creditApplied, BigDecimal cardCharge) {

    public static CreditCardSplit of(BigDecimal walletBalance, BigDecimal amount) {
        BigDecimal creditToUse = walletBalance.min(amount);
        return new CreditCardSplit(creditToUse, amount.subtract(creditToUse));
    }
}
