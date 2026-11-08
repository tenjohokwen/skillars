package com.softropic.skillars.platform.payment.api;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.booking.contract.PauseConflictResponse;
import com.softropic.skillars.platform.booking.contract.PausePackRequest;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.config.PaymentProperties;
import com.softropic.skillars.platform.payment.contract.CoachSoldPackDto;
import com.softropic.skillars.platform.payment.contract.CreateSessionPackTierRequest;
import com.softropic.skillars.platform.payment.contract.PurchaseSessionPackRequest;
import com.softropic.skillars.platform.payment.contract.SavedPaymentMethodRequest;
import com.softropic.skillars.platform.payment.contract.SavedPaymentMethodResponse;
import com.softropic.skillars.platform.payment.contract.SessionPackPurchaseResponse;
import com.softropic.skillars.platform.payment.contract.SessionPackTierResponse;
import com.softropic.skillars.platform.payment.contract.SetupIntentResponse;
import com.softropic.skillars.platform.payment.contract.StripeConfigResponse;
import com.softropic.skillars.platform.payment.contract.PaymentGateway;
import com.softropic.skillars.platform.payment.repo.StripeCustomer;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import com.softropic.skillars.platform.payment.service.CoachSoldPackQueryService;
import com.softropic.skillars.platform.payment.service.PackSessionService;
import com.softropic.skillars.platform.payment.service.SessionPackPaymentService;
import com.softropic.skillars.platform.payment.service.StripeCustomerEmailSyncOutboxSupport;
import com.softropic.skillars.platform.payment.service.SubscriptionService;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
@Observed(name = "payment.session-packs")
public class SessionPackPaymentResource {

    private final SessionPackPaymentService sessionPackPaymentService;
    private final PackSessionService packSessionService;
    private final StripeCustomerRepository stripeCustomerRepository;
    private final PaymentGateway paymentGateway;
    private final CoachProfileRepository coachProfileRepository;
    private final SecurityUtil securityUtil;
    private final PaymentProperties paymentProperties;
    private final CoachSoldPackQueryService coachSoldPackQueryService;
    private final SubscriptionService subscriptionService;
    private final StripeCustomerEmailSyncOutboxSupport stripeCustomerEmailSync;

    // ─── Parent: purchase a session pack ──────────────────────────────────────

