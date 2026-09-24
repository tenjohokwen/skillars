package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.contract.CoachSubscriptionTier;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachSubscription;
import com.softropic.skillars.platform.marketplace.repo.CoachSubscriptionRepository;
import com.softropic.skillars.platform.payment.contract.CoachSubscriptionResponse;
import com.softropic.skillars.platform.payment.contract.PlayerSubscriptionResponse;
import com.softropic.skillars.platform.payment.contract.TierInfoResponse;
import com.softropic.skillars.platform.payment.contract.event.SubscriptionExpiredEvent;
import com.softropic.skillars.platform.payment.repo.CoachSubscriptionChange;
import com.softropic.skillars.platform.payment.repo.CoachSubscriptionChangeRepository;
import com.softropic.skillars.platform.payment.repo.PaymentCoachSubscription;
import com.softropic.skillars.platform.payment.repo.PaymentCoachSubscriptionRepository;
import com.softropic.skillars.platform.payment.repo.PaymentPlayerSubscription;
import com.softropic.skillars.platform.payment.repo.PaymentPlayerSubscriptionRepository;
import com.softropic.skillars.platform.payment.repo.PlayerSubscriptionChange;
import com.softropic.skillars.platform.payment.repo.PlayerSubscriptionChangeRepository;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import com.softropic.skillars.platform.security.repo.ParentPlayerLinkRepository;
import com.stripe.model.Subscription;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionService {

    private final PaymentCoachSubscriptionRepository paymentCoachSubscriptionRepository;
    private final PaymentPlayerSubscriptionRepository paymentPlayerSubscriptionRepository;
    private final CoachSubscriptionChangeRepository coachSubscriptionChangeRepository;
    private final PlayerSubscriptionChangeRepository playerSubscriptionChangeRepository;
    private final CoachSubscriptionRepository coachSubscriptionRepository;
    private final StripeCustomerRepository stripeCustomerRepository;
    private final ConfigService configService;
    private final StripeClient stripeClient;
    private final ApplicationEventPublisher eventPublisher;
    private final ParentPlayerLinkRepository parentPlayerLinkRepository;
    private final TransactionTemplate transactionTemplate;
    private final CoachProfileRepository coachProfileRepository;
    private final PessimisticLockRetryer lockRetryer;

    /** Self-reference so @Transactional on persist* methods is honoured via the Spring proxy. */
    @Autowired @Lazy
    private SubscriptionService self;

    private static final List<String> COACH_TIERS = Arrays.asList("SCOUT", "INSTRUCTOR", "ACADEMY");
    private static final List<String> PLAYER_TIERS = Arrays.asList("ATHLETE", "SEMI_PRO", "PRO");
    private static final Set<String> ACTIVE_STATUSES = Set.of("ACTIVE", "TRIALLING");

    // ─── Tier Info ─────────────────────────────────────────────────────────────

    public List<TierInfoResponse> getCoachTiers() {
        return COACH_TIERS.stream()
            .map(tier -> {
                List<String> features = loadFeaturesFromConfig("coach", tier);
                String monthlyPrice = loadPriceFromConfig("coach", tier, "monthly");
                return new TierInfoResponse(tier, features, monthlyPrice, null);
            })
            .toList();
    }

    public List<TierInfoResponse> getPlayerTiers() {
        return PLAYER_TIERS.stream()
            .map(tier -> {
                List<String> features = loadFeaturesFromConfig("player", tier);
                String monthlyPrice = "SEMI_PRO".equals(tier) || "PRO".equals(tier)
                    ? null
                    : loadPriceFromConfig("player", tier, "monthly");
                String annualPrice = loadPriceFromConfig("player", tier, "yearly");
                return new TierInfoResponse(tier, features, monthlyPrice, annualPrice);
            })
            .toList();
    }

    // ─── Current Subscription Queries ──────────────────────────────────────────

    @Transactional
    public CoachSubscriptionResponse getCoachSubscription(UUID coachId) {
        PaymentCoachSubscription sub = findOrCreateCoachSubscription(coachId);
        return toCoachResponse(sub);
    }

    @Transactional
    public PlayerSubscriptionResponse getPlayerSubscription(Long parentUserId, Long playerId) {
        assertPlayerOwnership(parentUserId, playerId);
        PaymentPlayerSubscription sub = findOrCreatePlayerSubscription(playerId);
        return toPlayerResponse(sub);
    }

    // ─── Coach Subscribe ────────────────────────────────────────────────────────

    public CoachSubscriptionResponse subscribeCoach(UUID coachId, Long coachUserId, String tier) {
        if ("SCOUT".equalsIgnoreCase(tier)) {
            throw new com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException(
                "payment.subscription.cannotSubscribeToFreeTier");
        }

        PaymentCoachSubscription sub = findOrCreateCoachSubscription(coachId);
        if (ACTIVE_STATUSES.contains(sub.getStatus()) && sub.getStripeSubscriptionId() != null) {
            throw new com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException(
                "payment.subscription.alreadyActive");
        }

        String priceId = configService.getString(
            "subscription.coach." + tier.toLowerCase() + ".monthly.priceId");
        if (priceId == null || priceId.isBlank()) {
            throw new com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException(
                "payment.subscription.priceNotConfigured");
        }

        com.softropic.skillars.platform.payment.repo.StripeCustomer stripeCustomer =
            stripeCustomerRepository.findById(coachUserId)
                .orElseThrow(() -> new com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException(
                    "payment.noPaymentMethod"));
        String paymentMethodId = stripeCustomer.getStripePaymentMethodId();
        if (paymentMethodId == null || paymentMethodId.isBlank()) {
            throw new com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException(
                "payment.noPaymentMethod");
        }
        String stripeCustomerId = stripeCustomer.getStripeCustomerId();

        // Stripe call outside @Transactional
        Subscription stripeSub;
        try {
            // No attachPaymentMethod call here: the payment method was already attached to this
            // exact customer when it was saved (POST /setup-intent creates a customer-scoped
            // SetupIntent; confirmCardSetup + POST /save-payment-method complete it). Calling
            // attach again on an already-attached payment method throws on Stripe's side.
            stripeSub = stripeClient.createSubscription(stripeCustomerId, priceId, paymentMethodId);
        } catch (com.stripe.exception.StripeException e) {
            log.error("[COACH_SUBSCRIBE_STRIPE_FAILED coachId={}]", coachId, e);
            throw new com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException(
                "payment.subscription.stripeError");
        }

        return self.persistCoachSubscription(coachId, sub, tier, stripeSub);
    }

    @Transactional
    public CoachSubscriptionResponse persistCoachSubscription(UUID coachId, PaymentCoachSubscription sub,
                                                              String tier, Subscription stripeSub) {
        sub.setTier(tier);
        sub.setStripeSubscriptionId(stripeSub.getId());
        sub.setStatus(normalizeStripeStatus(stripeSub.getStatus()));
        if (stripeSub.getCurrentPeriodEnd() != null) {
            sub.setCurrentPeriodEnd(Instant.ofEpochSecond(stripeSub.getCurrentPeriodEnd()));
        }
        paymentCoachSubscriptionRepository.save(sub);

        // Keep marketplace.coach_subscriptions in sync
        //
        // Code review 2026-09-23 (Decision 3): syncMarketplaceTier's Fix 4 lock (see its own comment
        // below) now contends with CoachProfileService.publishProfile's lock on the same coach_profiles
        // row. subscribeCoach calls Stripe deliberately outside this transaction, so a contended lock
        // failing here would roll back this whole method and lose the payment row after Stripe has
        // already charged the coach — the worst available outcome, worse than a temporarily stale
        // marketplace tier. marketplace.coach_subscriptions is a derived projection re-synced by
        // applyPendingChanges/checkPastDueGracePeriod, and getCoachSubscriptionTier's SCOUT default
        // makes a stale/missing tier fail closed in the meantime, so deferring the sync on lock
        // contention is safe.
        try {
            syncMarketplaceTier(coachId, tier);
        } catch (PessimisticLockingFailureException e) {
            log.warn("[COACH_SUBSCRIBE_TIER_SYNC_DEFERRED coachId={} tier={}]", coachId, tier, e);
        }

        log.info("[COACH_SUBSCRIBED coachId={} tier={} status={}]", coachId, tier, sub.getStatus());
        return toCoachResponse(sub);
    }

    // ─── Coach Change Tier ───────────────────────────────────────────────────────

    public void changeCoachTier(UUID coachId, String newTier) {
        PaymentCoachSubscription sub = paymentCoachSubscriptionRepository.findByCoachId(coachId)
            .orElseThrow(() -> new com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException(
                "payment.subscription.notFound"));

        String currentTier = sub.getTier();
        int currentRank = COACH_TIERS.indexOf(currentTier.toUpperCase());
        int newRank = COACH_TIERS.indexOf(newTier.toUpperCase());
        if (newRank < 0) {
            throw new com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException(
                "payment.subscription.invalidTier");
        }

        if (newRank > currentRank) {
            // Upgrade: immediate, Stripe prorates
            if (sub.getStripeSubscriptionId() == null) {
                throw new com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException(
                    "payment.subscription.noActiveStripeSubscription");
            }
            String newPriceId = configService.getString(
                "subscription.coach." + newTier.toLowerCase() + ".monthly.priceId");
            if (newPriceId == null || newPriceId.isBlank()) {
                throw new com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException(
                    "payment.subscription.priceNotConfigured");
            }
            try {
                stripeClient.updateSubscriptionTier(sub.getStripeSubscriptionId(), newPriceId);
            } catch (com.stripe.exception.StripeException e) {
                log.error("[COACH_UPGRADE_STRIPE_FAILED coachId={}]", coachId, e);
                throw new com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException(
                    "payment.subscription.stripeError");
            }
            self.persistCoachTierUpgrade(coachId, sub, newTier);
        } else {
            // Downgrade: scheduled at period end
            self.persistCoachTierDowngrade(coachId, sub, newTier);
        }
    }

    @Transactional
    public void persistCoachTierUpgrade(UUID coachId, PaymentCoachSubscription sub, String newTier) {
        // Void any pending downgrade before applying upgrade
        voidPendingCoachDowngrade(coachId);
        sub.setTier(newTier);
        paymentCoachSubscriptionRepository.save(sub);
        // skillars-deferred-132 AC1 Fix 3: mirrors persistCoachSubscription's own Decision 3
        // catch-and-defer above — unlike that path, changeCoachTier's own Stripe call
        // (stripeClient.updateSubscriptionTier) already happened outside this transaction before
        // this method was invoked, so letting a contended syncMarketplaceTier lock roll back this
        // whole method would leave Stripe billing at newTier while payment.coach_subscriptions rolls
        // back to the OLD tier — worse than a temporarily stale marketplace projection. This story's
        // own reconcileMarketplaceTiers sweep now closes the temporary staleness this defer creates.
        try {
            syncMarketplaceTier(coachId, newTier);
        } catch (PessimisticLockingFailureException e) {
            log.warn("[COACH_TIER_UPGRADE_SYNC_DEFERRED coachId={} newTier={}]", coachId, newTier, e);
        }
        log.info("[COACH_TIER_UPGRADED coachId={} newTier={}]", coachId, newTier);
    }

    @Transactional
    public void persistCoachTierDowngrade(UUID coachId, PaymentCoachSubscription sub, String newTier) {
        // Void any existing pending downgrade
        voidPendingCoachDowngrade(coachId);

        CoachSubscriptionChange change = new CoachSubscriptionChange();
        change.setCoachId(coachId);
        change.setFromTier(sub.getTier());
        change.setToTier(newTier);
        change.setEffectiveAt(sub.getCurrentPeriodEnd() != null ? sub.getCurrentPeriodEnd() : Instant.now());
        change.setTriggerSource("SCHEDULED");
        coachSubscriptionChangeRepository.save(change);
        log.info("[COACH_DOWNGRADE_SCHEDULED coachId={} newTier={} effectiveAt={}]",
            coachId, newTier, change.getEffectiveAt());
    }

    private void voidPendingCoachDowngrade(UUID coachId) {
        coachSubscriptionChangeRepository.findPendingForCoach(coachId).ifPresent(existing -> {
            existing.setApplied(true);
            existing.setVoidedAt(Instant.now());
            coachSubscriptionChangeRepository.save(existing);
        });
    }

    // ─── Coach Cancel ────────────────────────────────────────────────────────────

    public void cancelCoachSubscription(UUID coachId) {
        PaymentCoachSubscription sub = paymentCoachSubscriptionRepository.findByCoachId(coachId)
            .orElseThrow(() -> new com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException(
                "payment.subscription.notFound"));

        if (sub.getStripeSubscriptionId() == null) {
            throw new com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException(
                "payment.subscription.noActiveStripeSubscription");
        }

        try {
            Instant periodEnd = stripeClient.cancelSubscriptionAtPeriodEnd(sub.getStripeSubscriptionId());
            self.persistCoachCancellation(sub, periodEnd);
        } catch (com.stripe.exception.StripeException e) {
            log.error("[COACH_CANCEL_STRIPE_FAILED coachId={}]", coachId, e);
            throw new com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException(
                "payment.subscription.stripeError");
        }
    }

    @Transactional
    public void persistCoachCancellation(PaymentCoachSubscription sub, Instant periodEnd) {
        sub.setCancelAtPeriodEnd(true);
        if (periodEnd != null) sub.setCurrentPeriodEnd(periodEnd);
        paymentCoachSubscriptionRepository.save(sub);
        log.info("[COACH_CANCEL_AT_PERIOD_END coachId={} periodEnd={}]", sub.getCoachId(), periodEnd);
    }

    // ─── Player Subscribe ────────────────────────────────────────────────────────

    public PlayerSubscriptionResponse subscribePlayer(Long parentUserId, Long playerId,
                                                       String tier, String billingInterval) {
        assertPlayerOwnership(parentUserId, playerId);
        validatePlayerTierInterval(tier, billingInterval);

        PaymentPlayerSubscription sub = findOrCreatePlayerSubscription(playerId);
        if (ACTIVE_STATUSES.contains(sub.getStatus()) && sub.getStripeSubscriptionId() != null) {
            throw new com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException(
                "payment.subscription.alreadyActive");
        }

        String priceId = configService.getString(
            "subscription.player." + tier.toLowerCase() + "." + billingInterval.toLowerCase() + ".priceId");
        if (priceId == null || priceId.isBlank()) {
            throw new com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException(
                "payment.subscription.priceNotConfigured");
        }

        com.softropic.skillars.platform.payment.repo.StripeCustomer stripeCustomer =
            stripeCustomerRepository.findById(parentUserId)
                .orElseThrow(() -> new com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException(
                    "payment.noPaymentMethod"));
        String paymentMethodId = stripeCustomer.getStripePaymentMethodId();
        if (paymentMethodId == null || paymentMethodId.isBlank()) {
            throw new com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException(
                "payment.noPaymentMethod");
        }
        String stripeCustomerId = stripeCustomer.getStripeCustomerId();

        Subscription stripeSub;
        try {
            // No attachPaymentMethod call here: the payment method was already attached to this
            // exact customer when it was saved (POST /setup-intent creates a customer-scoped
            // SetupIntent; confirmCardSetup + POST /save-payment-method complete it). Calling
            // attach again on an already-attached payment method throws on Stripe's side.
            stripeSub = stripeClient.createSubscription(stripeCustomerId, priceId, paymentMethodId);
        } catch (com.stripe.exception.StripeException e) {
            log.error("[PLAYER_SUBSCRIBE_STRIPE_FAILED playerId={}]", playerId, e);
            throw new com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException(
                "payment.subscription.stripeError");
        }

        return self.persistPlayerSubscription(sub, tier, billingInterval, stripeSub);
    }

    @Transactional
    public PlayerSubscriptionResponse persistPlayerSubscription(PaymentPlayerSubscription sub,
                                                                String tier, String billingInterval,
                                                                Subscription stripeSub) {
        sub.setTier(tier);
        sub.setBillingInterval(billingInterval);
        sub.setStripeSubscriptionId(stripeSub.getId());
        sub.setStatus(normalizeStripeStatus(stripeSub.getStatus()));
        if (stripeSub.getCurrentPeriodEnd() != null) {
            sub.setCurrentPeriodEnd(Instant.ofEpochSecond(stripeSub.getCurrentPeriodEnd()));
        }
        paymentPlayerSubscriptionRepository.save(sub);
        log.info("[PLAYER_SUBSCRIBED playerId={} tier={} interval={} status={}]",
            sub.getPlayerId(), tier, billingInterval, sub.getStatus());
        return toPlayerResponse(sub);
    }

    // ─── Player Change Tier ──────────────────────────────────────────────────────

    public void changePlayerTier(Long parentUserId, Long playerId, String newTier) {
        assertPlayerOwnership(parentUserId, playerId);

        PaymentPlayerSubscription sub = paymentPlayerSubscriptionRepository.findByPlayerId(playerId)
            .orElseThrow(() -> new com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException(
                "payment.subscription.notFound"));

        // Validate new tier's billing interval constraints
        validatePlayerTierInterval(newTier, sub.getBillingInterval());

        String currentTier = sub.getTier();
        int currentRank = PLAYER_TIERS.indexOf(currentTier.toUpperCase());
        int newRank = PLAYER_TIERS.indexOf(newTier.toUpperCase());
        if (newRank < 0) {
            throw new com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException(
                "payment.subscription.invalidTier");
        }

        if (newRank > currentRank) {
            if (sub.getStripeSubscriptionId() == null) {
                throw new com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException(
                    "payment.subscription.noActiveStripeSubscription");
            }
            String newPriceId = configService.getString(
                "subscription.player." + newTier.toLowerCase() + "." + sub.getBillingInterval().toLowerCase() + ".priceId");
            if (newPriceId == null || newPriceId.isBlank()) {
                throw new com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException(
                    "payment.subscription.priceNotConfigured");
            }
            try {
                stripeClient.updateSubscriptionTier(sub.getStripeSubscriptionId(), newPriceId);
            } catch (com.stripe.exception.StripeException e) {
                log.error("[PLAYER_UPGRADE_STRIPE_FAILED playerId={}]", playerId, e);
                throw new com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException(
                    "payment.subscription.stripeError");
            }
            self.persistPlayerTierUpgrade(sub, newTier);
        } else {
            self.persistPlayerTierDowngrade(sub, newTier);
        }
    }

    @Transactional
    public void persistPlayerTierUpgrade(PaymentPlayerSubscription sub, String newTier) {
        voidPendingPlayerDowngrade(sub.getPlayerId());
        sub.setTier(newTier);
        paymentPlayerSubscriptionRepository.save(sub);
        log.info("[PLAYER_TIER_UPGRADED playerId={} newTier={}]", sub.getPlayerId(), newTier);
    }

    @Transactional
    public void persistPlayerTierDowngrade(PaymentPlayerSubscription sub, String newTier) {
        voidPendingPlayerDowngrade(sub.getPlayerId());

        PlayerSubscriptionChange change = new PlayerSubscriptionChange();
        change.setPlayerId(sub.getPlayerId());
        change.setFromTier(sub.getTier());
        change.setToTier(newTier);
        change.setEffectiveAt(sub.getCurrentPeriodEnd() != null ? sub.getCurrentPeriodEnd() : Instant.now());
        change.setTriggerSource("SCHEDULED");
        playerSubscriptionChangeRepository.save(change);
        log.info("[PLAYER_DOWNGRADE_SCHEDULED playerId={} newTier={} effectiveAt={}]",
            sub.getPlayerId(), newTier, change.getEffectiveAt());
    }

    private void voidPendingPlayerDowngrade(Long playerId) {
        playerSubscriptionChangeRepository.findPendingForPlayer(playerId).ifPresent(existing -> {
            existing.setApplied(true);
            existing.setVoidedAt(Instant.now());
            playerSubscriptionChangeRepository.save(existing);
        });
    }

    // ─── Player Cancel ────────────────────────────────────────────────────────────

    public void cancelPlayerSubscription(Long parentUserId, Long playerId) {
        assertPlayerOwnership(parentUserId, playerId);

        PaymentPlayerSubscription sub = paymentPlayerSubscriptionRepository.findByPlayerId(playerId)
            .orElseThrow(() -> new com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException(
                "payment.subscription.notFound"));

        if (sub.getStripeSubscriptionId() == null) {
            throw new com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException(
                "payment.subscription.noActiveStripeSubscription");
        }

        try {
            Instant periodEnd = stripeClient.cancelSubscriptionAtPeriodEnd(sub.getStripeSubscriptionId());
            self.persistPlayerCancellation(sub, periodEnd);
        } catch (com.stripe.exception.StripeException e) {
            log.error("[PLAYER_CANCEL_STRIPE_FAILED playerId={}]", playerId, e);
            throw new com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException(
                "payment.subscription.stripeError");
        }
    }

    @Transactional
    public void persistPlayerCancellation(PaymentPlayerSubscription sub, Instant periodEnd) {
        sub.setCancelAtPeriodEnd(true);
        if (periodEnd != null) sub.setCurrentPeriodEnd(periodEnd);
        paymentPlayerSubscriptionRepository.save(sub);
        log.info("[PLAYER_CANCEL_AT_PERIOD_END playerId={} periodEnd={}]", sub.getPlayerId(), periodEnd);
    }

    // ─── Scheduled: Apply Pending Changes ────────────────────────────────────────

    // skillars-deferred-116 AC1/AC2: restructured from a single method-level @Transactional wrapping
    // the whole batch (one bad row rolled back every sibling coach/player change for the entire run)
    // to short-transaction batch load + per-item transactionTemplate.execute(...) wrapped in
    // try/catch, mirroring SessionPackForfeitureScheduler's established pattern in this same module.
    // The batch-load transaction's commit is also what releases findPendingForScheduler's
    // `FOR UPDATE SKIP LOCKED` row locks before per-item writes begin — see AC3's @SchedulerLock,
    // which closes the resulting concurrent-re-selection gap.
    public void applyPendingChanges() {
        Instant now = Instant.now();

        List<CoachSubscriptionChange> coachChanges = transactionTemplate.execute(
            status -> coachSubscriptionChangeRepository.findPendingForScheduler(now));
        if (coachChanges == null) coachChanges = List.of();
        log.info("[SUBSCRIPTION_CHANGE_APPLICATOR] {} pending coach downgrade(s) loaded", coachChanges.size());
        for (CoachSubscriptionChange change : coachChanges) {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    paymentCoachSubscriptionRepository.findByCoachId(change.getCoachId()).ifPresent(sub -> {
                        sub.setTier(change.getToTier());
                        paymentCoachSubscriptionRepository.save(sub);
                        syncMarketplaceTier(change.getCoachId(), change.getToTier());
                    });
                    change.setApplied(true);
                    coachSubscriptionChangeRepository.save(change);
                    log.info("[COACH_SCHEDULED_DOWNGRADE_APPLIED coachId={} toTier={}]",
                        change.getCoachId(), change.getToTier());
                });
            } catch (Exception e) {
                // Deliberately left with applied=false: findPendingForScheduler filters on exactly
                // that column, so a malformed/stale to_tier (or any other failure) is naturally
                // re-selected on tomorrow's run once the underlying data issue is fixed.
                log.error("Failed to apply scheduled coach subscription downgrade {}",
                    change.getChangeId(), e);
            }
        }

        List<PlayerSubscriptionChange> playerChanges = transactionTemplate.execute(
            status -> playerSubscriptionChangeRepository.findPendingForScheduler(now));
        if (playerChanges == null) playerChanges = List.of();
        log.info("[SUBSCRIPTION_CHANGE_APPLICATOR] {} pending player downgrade(s) loaded", playerChanges.size());
        for (PlayerSubscriptionChange change : playerChanges) {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    // No syncMarketplaceTier equivalent here, deliberately: players have no
                    // marketplace-visible tier (marketplace.coach_subscriptions only models coaches).
                    // Confirmed intentional at skillars-deferred-116 story creation, not an oversight.
                    paymentPlayerSubscriptionRepository.findByPlayerId(change.getPlayerId()).ifPresent(sub -> {
                        sub.setTier(change.getToTier());
                        paymentPlayerSubscriptionRepository.save(sub);
                    });
                    change.setApplied(true);
                    playerSubscriptionChangeRepository.save(change);
                    log.info("[PLAYER_SCHEDULED_DOWNGRADE_APPLIED playerId={} toTier={}]",
                        change.getPlayerId(), change.getToTier());
                    // Only publish SubscriptionExpiredEvent for WEBHOOK_DELETED source (scheduled
                    // downgrades are tier changes, not subscription deletions; video lifecycle only
                    // triggers on deletion)
                });
            } catch (Exception e) {
                log.error("Failed to apply scheduled player subscription downgrade {}",
                    change.getChangeId(), e);
            }
        }
    }

    // ─── Scheduled: Grace Period Check ───────────────────────────────────────────

    // skillars-deferred-116 AC2: identical restructure to applyPendingChanges() above, for the same
    // reason. A row that fails to downgrade stays PAST_DUE and past the cutoff, so it stays eligible
    // and is naturally re-selected on tomorrow's run with no extra bookkeeping needed.
    public void checkPastDueGracePeriod() {
        // Read per invocation — never cache (ConfigService has its own internal TTL cache).
        // skillars-deferred-107 AC2: neg → grace cutoff moves into the future (nobody / everybody
        // downgraded); 0 = "no grace" is legitimate. Mirrors ConfigBounds.SUBSCRIPTION_PAST_DUE_GRACE_PERIOD_DAYS.
        long gracePeriodDays = configService.getBoundedLong("subscription.pastDue.gracePeriodDays", 0L, 365L);
        Instant graceCutoff = Instant.now().minus(gracePeriodDays, java.time.temporal.ChronoUnit.DAYS);

        List<PaymentCoachSubscription> pastDueCoaches = transactionTemplate.execute(
            status -> paymentCoachSubscriptionRepository.findByStatusAndPastDueSinceBefore("PAST_DUE", graceCutoff));
        if (pastDueCoaches == null) pastDueCoaches = List.of();
        log.info("[SUBSCRIPTION_GRACE_PERIOD_CHECKER] {} past-due coach subscription(s) loaded", pastDueCoaches.size());
        for (PaymentCoachSubscription sub : pastDueCoaches) {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    sub.setTier("SCOUT");
                    sub.setStatus("CANCELLED");
                    paymentCoachSubscriptionRepository.save(sub);
                    syncMarketplaceTier(sub.getCoachId(), "SCOUT");
                    log.warn("[COACH_GRACE_PERIOD_EXPIRED coachId={} downgradedTo=SCOUT]", sub.getCoachId());
                });
            } catch (Exception e) {
                log.error("Failed to apply grace-period downgrade for coach {}", sub.getCoachId(), e);
            }
        }

        List<PaymentPlayerSubscription> pastDuePlayers = transactionTemplate.execute(
            status -> paymentPlayerSubscriptionRepository.findByStatusAndPastDueSinceBefore("PAST_DUE", graceCutoff));
        if (pastDuePlayers == null) pastDuePlayers = List.of();
        log.info("[SUBSCRIPTION_GRACE_PERIOD_CHECKER] {} past-due player subscription(s) loaded", pastDuePlayers.size());
        for (PaymentPlayerSubscription sub : pastDuePlayers) {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    // No syncMarketplaceTier equivalent here, deliberately — see the identical note
                    // in applyPendingChanges()'s player loop: players have no marketplace-visible tier.
                    sub.setTier("ATHLETE");
                    sub.setStatus("CANCELLED");
                    paymentPlayerSubscriptionRepository.save(sub);
                    log.warn("[PLAYER_GRACE_PERIOD_EXPIRED playerId={} downgradedTo=ATHLETE]", sub.getPlayerId());
                });
            } catch (Exception e) {
                log.error("Failed to apply grace-period downgrade for player {}", sub.getPlayerId(), e);
            }
        }
    }

    // ─── Scheduled: Marketplace Tier Reconciliation ──────────────────────────────

    /**
     * skillars-deferred-132 AC1 Fix 3. Neither {@link #applyPendingChanges()} nor
     * {@link #checkPastDueGracePeriod()} re-syncs a coach outside its own narrow trigger (a pending
     * scheduled downgrade, or a past-due status). A coach whose {@code subscribeCoach} upgrade hits
     * lock contention at exactly the wrong moment (Decision 3's own catch-and-defer in
     * {@link #persistCoachSubscription}, or {@link #persistCoachTierUpgrade}'s identical defer above)
     * is otherwise left reading a stale {@code marketplace.coach_subscriptions} tier indefinitely. This
     * sweep closes that gap directly: every {@code ACTIVE}/{@code TRIALLING} payment-side coach
     * subscription is compared against its marketplace projection, and any mismatch is corrected via
     * the same already-locked {@link #syncMarketplaceTier} write path the two sibling schedulers
     * already call — no new lock discipline needed here.
     *
     * <p>This is a payment → marketplace sweep only — it treats {@code payment.coach_subscriptions} as
     * the source of truth. It does NOT close the separate, still-open Stripe → payment reconciliation
     * residual (a coach with a Stripe subscription but no local {@code payment.coach_subscriptions}
     * row) the ledger calls for elsewhere.
     */
    public void reconcileMarketplaceTiers() {
        // Same status set countActiveByTier's own JPQL literal encodes (see
        // PaymentCoachSubscriptionRepository.findAllByStatusIn's own comment) — duplicated
        // deliberately rather than sharing a constant, per this codebase's literal-pin convention.
        List<PaymentCoachSubscription> active = transactionTemplate.execute(
            status -> paymentCoachSubscriptionRepository.findAllByStatusIn(List.of("ACTIVE", "TRIALLING")));
        if (active == null) active = List.of();
        log.info("[SUBSCRIPTION_TIER_RECONCILIATION] {} active/trialling coach subscription(s) loaded", active.size());
        for (PaymentCoachSubscription sub : active) {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    Optional<CoachSubscription> marketplace = coachSubscriptionRepository.findByCoachId(sub.getCoachId());
                    CoachSubscriptionTier paymentTier;
                    try {
                        paymentTier = CoachSubscriptionTier.valueOf(sub.getTier());
                    } catch (IllegalArgumentException e) {
                        // skillars-deferred-132 AC1 Fix 3: a payment row with a tier outside
                        // CoachSubscriptionTier's own values (COACH_TIERS above uses raw strings, not
                        // this enum) is skipped, not thrown — a per-row data issue must not abort the
                        // rest of the sweep.
                        log.error("[COACH_TIER_RECONCILE_SKIPPED coachId={} unrecognizedTier={}]",
                            sub.getCoachId(), sub.getTier(), e);
                        return;
                    }
                    if (marketplace.isEmpty() || marketplace.get().getTier() != paymentTier) {
                        syncMarketplaceTier(sub.getCoachId(), sub.getTier());
                        log.warn("[COACH_TIER_RECONCILED coachId={} tier={}]", sub.getCoachId(), sub.getTier());
                    }
                });
            } catch (Exception e) {
                log.error("Failed to reconcile marketplace tier for coach {}", sub.getCoachId(), e);
            }
        }
    }

    // ─── Webhook Handler ──────────────────────────────────────────────────────────

    @Transactional
    public void handleSubscriptionWebhook(String eventType, String stripeSubscriptionId,
                                           Map<String, Object> data) {
        if ("customer.subscription.updated".equals(eventType)) {
            handleSubscriptionUpdated(stripeSubscriptionId, data);
        } else if ("customer.subscription.deleted".equals(eventType)) {
            handleSubscriptionDeleted(stripeSubscriptionId, data);
        } else if ("invoice.payment_failed".equals(eventType)) {
            handleInvoicePaymentFailed(stripeSubscriptionId);
        }
    }

    private void handleSubscriptionUpdated(String stripeSubscriptionId, Map<String, Object> data) {
        // Only sync status/period fields — do NOT sync tier from Stripe (no priceId→tier map)
        Instant periodEnd = extractCurrentPeriodEnd(data);
        Boolean cancelAtPeriodEnd = extractCancelAtPeriodEnd(data);
        String newStatus = extractStatus(data);

        paymentCoachSubscriptionRepository.findByStripeSubscriptionId(stripeSubscriptionId)
            .ifPresent(sub -> {
                if (newStatus != null) sub.setStatus(normalizeStripeStatus(newStatus));
                if (periodEnd != null) sub.setCurrentPeriodEnd(periodEnd);
                if (cancelAtPeriodEnd != null) sub.setCancelAtPeriodEnd(cancelAtPeriodEnd);
                paymentCoachSubscriptionRepository.save(sub);
            });

        paymentPlayerSubscriptionRepository.findByStripeSubscriptionId(stripeSubscriptionId)
            .ifPresent(sub -> {
                if (newStatus != null) sub.setStatus(normalizeStripeStatus(newStatus));
                if (periodEnd != null) sub.setCurrentPeriodEnd(periodEnd);
                if (cancelAtPeriodEnd != null) sub.setCancelAtPeriodEnd(cancelAtPeriodEnd);
                paymentPlayerSubscriptionRepository.save(sub);
            });
    }

    private void handleSubscriptionDeleted(String stripeSubscriptionId, Map<String, Object> data) {
        paymentCoachSubscriptionRepository.findByStripeSubscriptionId(stripeSubscriptionId)
            .ifPresent(sub -> {
                sub.setTier("SCOUT");
                sub.setStatus("CANCELLED");
                sub.setStripeSubscriptionId(null);
                paymentCoachSubscriptionRepository.save(sub);
                syncMarketplaceTier(sub.getCoachId(), "SCOUT");
                log.info("[COACH_SUBSCRIPTION_DELETED coachId={}]", sub.getCoachId());
            });

        // Only publish SubscriptionExpiredEvent for player subscriptions (coaches have no video lifecycle)
        paymentPlayerSubscriptionRepository.findByStripeSubscriptionId(stripeSubscriptionId)
            .ifPresent(sub -> {
                String billingInterval = sub.getBillingInterval();
                Long playerId = sub.getPlayerId();
                sub.setTier("ATHLETE");
                sub.setStatus("CANCELLED");
                sub.setStripeSubscriptionId(null);
                paymentPlayerSubscriptionRepository.save(sub);
                log.info("[PLAYER_SUBSCRIPTION_DELETED playerId={}]", playerId);
                eventPublisher.publishEvent(
                    new SubscriptionExpiredEvent(this, playerId, billingInterval, Instant.now()));
            });
    }

    private void handleInvoicePaymentFailed(String stripeSubscriptionId) {
        paymentCoachSubscriptionRepository.findByStripeSubscriptionId(stripeSubscriptionId)
            .ifPresent(sub -> {
                sub.setStatus("PAST_DUE");
                // Only set pastDueSince on the first failure — do not overwrite on repeated failures
                if (sub.getPastDueSince() == null) sub.setPastDueSince(Instant.now());
                paymentCoachSubscriptionRepository.save(sub);
                log.warn("[COACH_PAYMENT_FAILED coachId={} pastDueSince={}]",
                    sub.getCoachId(), sub.getPastDueSince());
            });

        paymentPlayerSubscriptionRepository.findByStripeSubscriptionId(stripeSubscriptionId)
            .ifPresent(sub -> {
                sub.setStatus("PAST_DUE");
                if (sub.getPastDueSince() == null) sub.setPastDueSince(Instant.now());
                paymentPlayerSubscriptionRepository.save(sub);
                log.warn("[PLAYER_PAYMENT_FAILED playerId={} pastDueSince={}]",
                    sub.getPlayerId(), sub.getPastDueSince());
            });
    }

    // ─── Effective Tier Helpers ───────────────────────────────────────────────────

    public String getEffectiveCoachTier(UUID coachId) {
        return paymentCoachSubscriptionRepository.findByCoachId(coachId)
            .filter(sub -> ACTIVE_STATUSES.contains(sub.getStatus()))
            .map(PaymentCoachSubscription::getTier)
            .orElse("SCOUT");
    }

    public String getEffectivePlayerTier(Long playerId) {
        return paymentPlayerSubscriptionRepository.findByPlayerId(playerId)
            .filter(sub -> ACTIVE_STATUSES.contains(sub.getStatus()))
            .map(PaymentPlayerSubscription::getTier)
            .orElse("ATHLETE");
    }

    // ─── Private Helpers ──────────────────────────────────────────────────────────

    private PaymentCoachSubscription findOrCreateCoachSubscription(UUID coachId) {
        return paymentCoachSubscriptionRepository.findByCoachId(coachId)
            .orElseGet(() -> {
                PaymentCoachSubscription newSub = new PaymentCoachSubscription();
                newSub.setCoachId(coachId);
                return paymentCoachSubscriptionRepository.save(newSub);
            });
    }

    private PaymentPlayerSubscription findOrCreatePlayerSubscription(Long playerId) {
        return paymentPlayerSubscriptionRepository.findByPlayerId(playerId)
            .orElseGet(() -> {
                PaymentPlayerSubscription newSub = new PaymentPlayerSubscription();
                newSub.setPlayerId(playerId);
                return paymentPlayerSubscriptionRepository.save(newSub);
            });
    }

    // skillars-deferred-131 AC1 Fix 4: this method's INSERT branch needs a FOR KEY SHARE lock on
    // coach_profiles (coach_subscriptions_coach_id_fkey) at flush time, which blocks behind
    // CoachProfileService.publishProfile's own FOR UPDATE lock on that same row
    // (skillars-deferred-130) if the two run concurrently for the same coach — once publishProfile
    // commits first (having inserted its own coach_subscriptions row), this method's still-pending
    // INSERT resumes and violates coach_subscriptions_pkey, uncaught, rolling back
    // persistCoachSubscription's whole transaction while any Stripe subscription created earlier in
    // the same call chain (outside this DB transaction) survives orphaned. Closing the race at its
    // source — taking the SAME coach_profiles row lock publishProfile already takes — serializes the
    // two writers instead of racing them.
    //
    // The lock acquisition alone goes inside withBoundedRetry; the ifPresentOrElse writes below run
    // AFTER it returns, not inside it — PessimisticLockRetryerCallSiteAuditTest's DENYLIST rejects any
    // .save( inside a retried lambda, and withBoundedRetry's own contract requires the supplier be
    // read-only since it can legitimately execute more than once per logical call. The Postgres row
    // lock is held for the whole transaction, not just the lambda's duration, so running the writes
    // after withBoundedRetry returns preserves the same serialization guarantee.
    private void syncMarketplaceTier(UUID coachId, String tier) {
        lockRetryer.withBoundedRetry("SubscriptionService.syncMarketplaceTier", () -> coachProfileRepository.findByIdForUpdate(coachId)
            .orElseThrow(() -> new com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException(
                "payment.subscription.coachProfileNotFound")));

        coachSubscriptionRepository.findByCoachId(coachId).ifPresentOrElse(
            cs -> {
                cs.setTier(com.softropic.skillars.platform.marketplace.contract.CoachSubscriptionTier.valueOf(tier));
                coachSubscriptionRepository.save(cs);
            },
            () -> {
                CoachSubscription cs = new CoachSubscription();
                cs.setCoachId(coachId);
                cs.setTier(com.softropic.skillars.platform.marketplace.contract.CoachSubscriptionTier.valueOf(tier));
                coachSubscriptionRepository.save(cs);
            }
        );
    }

    private void assertPlayerOwnership(Long parentUserId, Long playerId) {
        if (!parentPlayerLinkRepository.existsByParentIdAndPlayerId(parentUserId, playerId)) {
            throw new com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException(
                "payment.subscription.playerOwnership");
        }
    }

    private void validatePlayerTierInterval(String tier, String billingInterval) {
        if (("SEMI_PRO".equalsIgnoreCase(tier) || "PRO".equalsIgnoreCase(tier))
                && !"YEARLY".equalsIgnoreCase(billingInterval)) {
            throw new com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException(
                "payment.subscription.invalidBillingInterval");
        }
    }

    private List<String> loadFeaturesFromConfig(String role, String tier) {
        try {
            String raw = configService.getString(role + ".tier." + tier.toLowerCase() + ".features");
            return Arrays.asList(raw.split(","));
        } catch (Exception e) {
            return List.of();
        }
    }

    private String loadPriceFromConfig(String role, String tier, String interval) {
        try {
            return configService.getString("subscription." + role + "." + tier.toLowerCase() + "." + interval.toLowerCase() + ".priceId");
        } catch (Exception e) {
            return null;
        }
    }

    private Instant extractCurrentPeriodEnd(Map<String, Object> data) {
        try {
            Object val = data.get("current_period_end");
            if (val instanceof Number n) return Instant.ofEpochSecond(n.longValue());
        } catch (Exception ignored) {}
        return null;
    }

    private Boolean extractCancelAtPeriodEnd(Map<String, Object> data) {
        try {
            Object val = data.get("cancel_at_period_end");
            if (val instanceof Boolean b) return b;
        } catch (Exception ignored) {}
        return null;
    }

    private String extractStatus(Map<String, Object> data) {
        try {
            Object val = data.get("status");
            if (val instanceof String s) return s;
        } catch (Exception ignored) {}
        return null;
    }

    private String normalizeStripeStatus(String stripeStatus) {
        return switch (stripeStatus) {
            case "active" -> "ACTIVE";
            case "past_due" -> "PAST_DUE";
            case "canceled", "cancelled" -> "CANCELLED";
            case "trialing" -> "TRIALLING";
            default -> stripeStatus.toUpperCase();
        };
    }

    private CoachSubscriptionResponse toCoachResponse(PaymentCoachSubscription sub) {
        return new CoachSubscriptionResponse(
            sub.getSubscriptionId(), sub.getTier(), sub.getStatus(),
            sub.getCurrentPeriodEnd(), sub.isCancelAtPeriodEnd());
    }

    private PlayerSubscriptionResponse toPlayerResponse(PaymentPlayerSubscription sub) {
        return new PlayerSubscriptionResponse(
            sub.getSubscriptionId(), sub.getPlayerId(), sub.getTier(),
            sub.getBillingInterval(), sub.getStatus(),
            sub.getCurrentPeriodEnd(), sub.isCancelAtPeriodEnd());
    }
}
