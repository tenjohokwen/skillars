package com.softropic.skillars.platform.payment.contract;

import java.math.BigDecimal;

/**
 * feat/coach-workflow-gaps A22: the pre-submit preview of what a multi-session (batch) request will cost —
 * per-session price, total, and the € wallet credit / card split. Computed with the same price source and
 * the same {@code CreditCardSplit} rule the batch settlement uses on acceptance. It is a preview, not a
 * reservation: price and wallet balance are read again when the coach accepts.
 *
 * <p>Session-pack credits are never applied to a batch (batch bookings carry no
 * {@code sessionPackPurchaseId}; settlement is wallet + card only), so they do not appear here.
 */
public record BatchPaymentQuoteDto(
    BigDecimal perSessionPrice,
    int sessionCount,
    BigDecimal total,
    BigDecimal walletBalance,
    BigDecimal creditApplied,
    BigDecimal cardCharge,
    String currency
) {}
