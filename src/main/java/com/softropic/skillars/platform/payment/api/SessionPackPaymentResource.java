package com.softropic.skillars.platform.payment.api;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.contract.CreateSessionPackTierRequest;
import com.softropic.skillars.platform.payment.contract.PurchaseSessionPackRequest;
import com.softropic.skillars.platform.payment.contract.SavedPaymentMethodRequest;
import com.softropic.skillars.platform.payment.contract.SessionPackPurchaseResponse;
import com.softropic.skillars.platform.payment.contract.SessionPackTierResponse;
import com.softropic.skillars.platform.payment.contract.SetupIntentResponse;
import com.softropic.skillars.platform.payment.contract.PaymentGateway;
import com.softropic.skillars.platform.payment.repo.StripeCustomer;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import com.softropic.skillars.platform.payment.service.SessionPackPaymentService;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
@Observed(name = "payment.session-packs")
public class SessionPackPaymentResource {

    private final SessionPackPaymentService sessionPackPaymentService;
    private final StripeCustomerRepository stripeCustomerRepository;
    private final PaymentGateway paymentGateway;
    private final CoachProfileRepository coachProfileRepository;
    private final SecurityUtil securityUtil;

    // ─── Parent: purchase a session pack ──────────────────────────────────────

    @PostMapping("/session-packs/purchase")
    @PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)
    public ResponseEntity<SessionPackPurchaseResponse> purchaseSessionPack(
            @Valid @RequestBody PurchaseSessionPackRequest request) {
        Long parentId = securityUtil.getCurrentCoachUserId();
        SessionPackPurchaseResponse response = sessionPackPaymentService.purchasePack(
            parentId, request.packTierId(), request.paymentMethodId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // ─── Coach: extend a session pack ─────────────────────────────────────────

    @PostMapping("/session-packs/{purchaseId}/extend")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<Void> extendSessionPack(@PathVariable UUID purchaseId) {
        Long coachUserId = securityUtil.getCurrentCoachUserId();
        sessionPackPaymentService.extendPack(coachUserId, purchaseId);
        return ResponseEntity.noContent().build();
    }

    // ─── Coach: manage tiers ──────────────────────────────────────────────────

    @GetMapping("/coaches/me/session-pack-tiers")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<List<SessionPackTierResponse>> getMyTiers() {
        UUID coachId = resolveCoachId();
        return ResponseEntity.ok(sessionPackPaymentService.getCoachTiers(coachId));
    }

    @PostMapping("/coaches/me/session-pack-tiers")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<SessionPackTierResponse> createTier(
            @Valid @RequestBody CreateSessionPackTierRequest request) {
        UUID coachId = resolveCoachId();
        SessionPackTierResponse response = sessionPackPaymentService.createTier(
            coachId, request.label(), request.sessionCount(), request.totalPrice());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PatchMapping("/coaches/me/session-pack-tiers/{tierId}/deactivate")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<Void> deactivateTier(@PathVariable UUID tierId) {
        UUID coachId = resolveCoachId();
        sessionPackPaymentService.deactivateTier(coachId, tierId);
        return ResponseEntity.noContent().build();
    }

    // ─── Parent: discover active tier for a coach ─────────────────────────────

    @GetMapping("/coaches/{coachId}/session-pack-tiers")
    @PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)
    public ResponseEntity<SessionPackTierResponse> getActiveCoachTier(@PathVariable UUID coachId) {
        SessionPackTierResponse tier = sessionPackPaymentService.getActiveCoachTier(coachId);
        if (tier == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(tier);
    }

    // ─── Card setup (AC 11) ───────────────────────────────────────────────────

    @PostMapping("/setup-intent")
    @PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)
    public ResponseEntity<SetupIntentResponse> createSetupIntent() {
        Long parentId = securityUtil.getCurrentCoachUserId();
        StripeCustomer customer = stripeCustomerRepository.findById(parentId)
            .orElseGet(() -> {
                String stripeCustomerId = paymentGateway.createStripeCustomer(parentId);
                StripeCustomer sc = new StripeCustomer();
                sc.setParentId(parentId);
                sc.setStripeCustomerId(stripeCustomerId);
                try {
                    return stripeCustomerRepository.save(sc);
                } catch (DataIntegrityViolationException e) {
                    return stripeCustomerRepository.findById(parentId).orElseThrow();
                }
            });
        String clientSecret = paymentGateway.createSetupIntent(customer.getStripeCustomerId());
        return ResponseEntity.ok(new SetupIntentResponse(clientSecret));
    }

    @PostMapping("/save-payment-method")
    @PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)
    public ResponseEntity<Void> savePaymentMethod(@Valid @RequestBody SavedPaymentMethodRequest request) {
        Long parentId = securityUtil.getCurrentCoachUserId();
        StripeCustomer customer = stripeCustomerRepository.findById(parentId)
            .orElseGet(() -> {
                String stripeCustomerId = paymentGateway.createStripeCustomer(parentId);
                StripeCustomer sc = new StripeCustomer();
                sc.setParentId(parentId);
                sc.setStripeCustomerId(stripeCustomerId);
                return sc;
            });
        customer.setStripePaymentMethodId(request.paymentMethodId());
        try {
            stripeCustomerRepository.save(customer);
        } catch (DataIntegrityViolationException e) {
            StripeCustomer existing = stripeCustomerRepository.findById(parentId).orElseThrow();
            existing.setStripePaymentMethodId(request.paymentMethodId());
            stripeCustomerRepository.save(existing);
        }
        return ResponseEntity.noContent().build();
    }

    private UUID resolveCoachId() {
        Long coachUserId = securityUtil.getCurrentCoachUserId();
        CoachProfile coach = coachProfileRepository.findByUserId(coachUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));
        return coach.getId();
    }
}
