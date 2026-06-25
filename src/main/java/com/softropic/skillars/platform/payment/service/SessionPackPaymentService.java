package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.contract.SessionPackPurchaseResponse;
import com.softropic.skillars.platform.payment.contract.SessionPackTierResponse;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchase;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchaseRepository;
import com.softropic.skillars.platform.payment.repo.SessionPackTier;
import com.softropic.skillars.platform.payment.repo.SessionPackTierRepository;
import com.softropic.skillars.platform.payment.repo.StripeCustomer;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import com.softropic.skillars.platform.payment.contract.PaymentGateway;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SessionPackPaymentService {

    private final SessionPackTierRepository sessionPackTierRepository;
    private final SessionPackPurchaseRepository sessionPackPurchaseRepository;
    private final StripeCustomerRepository stripeCustomerRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final PaymentGateway paymentGateway;

    public SessionPackPurchaseResponse purchasePack(Long parentId, UUID packTierId, String paymentMethodId) {
        SessionPackTier tier = sessionPackTierRepository.findById(packTierId)
            .orElseThrow(() -> new ResourceNotFoundException("Session pack tier not found", "session_pack_tier"));
        if (!tier.isActive()) {
            throw new PaymentGatewayException("payment.packTierInactive");
        }

        StripeCustomer customer = getOrCreateStripeCustomer(parentId, paymentMethodId);

        String paymentIntentId = paymentGateway.chargeAndCapture(
            packTierId, parentId, tier.getCoachId(), tier.getTotalPrice());

        SessionPackPurchase purchase;
        try {
            purchase = createPurchase(parentId, tier, paymentIntentId);
        } catch (Exception e) {
            log.error("Purchase record creation failed after Stripe charge: packTierId={} parentId={}", packTierId, parentId);
            try {
                paymentGateway.refund(paymentIntentId, tier.getTotalPrice());
            } catch (PaymentGatewayException refundEx) {
                log.error("Compensating refund also failed: intentId={}", paymentIntentId);
            }
            throw new PaymentGatewayException("payment.lifecycleFailure");
        }

        return toResponse(purchase, tier.getLabel(), tier.getSessionCount());
    }

    @Transactional
    public void extendPack(Long coachUserId, UUID purchaseId) {
        CoachProfile coach = coachProfileRepository.findByUserId(coachUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));

        SessionPackPurchase purchase = sessionPackPurchaseRepository.findById(purchaseId)
            .orElseThrow(() -> new ResourceNotFoundException("Session pack purchase not found", "session_pack_purchase"));

        if (!purchase.getCoachId().equals(coach.getId())) {
            throw new OperationNotAllowedException("Coach does not own this session pack", SecurityError.MISSING_RIGHTS);
        }

        Instant now = Instant.now();
        Instant windowStart = purchase.getExpiresAt().minus(14, ChronoUnit.DAYS);
        if (now.isBefore(windowStart) || now.isAfter(purchase.getExpiresAt()) || purchase.getExtendedAt() != null) {
            throw new PaymentGatewayException("payment.packExtensionNotEligible");
        }

        purchase.setExpiresAt(purchase.getExpiresAt().plus(30, ChronoUnit.DAYS));
        purchase.setExtendedAt(now);
        sessionPackPurchaseRepository.save(purchase);
        log.info("Session pack extended: purchaseId={} newExpiresAt={}", purchaseId, purchase.getExpiresAt());
    }

    @Transactional
    public SessionPackTierResponse createTier(UUID coachId, String label, int sessionCount, BigDecimal totalPrice) {
        // Deactivate all existing active tiers for this coach
        sessionPackTierRepository.findAllByCoachIdAndIsActiveTrue(coachId)
            .forEach(t -> {
                t.setActive(false);
                sessionPackTierRepository.saveAndFlush(t);
            });

        BigDecimal pricePerSession = totalPrice
            .divide(BigDecimal.valueOf(sessionCount), 2, RoundingMode.HALF_UP);

        SessionPackTier tier = new SessionPackTier();
        tier.setCoachId(coachId);
        tier.setLabel(label);
        tier.setSessionCount(sessionCount);
        tier.setTotalPrice(totalPrice);
        tier.setPricePerSession(pricePerSession);
        tier.setActive(true);
        SessionPackTier saved = sessionPackTierRepository.save(tier);

        log.info("Session pack tier created: coachId={} label={} sessions={}", coachId, label, sessionCount);
        return toTierResponse(saved);
    }

    @Transactional
    public void deactivateTier(UUID coachId, UUID tierId) {
        SessionPackTier tier = sessionPackTierRepository.findById(tierId)
            .orElseThrow(() -> new ResourceNotFoundException("Session pack tier not found", "session_pack_tier"));
        if (!tier.getCoachId().equals(coachId)) {
            throw new OperationNotAllowedException("Coach does not own this tier", SecurityError.MISSING_RIGHTS);
        }
        tier.setActive(false);
        sessionPackTierRepository.save(tier);
    }

    public List<SessionPackTierResponse> getCoachTiers(UUID coachId) {
        return sessionPackTierRepository.findAllByCoachId(coachId).stream()
            .map(this::toTierResponse)
            .collect(Collectors.toList());
    }

    public SessionPackTierResponse getActiveCoachTier(UUID coachId) {
        return sessionPackTierRepository.findByCoachIdAndIsActiveTrue(coachId)
            .map(this::toTierResponse)
            .orElse(null);
    }

    private StripeCustomer getOrCreateStripeCustomer(Long parentId, String paymentMethodId) {
        return stripeCustomerRepository.findById(parentId).map(existing -> {
            if (paymentMethodId != null && !paymentMethodId.isBlank()) {
                existing.setStripePaymentMethodId(paymentMethodId);
                stripeCustomerRepository.save(existing);
            }
            return existing;
        }).orElseGet(() -> {
            String stripeCustomerId = paymentGateway.createStripeCustomer(parentId);
            StripeCustomer sc = new StripeCustomer();
            sc.setParentId(parentId);
            sc.setStripeCustomerId(stripeCustomerId);
            sc.setStripePaymentMethodId(paymentMethodId);
            return stripeCustomerRepository.save(sc);
        });
    }

    private SessionPackPurchase createPurchase(Long parentId, SessionPackTier tier, String paymentIntentId) {
        SessionPackPurchase purchase = new SessionPackPurchase();
        purchase.setParentId(parentId);
        purchase.setCoachId(tier.getCoachId());
        purchase.setPackTierId(tier.getPackTierId());
        purchase.setPricePerSession(tier.getPricePerSession());
        purchase.setRemainingSessions(tier.getSessionCount());
        purchase.setExpiresAt(Instant.now().plus(60, ChronoUnit.DAYS));
        purchase.setStripePaymentIntentId(paymentIntentId);
        return sessionPackPurchaseRepository.save(purchase);
    }

    private SessionPackPurchaseResponse toResponse(SessionPackPurchase purchase, String label, int sessionCount) {
        return new SessionPackPurchaseResponse(
            purchase.getPurchaseId(),
            purchase.getPackTierId(),
            label,
            sessionCount,
            purchase.getRemainingSessions(),
            purchase.getPricePerSession(),
            purchase.getExpiresAt(),
            purchase.getStripePaymentIntentId()
        );
    }

    private SessionPackTierResponse toTierResponse(SessionPackTier tier) {
        return new SessionPackTierResponse(
            tier.getPackTierId(),
            tier.getCoachId(),
            tier.getLabel(),
            tier.getSessionCount(),
            tier.getTotalPrice(),
            tier.getPricePerSession(),
            tier.isActive(),
            tier.getCreatedAt()
        );
    }
}
