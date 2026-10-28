package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.marketplace.repo.CoachPricing;
import com.softropic.skillars.platform.marketplace.repo.CoachPricingRepository;
import com.softropic.skillars.platform.payment.contract.BatchPaymentQuoteDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * feat/coach-workflow-gaps A22: previews the batch charge exactly as
 * {@link PaymentLifecycleService#onBatchBookingAccepted} will compute it — price per booking from
 * {@code coach_pricing.per_session_price}, subtotal over the sessions, then {@link CreditCardSplit} against the
 * payer's wallet balance. fix/payment-agreed-price-lock: that same current price is what {@code createBatch}
 * locks onto each booking ({@code agreed_price}) and settlement charges. With no pricing row the quote shows
 * zero, but the accept then fails rather than confirming for free.
 */
@Service
@RequiredArgsConstructor
public class BatchPaymentQuoteService {

    private final CoachPricingRepository coachPricingRepository;
    private final CreditWalletService creditWalletService;

    @Transactional(readOnly = true)
    public BatchPaymentQuoteDto quote(Long payerId, UUID coachId, int sessionCount) {
        BigDecimal price = coachPricingRepository.findByCoachId(coachId)
            .map(CoachPricing::getPerSessionPrice)
            .orElse(BigDecimal.ZERO);
        BigDecimal total = price.multiply(BigDecimal.valueOf(sessionCount));
        BigDecimal balance = creditWalletService.getBalance(payerId);
        CreditCardSplit split = CreditCardSplit.of(balance, total);
        return new BatchPaymentQuoteDto(price, sessionCount, total, balance,
            split.creditApplied(), split.cardCharge(), "EUR");
    }
}
