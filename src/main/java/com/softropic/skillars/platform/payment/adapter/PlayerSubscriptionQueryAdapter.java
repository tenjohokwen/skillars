package com.softropic.skillars.platform.payment.adapter;

import com.softropic.skillars.platform.payment.repo.PaymentPlayerSubscription;
import com.softropic.skillars.platform.payment.repo.PaymentPlayerSubscriptionRepository;
import com.softropic.skillars.platform.video.contract.PlayerSubscriptionQueryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class PlayerSubscriptionQueryAdapter implements PlayerSubscriptionQueryPort {

    private final PaymentPlayerSubscriptionRepository playerSubscriptionRepository;

    private static final Set<String> ACTIVE_STATUSES = Set.of("ACTIVE", "TRIALLING");

    @Override
    public boolean hasActiveYearlySubscription(Long playerId) {
        return playerSubscriptionRepository.findByPlayerId(playerId)
            .map(sub -> ACTIVE_STATUSES.contains(sub.getStatus())
                && "YEARLY".equalsIgnoreCase(sub.getBillingInterval())
                && sub.getCurrentPeriodEnd() != null
                && sub.getCurrentPeriodEnd().isAfter(Instant.now()))
            .orElse(false);
    }

    @Override
    public boolean hasAnyActiveSubscription(Long playerId) {
        return playerSubscriptionRepository.findByPlayerId(playerId)
            .map(sub -> ACTIVE_STATUSES.contains(sub.getStatus())
                && sub.getCurrentPeriodEnd() != null
                && sub.getCurrentPeriodEnd().isAfter(Instant.now()))
            .orElse(false);
    }
}
