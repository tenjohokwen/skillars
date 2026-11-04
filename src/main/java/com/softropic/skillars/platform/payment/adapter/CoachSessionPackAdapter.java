package com.softropic.skillars.platform.payment.adapter;

import com.softropic.skillars.platform.marketplace.contract.CoachSessionPackPort;
import com.softropic.skillars.platform.marketplace.contract.SessionPackDto;
import com.softropic.skillars.platform.marketplace.contract.SessionPackLimits;
import com.softropic.skillars.platform.payment.repo.SessionPackTierRepository;
import com.softropic.skillars.platform.payment.service.SessionPackPaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * skillars-deferred-168: see {@link CoachSessionPackPort}. The read goes through JPA on purpose:
 * {@code CoachPublicProfileQueryCountIT} budgets {@code getPublicProfile} by Hibernate's prepared-statement
 * count, which a JDBC read would escape.
 */
@Component
@RequiredArgsConstructor
public class CoachSessionPackAdapter implements CoachSessionPackPort {

    private final SessionPackTierRepository sessionPackTierRepository;
    private final SessionPackPaymentService sessionPackPaymentService;

    @Override
    public Optional<SessionPackDto> findActivePack(UUID coachId) {
        return sessionPackTierRepository.findByCoachIdAndIsActiveTrue(coachId)
            .map(tier -> new SessionPackDto(
                tier.getSessionCount(),
                tier.getTotalPrice(),
                "EUR",
                SessionPackLimits.DEFAULT_LABEL.equals(tier.getLabel()) ? null : tier.getLabel()));
    }

    @Override
    public void replaceActivePack(UUID coachId, Optional<PackSpec> pack) {
        if (pack.isEmpty()) {
            sessionPackPaymentService.withdrawActiveOffer(coachId);
            return;
        }
        PackSpec spec = pack.get();
        sessionPackPaymentService.replaceActiveOffer(coachId, spec.sessionCount(), spec.totalPrice(), spec.label());
    }
}
