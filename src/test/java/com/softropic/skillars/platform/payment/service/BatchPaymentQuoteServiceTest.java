package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.marketplace.repo.CoachPricing;
import com.softropic.skillars.platform.marketplace.repo.CoachPricingRepository;
import com.softropic.skillars.platform.payment.contract.BatchPaymentQuoteDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** feat/coach-workflow-gaps A22. */
@ExtendWith(MockitoExtension.class)
class BatchPaymentQuoteServiceTest {

    private static final Long PAYER = 42L;
    private static final UUID COACH = UUID.randomUUID();

    @Mock CoachPricingRepository coachPricingRepository;
    @Mock CreditWalletService creditWalletService;
    @InjectMocks BatchPaymentQuoteService service;

    private void price(String perSession) {
        CoachPricing pricing = mock(CoachPricing.class);
        when(pricing.getPerSessionPrice()).thenReturn(new BigDecimal(perSession));
        when(coachPricingRepository.findByCoachId(COACH)).thenReturn(Optional.of(pricing));
    }

    @Test
    void walletCoversPartOfTotal_restChargedToCard() {
        price("40.00");
        when(creditWalletService.getBalance(PAYER)).thenReturn(new BigDecimal("25.00"));

        BatchPaymentQuoteDto q = service.quote(PAYER, COACH, 3);

        assertThat(q.total()).isEqualByComparingTo("120.00");
        assertThat(q.creditApplied()).isEqualByComparingTo("25.00");
        assertThat(q.cardCharge()).isEqualByComparingTo("95.00");
        assertThat(q.perSessionPrice()).isEqualByComparingTo("40.00");
    }

    /** fix/payment-agreed-price-lock: a negative balance never inflates the card charge. */
    @Test
    void negativeBalance_noCreditApplied_cardChargedExactlyTheTotal() {
        price("40.00");
        when(creditWalletService.getBalance(PAYER)).thenReturn(new BigDecimal("-25.00"));

        BatchPaymentQuoteDto q = service.quote(PAYER, COACH, 2);

        assertThat(q.creditApplied()).isEqualByComparingTo("0");
        assertThat(q.cardCharge()).isEqualByComparingTo("80.00");
    }

    @Test
    void walletCoversEverything_nothingChargedToCard() {
        price("40.00");
        when(creditWalletService.getBalance(PAYER)).thenReturn(new BigDecimal("500.00"));

        BatchPaymentQuoteDto q = service.quote(PAYER, COACH, 2);

        assertThat(q.creditApplied()).isEqualByComparingTo("80.00");
        assertThat(q.cardCharge()).isEqualByComparingTo("0");
    }

    @Test
    void noPricingRow_quotesZero_likeSettlement() {
        when(coachPricingRepository.findByCoachId(COACH)).thenReturn(Optional.empty());
        when(creditWalletService.getBalance(PAYER)).thenReturn(BigDecimal.TEN);

        BatchPaymentQuoteDto q = service.quote(PAYER, COACH, 2);

        assertThat(q.total()).isEqualByComparingTo("0");
        assertThat(q.cardCharge()).isEqualByComparingTo("0");
    }

    @Test
    void creditCardSplit_matchesSettlementRule() {
        CreditCardSplit split = CreditCardSplit.of(new BigDecimal("10.00"), new BigDecimal("35.50"));
        assertThat(split.creditApplied()).isEqualByComparingTo("10.00");
        assertThat(split.cardCharge()).isEqualByComparingTo("25.50");
    }
}
