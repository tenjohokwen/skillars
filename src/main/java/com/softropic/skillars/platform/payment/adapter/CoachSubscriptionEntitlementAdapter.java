package com.softropic.skillars.platform.payment.adapter;

import com.softropic.skillars.platform.marketplace.contract.CoachSubscriptionEntitlementPort;
import com.softropic.skillars.platform.payment.contract.SubscriptionEntitlement;
import com.softropic.skillars.platform.payment.repo.PaymentCoachSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** fix/payment-subscription-entitlements: see {@link CoachSubscriptionEntitlementPort}. */
@Component
@RequiredArgsConstructor
public class CoachSubscriptionEntitlementAdapter implements CoachSubscriptionEntitlementPort {

    private final PaymentCoachSubscriptionRepository paymentCoachSubscriptionRepository;

    @Override
    public boolean isPaidTierEntitled(UUID coachId) {
        return paymentCoachSubscriptionRepository.findByCoachId(coachId)
            .map(sub -> SubscriptionEntitlement.isEntitled(sub.getStatus()))
            .orElse(true);
    }
}