    @PostMapping("/session-packs/purchase")
    @PreAuthorize(SecurityConstants.HAS_PARENT_OR_PLAYER_ROLE)
    public ResponseEntity<SessionPackPurchaseResponse> purchaseSessionPack(
            @Valid @RequestBody PurchaseSessionPackRequest request,
            // fix/payment-session-pack-integrity: one key per purchase attempt, so a double-submit dedupes
            // to the same charge and pack. Optional — the service generates one when absent.
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        Long parentId = securityUtil.getCurrentCoachUserId();
        SessionPackPurchaseResponse response = sessionPackPaymentService.purchasePack(
            parentId, request.packTierId(), request.playerId(), request.paymentMethodId(),
            idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.strip());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // ─── Parent: list own session packs ───────────────────────────────────────

    @GetMapping("/session-packs")
    @PreAuthorize(SecurityConstants.HAS_PARENT_OR_PLAYER_ROLE)
    public ResponseEntity<List<SessionPackPurchaseResponse>> getMySessionPacks(
            @RequestParam(required = false) UUID coachId) {
        Long parentId = securityUtil.getCurrentCoachUserId();
        return ResponseEntity.ok(sessionPackPaymentService.getPacksForParent(parentId, coachId));
    }

    // ─── Parent: pause a session pack ──────────────────────────────────────────

    @PostMapping("/session-packs/{purchaseId}/pause")
    @PreAuthorize(SecurityConstants.HAS_PARENT_OR_PLAYER_ROLE)
    public ResponseEntity<PauseConflictResponse> pauseSessionPack(
            @PathVariable UUID purchaseId, @Valid @RequestBody PausePackRequest request) {
        Long parentId = securityUtil.getCurrentCoachUserId();
        PauseConflictResponse response = packSessionService.pausePack(parentId, purchaseId, request);
        return ResponseEntity.ok(response);
    }

    // ─── Coach: extend a session pack ─────────────────────────────────────────

    @PostMapping("/session-packs/{purchaseId}/extend")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<Void> extendSessionPack(@PathVariable UUID purchaseId) {
        Long coachUserId = securityUtil.getCurrentCoachUserId();
        sessionPackPaymentService.extendPack(coachUserId, purchaseId);
        return ResponseEntity.noContent().build();
    }

    // ─── Coach: list sold packs (feat/coach-workflow-gaps A20) ─────────────────
    // Scoped to the caller's own coach profile by construction (coachId is resolved from the principal,
    // never taken from the request), so there is no ownership check to get wrong. Feeds the "Extend"
    // action above, which re-checks ownership and eligibility itself under a row lock.

    @GetMapping("/coaches/me/session-packs")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<Page<CoachSoldPackDto>> getMySoldPacks(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID coachId = resolveCoachId();
        int boundedSize = Math.max(1, Math.min(size, 100));
        return ResponseEntity.ok(coachSoldPackQueryService.getSoldPacks(
            coachId, PageRequest.of(Math.max(0, page), boundedSize)));
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
    @PreAuthorize(SecurityConstants.HAS_PARENT_OR_PLAYER_ROLE)
    public ResponseEntity<SessionPackTierResponse> getActiveCoachTier(@PathVariable UUID coachId) {
        SessionPackTierResponse tier = sessionPackPaymentService.getActiveCoachTier(coachId);
        if (tier == null) {
            throw new ResourceNotFoundException("Active session pack tier not found for coach " + coachId, "SessionPackTier");
        }
        return ResponseEntity.ok(tier);
    }

    // ─── Card setup (AC 11) ───────────────────────────────────────────────────

    @PostMapping("/setup-intent")
    @PreAuthorize(SecurityConstants.HAS_PARENT_PLAYER_OR_COACH_ROLE)
    public ResponseEntity<SetupIntentResponse> createSetupIntent() {
        Long parentId = securityUtil.getCurrentCoachUserId();
        // skillars-deferred-177 (RS03h): no SetupIntent for a payer erased while the request ran.
        StripeCustomer customer = stripeCustomerRepository.findById(parentId)
            .map(existing -> {
                sessionPackPaymentService.requirePayerLive(parentId);
                return existing;
            })
            .orElseGet(() -> {
                String emailBefore = stripeCustomerEmailSync.currentEmail(parentId);
                String stripeCustomerId = paymentGateway.createStripeCustomer(parentId);
                StripeCustomer sc = new StripeCustomer();
                sc.setParentId(parentId);
                sc.setStripeCustomerId(stripeCustomerId);
                // Insert-only (177 review round 4): save() merges and would overwrite a row a concurrent request committed.
                if (stripeCustomerRepository.insertIfAbsent(parentId, stripeCustomerId, null) == 0) {
                    // A concurrent first request saved the row; it is an existing customer now (177 review round 1), and the
                    // customer this request created is named by no row, so it is erased at Stripe (review round 3).
                    sessionPackPaymentService.discardUnrecordedCustomer(stripeCustomerId);
                    sessionPackPaymentService.requirePayerLive(parentId);
                    return stripeCustomerRepository.findById(parentId).orElseThrow();
                }
                StripeCustomer saved = sc;
                // skillars-deferred-176 AC2: an email change that committed while the customer was created enqueued no sync.
                if (stripeCustomerEmailSync.syncIfEmailChangedSince(parentId, emailBefore)) {
                    throw SessionPackPaymentService.erasedPayer(parentId);
                }
                return saved;
            });
        String clientSecret = paymentGateway.createSetupIntent(customer.getStripeCustomerId());
        return ResponseEntity.ok(new SetupIntentResponse(clientSecret));
    }

    @PostMapping("/save-payment-method")
    @PreAuthorize(SecurityConstants.HAS_PARENT_PLAYER_OR_COACH_ROLE)
    public ResponseEntity<Void> savePaymentMethod(@Valid @RequestBody SavedPaymentMethodRequest request) {
        Long parentId = securityUtil.getCurrentCoachUserId();
        // skillars-deferred-174 AC1 (RS09b): only a card Stripe reports attached to the caller's own customer is saved.
        // The card form always creates that customer first (POST /setup-intent), so a caller without one cannot own the
        // card. Checked before the save's transaction (it is a Stripe call); a Stripe failure answers 422 and saves nothing.
        StripeCustomer customer = stripeCustomerRepository.findById(parentId).orElse(null);
        if (customer == null
                || !paymentGateway.paymentMethodBelongsTo(customer.getStripeCustomerId(), request.paymentMethodId())) {
            log.warn("[PAYMENT_METHOD_NOT_OWNED] userId={} hasCustomer={} — card not saved", parentId, customer != null);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "payment.paymentMethodNotOwned");
        }
        // fix/payment-subscription-resume-and-async-sync: saves the card and, atomically with it, enqueues
        // the move of the caller's live subscriptions onto it (default payment method, past-due invoice
        // retry, paused-subscription resume). The Stripe calls run on the durable outbox after commit, so
        // they neither fail nor slow this request, and a Stripe outage is retried.
        subscriptionService.savePaymentMethodAndScheduleSync(parentId, request.paymentMethodId());
        // feat/security-s06-related-party: best-effort, after the save has committed.
        sessionPackPaymentService.recordCardFingerprint(parentId, request.paymentMethodId());
        return ResponseEntity.noContent().build();
    }

    // ─── Stripe.js configuration ───────────────────────────────────────────────

    @GetMapping("/stripe/config")
    @PreAuthorize(SecurityConstants.IS_AUTHENTICATED)
    public ResponseEntity<StripeConfigResponse> getStripeConfig() {
        return ResponseEntity.ok(new StripeConfigResponse(paymentProperties.getPublishableKey()));
    }

    // ─── Parent: saved payment method ──────────────────────────────────────────

    @GetMapping("/payment-method")
    @PreAuthorize(SecurityConstants.HAS_PARENT_PLAYER_OR_COACH_ROLE)
    public ResponseEntity<SavedPaymentMethodResponse> getSavedPaymentMethod() {
        Long parentId = securityUtil.getCurrentCoachUserId();
        return ResponseEntity.ok(sessionPackPaymentService.getSavedPaymentMethod(parentId));
    }

    private UUID resolveCoachId() {
        Long coachUserId = securityUtil.getCurrentCoachUserId();
        CoachProfile coach = coachProfileRepository.findByUserId(coachUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));
        return coach.getId();
    }
}
