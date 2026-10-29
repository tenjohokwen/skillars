package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.booking.service.BookingService;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.contract.SavedPaymentMethodResponse;
import com.softropic.skillars.platform.payment.contract.SessionPackPurchaseResponse;
import com.softropic.skillars.platform.payment.contract.SessionPackTierResponse;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentMethod;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchase;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchaseRepository;
import com.softropic.skillars.platform.payment.repo.SessionPackTier;
import com.softropic.skillars.platform.payment.repo.SessionPackTierRepository;
import com.softropic.skillars.platform.payment.repo.StripeCustomer;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import com.softropic.skillars.platform.payment.contract.PaymentGateway;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SessionPackPaymentService {

    /** How long before expiry a coach may extend a pack (once). Shared with CoachSoldPackQueryService. */
    static final java.time.Duration EXTENSION_WINDOW = java.time.Duration.ofDays(14);

    /** A client purchase-attempt key: UUID-like, bounded so the derived Stripe key stays under 255 chars. */
    private static final Pattern ATTEMPT_KEY_PATTERN = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    private final SessionPackTierRepository sessionPackTierRepository;
    private final SessionPackPurchaseRepository sessionPackPurchaseRepository;
    private final StripeCustomerRepository stripeCustomerRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final PlayerProfileRepository playerProfileRepository;
    private final PaymentGateway paymentGateway;
    private final StripeClient stripeClient;
    private final PessimisticLockRetryer lockRetryer;
    private final RefundReconciliationService refundReconciliationService;
    private final ConfigService configService;
    private final SubscriptionService subscriptionService;

    /**
     * Purchase a session pack for a player.
     * Uses compensating-action pattern: charge → persist → on-failure compensating refund.
     * Not annotated @Transactional to avoid holding DB connection open across external Stripe call.
     *
     * <p>skillars-deferred-99 AC1: if persisting the purchase throws, the compensating
     * {@code paymentGateway.refund(...)} is attempted under a deterministic Stripe idempotency key
     * (so a caller retry replays the original refund rather than double-crediting). Any throwable
     * from that refund — not just {@link PaymentGatewayException} — is caught and a durable
     * {@code stripe_refund_failures} row is written via {@link RefundReconciliationService} so an
     * operator can recover the money. The outer {@code payment.lifecycleFailure} is thrown on every
     * path.
     */
    public SessionPackPurchaseResponse purchasePack(Long parentId, UUID packTierId, Long playerId, String paymentMethodId) {
        return purchasePack(parentId, packTierId, playerId, paymentMethodId, null);
    }

    /**
     * fix/payment-session-pack-integrity: {@code attemptKey} names one purchase attempt (the client's
     * {@code Idempotency-Key}; generated here when absent). It becomes the Stripe idempotency key, so a
     * double-submit replays the same PaymentIntent and gets back the pack that charge already funded — never
     * a second pack for one charge (V195 unique index), and never a sibling's purchase replayed.
     */
    public SessionPackPurchaseResponse purchasePack(Long parentId, UUID packTierId, Long playerId, String paymentMethodId,
                                                    String attemptKey) {
        if (attemptKey != null && !ATTEMPT_KEY_PATTERN.matcher(attemptKey).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "payment.invalidIdempotencyKey");
        }
        // Deferred-81 AC4: a self-registered (no-parent) player has parentId == null by
        // definition, so the old findByIdAndParentId(playerId, parentId) lookup always returned
        // empty for them — reworks this into the same parentId-vs-userId XOR branch
        // BookingService.createBookingRequest/getParentPlayerSchedule and
        // BookingBatchService.createBatch already use for the identical opaque-payer-id pattern.
        PlayerProfile player = playerProfileRepository.findById(playerId)
            .orElseThrow(() -> new ResourceNotFoundException("Player not found", "player_profile"));
        if (player.getParentId() != null) {
            if (!Objects.equals(player.getParentId(), parentId)) {
                throw new OperationNotAllowedException("Parent does not own this player", SecurityError.MISSING_RIGHTS);
            }
        } else {
            if (!Objects.equals(player.getUserId(), parentId)) {
                throw new OperationNotAllowedException("Player does not own this profile", SecurityError.MISSING_RIGHTS);
            }
        }

        SessionPackTier tier = sessionPackTierRepository.findById(packTierId)
            .orElseThrow(() -> new ResourceNotFoundException("Session pack tier not found", "session_pack_tier"));
        if (!tier.isActive()) {
            throw new PaymentGatewayException("payment.packTierInactive");
        }
        // fix/payment-pack-integrity: a pack of a coach who cannot be booked (suspended, safety hold, not
        // active) bought nothing usable — refuse before charging, with the booking flow's own status gate.
        CoachProfile coach = coachProfileRepository.findById(tier.getCoachId())
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));
        BookingService.requireBookableCoachStatus(coach, Map.of("coach id", tier.getCoachId()));

        Optional<StripeCustomer> customerBefore = stripeCustomerRepository.findById(parentId);
        boolean customerExisted = customerBefore.isPresent();
        String previousCard = customerBefore.map(StripeCustomer::getStripePaymentMethodId).orElse(null);
        StripeCustomer customer = getOrCreateStripeCustomer(parentId, paymentMethodId);

        // Scoped by parentId so two payers can never share a key, whatever the client sends.
        String idempotencyKey = "pack-" + parentId + "-" + (attemptKey != null ? attemptKey : UUID.randomUUID());
        String paymentIntentId = paymentGateway.chargeAndCapture(
            packTierId, parentId, tier.getCoachId(), tier.getTotalPrice(), idempotencyKey);

        // A replayed attempt whose pack is already recorded: return it, charge nothing more.
        Optional<SessionPackPurchase> alreadyRecorded = sessionPackPurchaseRepository.findByStripePaymentIntentId(paymentIntentId);
        if (alreadyRecorded.isPresent()) {
            return replayedPurchase(alreadyRecorded.get(), playerId, tier);
        }

        SessionPackPurchase purchase;
        try {
            purchase = createPurchase(parentId, playerId, tier, paymentIntentId);
        } catch (Exception e) {
            log.error("[PACK_PURCHASE_RECORD_FAILED] purchase record creation failed after Stripe charge: "
                + "packTierId={} parentId={} intentId={}", packTierId, parentId, paymentIntentId, e);
            // A concurrent request for the same attempt may have recorded the pack for this very charge (the
            // V195 unique index is what failed this insert). Refunding then would take back the money of a
            // pack that exists — so refund only once it is proven that no pack holds this charge.
            final Optional<SessionPackPurchase> winner;
            try {
                winner = sessionPackPurchaseRepository.findByStripePaymentIntentId(paymentIntentId);
            } catch (Exception lookupEx) {
                log.error("[PACK_PURCHASE_UNRECONCILED] charge captured, pack record failed and the intent lookup "
                    + "failed too — NOT refunded automatically, MANUAL RECOVERY REQUIRED: intentId={} amount={} "
                    + "parentId={} packTierId={}", paymentIntentId, tier.getTotalPrice(), parentId, packTierId, lookupEx);
                recordReconciliation(paymentIntentId, tier.getTotalPrice(), parentId, packTierId,
                    "pack record failed and intent lookup failed (not refunded): " + lookupEx);
                throw new PaymentGatewayException("payment.lifecycleFailure");
            }
            if (winner.isPresent()) {
                return replayedPurchase(winner.get(), playerId, tier);
            }
            try {
                paymentGateway.refund(paymentIntentId, tier.getTotalPrice(), "refund-" + paymentIntentId);
            } catch (Exception refundEx) {
                // Widened from PaymentGatewayException (skillars-deferred-99 AC1): an unchecked
                // throw or an unwrapped StripeException used to propagate here and skip both the
                // reconciliation record and the lifecycleFailure wrap. Excludes Error only.
                log.error("Compensating refund also failed: intentId={}", paymentIntentId, refundEx);
                recordReconciliation(paymentIntentId, tier.getTotalPrice(), parentId, packTierId, refundEx.toString());
            }
            throw new PaymentGatewayException("payment.lifecycleFailure");
        }

        if (customerExisted) {
            scheduleSubscriptionCardSync(parentId, previousCard, paymentMethodId);
        }
        return toResponse(purchase, tier.getLabel(), tier.getSessionCount());
    }

    /**
     * fix/payment-pack-integrity: paying for a pack with a different card makes it the saved default card
     * (the charge reads it from {@code stripe_customers}) — the subscriptions this payer funds are moved onto it
     * too, through the same durable outbox sync as the save-card flow (#351: default payment method, open
     * invoice retry, resume of a paused subscription). Only once the charge succeeded and the pack is recorded,
     * so a declined card is never pushed onto the subscriptions. A failure to enqueue never fails the purchase
     * the payer has already been charged for — it is logged; saving the card again re-runs the sync. A payer
     * with no {@code stripe_customers} row before this purchase has no subscription to move.
     */
    private void scheduleSubscriptionCardSync(Long parentId, String previousCard, String paymentMethodId) {
        if (paymentMethodId == null || paymentMethodId.isBlank() || Objects.equals(previousCard, paymentMethodId)) {
            return;
        }
        try {
            subscriptionService.savePaymentMethodAndScheduleSync(parentId, paymentMethodId);
        } catch (RuntimeException e) {
            log.error("[PACK_PURCHASE_CARD_SYNC_FAILED] pack bought with a new card but the subscription card sync "
                + "could not be enqueued: parentId={}", parentId, e);
        }
    }

    /** The pack an earlier delivery of the same attempt already recorded for this charge. */
    private SessionPackPurchaseResponse replayedPurchase(SessionPackPurchase existing, Long playerId, SessionPackTier tier) {
        if (!Objects.equals(existing.getPlayerId(), playerId) || !Objects.equals(existing.getPackTierId(), tier.getPackTierId())) {
            // Same attempt key, different purchase: never hand back (or create) a pack the charge did not fund.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "payment.idempotencyKeyReused");
        }
        log.info("Pack purchase replayed: purchaseId={} intentId={}", existing.getPurchaseId(), existing.getStripePaymentIntentId());
        return toResponse(existing, tier.getLabel(), tier.getSessionCount());
    }

    private void recordReconciliation(String paymentIntentId, BigDecimal amount, Long parentId, UUID packTierId, String reason) {
        try {
            refundReconciliationService.recordRefundFailure(paymentIntentId, amount, parentId, packTierId, reason);
        } catch (Exception reconEx) {
            // The reconciliation write is the last line of defence; if it also fails the money is
            // unrecoverable from the DB. Log at ERROR with the full context so it is recoverable from the
            // logs, then still throw a clean lifecycleFailure (the caller does).
            log.error("Refund reconciliation record could not be persisted — MANUAL RECOVERY "
                + "REQUIRED: intentId={} amount={} parentId={} packTierId={}",
                paymentIntentId, amount, parentId, packTierId, reconEx);
        }
    }

    public List<SessionPackPurchaseResponse> getPacksForParent(Long parentId, UUID coachId) {
        List<SessionPackPurchase> purchases = coachId == null
            ? sessionPackPurchaseRepository.findByParentIdOrderByCreatedAtDesc(parentId)
            : sessionPackPurchaseRepository.findByParentIdAndCoachIdOrderByCreatedAtDesc(parentId, coachId);
        List<UUID> tierIds = purchases.stream().map(SessionPackPurchase::getPackTierId).distinct().toList();
        Map<UUID, SessionPackTier> tiersById = sessionPackTierRepository.findAllById(tierIds).stream()
            .collect(Collectors.toMap(SessionPackTier::getPackTierId, t -> t));
        return purchases.stream()
            .map(p -> {
                SessionPackTier tier = tiersById.get(p.getPackTierId());
                String label = tier != null ? tier.getLabel() : null;
                int sessionCount = tier != null ? tier.getSessionCount() : 0;
                return toResponse(p, label, sessionCount);
            })
            .toList();
    }

    @Transactional
    public void extendPack(Long coachUserId, UUID purchaseId) {
        CoachProfile coach = coachProfileRepository.findByUserId(coachUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));

        SessionPackPurchase purchase = lockRetryer.withBoundedRetry("SessionPackPaymentService.extendPack",
            () -> sessionPackPurchaseRepository.findByIdForUpdate(purchaseId)
                .orElseThrow(() -> new ResourceNotFoundException("Session pack purchase not found", "session_pack_purchase")));

        if (!purchase.getCoachId().equals(coach.getId())) {
            throw new OperationNotAllowedException("Coach does not own this session pack", SecurityError.MISSING_RIGHTS);
        }

        if (purchase.getExtendedAt() != null) {
            // Already extended by a concurrent request that committed first — 409, not 422:
            // this is a conflict with existing state, not an invalid request. Must be checked
            // before the timing window below: once extended, expiresAt has already moved forward
            // by 30 days, which would otherwise make the recomputed window check fire instead
            // and mask the real conflict behind a generic "not eligible" 422.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "payment.packAlreadyExtended");
        }

        Instant now = Instant.now();
        Instant windowStart = purchase.getExpiresAt().minus(EXTENSION_WINDOW);
        if (now.isBefore(windowStart) || now.isAfter(purchase.getExpiresAt())) {
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
        // Concurrency note (skillars-deferred-103 AC1): two racing transactions may both deactivate
        // overlapping sets and both attempt an insert. The DB partial unique index
        // idx_spt_one_active_per_coach (V62:58) lets exactly one insert commit and rejects the other.
        // ApiAdvice maps this constraint violation to 409 Conflict (idempotent-retry collision).
        // The deactivate loop is safe: neither transaction's writes are visible to the other until
        // commit; the loser's whole transaction rolls back, including its deactivations.
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

    /**
     * Not {@code @Transactional} (fix/payment-pack-integrity): one repository read, then a Stripe round-trip
     * that must not hold a pooled connection open.
     */
    public SavedPaymentMethodResponse getSavedPaymentMethod(Long parentId) {
        String paymentMethodId = stripeCustomerRepository.findById(parentId)
            .map(StripeCustomer::getStripePaymentMethodId)
            .orElse(null);
        if (paymentMethodId == null || paymentMethodId.isBlank()) {
            return new SavedPaymentMethodResponse(false, null, null, null, null);
        }
        try {
            PaymentMethod paymentMethod = stripeClient.retrievePaymentMethod(paymentMethodId);
            PaymentMethod.Card card = paymentMethod.getCard();
            if (card == null) {
                return new SavedPaymentMethodResponse(true, null, null, null, null);
            }
            return new SavedPaymentMethodResponse(
                true, card.getBrand(), card.getLast4(), card.getExpMonth(), card.getExpYear());
        } catch (StripeException e) {
            log.warn("Failed to retrieve saved payment method from Stripe: parentId={}", parentId, e);
            return new SavedPaymentMethodResponse(true, null, null, null, null);
        }
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

    private SessionPackPurchase createPurchase(Long parentId, Long playerId, SessionPackTier tier, String paymentIntentId) {
        SessionPackPurchase purchase = new SessionPackPurchase();
        purchase.setParentId(parentId);
        purchase.setPlayerId(playerId);
        purchase.setCoachId(tier.getCoachId());
        purchase.setPackTierId(tier.getPackTierId());
        purchase.setPricePerSession(tier.getPricePerSession());
        purchase.setAmountCharged(tier.getTotalPrice());
        // feat/coach-payouts-pack-and-credit (V186): what each completed session's coach payout is
        // computed from — locked here, because the tier and platform.commission.rate can both change
        // before the pack's sessions are played.
        purchase.setSessionCount(tier.getSessionCount());
        purchase.setCommissionRate(currentCommissionRate());
        purchase.setRemainingSessions(tier.getSessionCount());
        purchase.setExpiresAt(Instant.now().plus(60, ChronoUnit.DAYS));
        purchase.setStripePaymentIntentId(paymentIntentId);
        return sessionPackPurchaseRepository.save(purchase);
    }

    /**
     * {@code platform.commission.rate} now, or null when unreadable — a malformed config value must not
     * fail a purchase the payer has already been charged for; the payout then falls back to the live
     * rate, as for a pre-V186 pack (mirrors {@code BookingPaymentPersistenceService.currentCommissionRate}).
     */
    private BigDecimal currentCommissionRate() {
        try {
            String raw = configService.getString("platform.commission.rate");
            return raw == null ? null : new BigDecimal(raw.strip());
        } catch (IllegalStateException | NumberFormatException e) {
            log.error("Could not stamp session_pack_purchases.commission_rate — platform.commission.rate "
                + "unreadable ({}); leaving NULL, pack payouts will fall back to the live rate", e.getMessage());
            return null;
        }
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
            purchase.getStripePaymentIntentId(),
            purchase.getPlayerId(),
            purchase.getCoachId(),
            purchase.getPausedUntil(),
            computeStatus(purchase),
            purchase.getPausedFrom(),
            purchase.getRevokedReason()
        );
    }

    private String computeStatus(SessionPackPurchase purchase) {
        return computeStatus(purchase, Instant.now());
    }

    /**
     * Status precedence shared with {@link CoachSoldPackQueryService} (feat/coach-workflow-gaps A20) so the
     * coach's sold-pack list and the buyer's pack view can never disagree.
     *
     * <p>fix/pack-status-badges: {@code REVOKED} (the remaining sessions were taken away — see
     * {@code revokedReason}) outranks {@code EXHAUSTED}, which a revocation also produces by zeroing the
     * sessions; {@code FROZEN} (an open card dispute on the pack's charge — nothing can be booked or spent)
     * outranks the pause and expiry states. Neither is {@code ACTIVE}, so every "usable pack" filter on the
     * client excludes them.
     */
    static String computeStatus(SessionPackPurchase purchase, Instant now) {
        if (purchase.isRevoked()) {
            return "REVOKED";
        }
        if (purchase.getRemainingSessions() == 0) {
            return "EXHAUSTED";
        }
        if (purchase.isDisputeFrozen()) {
            return "FROZEN";
        }
        // fix/payment-pack-integrity: paused over [pausedFrom, pausedUntil) only — a pause scheduled for later
        // leaves the pack ACTIVE until it starts.
        if (purchase.isPausedAt(now)) {
            return "PAUSED";
        }
        if (purchase.getExpiresAt().isBefore(now)) {
            return "EXPIRED";
        }
        return "ACTIVE";
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
