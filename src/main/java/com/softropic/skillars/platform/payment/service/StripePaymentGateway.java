package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.payment.contract.PaymentGateway;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.payment.repo.CoachStripeAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class StripePaymentGateway implements PaymentGateway {

    private final CoachStripeAccountRepository coachStripeAccountRepository;

    @Override
    public String capturePayment(UUID referenceId, UUID coachId, BigDecimal amount) {
        // Deferred to Story 7.2 — parent payment method capture (stripe_customers,
        // SetupIntent) and booking_payments table are not available in Story 7.1.
        throw new PaymentGatewayException("payment.providerUnavailable");
    }

    @Override
    public boolean isCoachPaymentReady(UUID coachId) {
        return coachStripeAccountRepository.findById(coachId)
            .map(a -> "COMPLETE".equals(a.getOnboardingStatus()) && a.isChargesEnabled())
            .orElse(false);
    }
}
