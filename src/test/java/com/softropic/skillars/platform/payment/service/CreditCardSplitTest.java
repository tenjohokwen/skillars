package com.softropic.skillars.platform.payment.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** fix/payment-agreed-price-lock: the one wallet/card split rule, incl. the negative-balance clamp. */
class CreditCardSplitTest {

    private static CreditCardSplit split(String balance, String amount) {
        return CreditCardSplit.of(balance == null ? null : new BigDecimal(balance), new BigDecimal(amount));
    }

    @Test
    void negativeBalance_isClampedToZero_cardChargedExactlyTheAmount() {
        CreditCardSplit s = split("-15.00", "50.00");
        assertThat(s.creditApplied()).isEqualByComparingTo("0");
        assertThat(s.cardCharge()).isEqualByComparingTo("50.00");
    }

    @Test
    void nullBalance_treatedAsZero() {
        CreditCardSplit s = split(null, "50.00");
        assertThat(s.creditApplied()).isEqualByComparingTo("0");
        assertThat(s.cardCharge()).isEqualByComparingTo("50.00");
    }

    @Test
    void partialBalance_creditFirstThenCard() {
        CreditCardSplit s = split("20.00", "50.00");
        assertThat(s.creditApplied()).isEqualByComparingTo("20.00");
        assertThat(s.cardCharge()).isEqualByComparingTo("30.00");
    }

    @Test
    void balanceCoversAmount_nothingOnCard() {
        CreditCardSplit s = split("80.00", "50.00");
        assertThat(s.creditApplied()).isEqualByComparingTo("50.00");
        assertThat(s.cardCharge()).isEqualByComparingTo("0");
    }

    @Test
    void parts_alwaysSumToTheAmount() {
        for (String balance : new String[] {"-100.00", "0", "0.01", "49.99", "50.00", "1000"}) {
            CreditCardSplit s = split(balance, "50.00");
            assertThat(s.creditApplied().add(s.cardCharge())).isEqualByComparingTo("50.00");
            assertThat(s.creditApplied().signum()).isGreaterThanOrEqualTo(0);
        }
    }
}
