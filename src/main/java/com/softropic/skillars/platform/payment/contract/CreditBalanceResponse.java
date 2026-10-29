package com.softropic.skillars.platform.payment.contract;

import java.math.BigDecimal;

/**
 * The payer's wallet. {@code balance} is everything bookings can spend. fix/payment-wallet-dispute-integrity:
 * {@code cashableBalance} is the part that can be cashed out to the card and {@code spendOnlyBalance} the part
 * usable for bookings only (an expired-pack parent-cancel refund); they add up to {@code balance}.
 */
public record CreditBalanceResponse(BigDecimal balance, String currency, BigDecimal cashableBalance,
                                    BigDecimal spendOnlyBalance) {

    public CreditBalanceResponse(BigDecimal balance, String currency) {
        this(balance, currency, balance, BigDecimal.ZERO);
    }
}
