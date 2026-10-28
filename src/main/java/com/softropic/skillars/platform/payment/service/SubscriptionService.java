package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.contract.CoachSubscriptionTier;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachSubscription;
import com.softropic.skillars.platform.marketplace.repo.CoachSubscriptionRepository;
import com.softropic.skillars.platform.payment.contract.CoachSubscriptionResponse;
import com.softropic.skillars.platform.payment.contract.PlayerSubscriptionResponse;
import com.softropic.skillars.platform.payment.contract.SubscriptionEntitlement;
import com.softropic.skillars.platform.payment.contract.TierInfoResponse;
import com.softropic.skillars.platform.payment.contract.event.SubscriptionExpiredEvent;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
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
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.stripe.exception.CardException;
import com.stripe.exception.StripeException;
import com.stripe.model.Subscription;
import com.stripe.param.SubscriptionListParams;
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
import java.util.Objects;
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
    // skillars-deferred-135 AC2: reuses StripeWebhookService's own resolveCoachAndAlertIfOrphaned
    // resolution chain for the new Stripe->payment reconciliation sweep below. Plain (non-@Lazy)
    // constructor injection is safe here: StripeWebhookService's OWN reverse dependency on
    // SubscriptionService is @Lazy field-injected (see that class's own `subscriptionService` field
    // comment), so StripeWebhookService's constructor never needs a SubscriptionService bean to exist
    // first, and there is no circular-construction cycle.
    private final StripeWebhookService stripeWebhookService;
    private final PlayerProfileRepository playerProfileRepository;

    /** Self-reference so @Transactional on persist* methods is honoured via the Spring proxy. */
    @Autowired @Lazy
    private SubscriptionService self;

    private static final List<String> COACH_TIERS = Arrays.asList("SCOUT", "INSTRUCTOR", "ACADEMY");
    private static final List<String> PLAYER_TIERS = Arrays.asList("ATHLETE", "SEMI_PRO", "PRO");
    private static final Set<String> ACTIVE_STATUSES = Set.of("ACTIVE", "TRIALLING");
    private static final Set<String> BILLING_HEALTHY = SubscriptionEntitlement.BILLING_HEALTHY_STATUSES;
    private static final String COACH_FREE_TIER = SubscriptionEntitlement.COACH_FREE_TIER;
    private static final String PLAYER_FREE_TIER = SubscriptionEntitlement.PLAYER_FREE_TIER;
    // Statuses a paid invoice returns to ACTIVE (fix/payment-subscription-entitlements).
    private static final Set<String> RECOVERABLE_ON_PAYMENT = Set.of("PAST_DUE", "UNPAID", "INCOMPLETE");

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
    public PlayerSubscriptionResponse getPlayerSubscription(Long callerUserId, Long playerId) {
        assertPlayerOwnership(callerUserId, playerId);
        PaymentPlayerSubscription sub = findOrCreatePlayerSubscription(playerId);
        return toPlayerResponse(sub);
    }

    // ─── Coach Subscribe ────────────────────────────────────────────────────────

    public CoachSubscriptionResponse subscribeCoach(UUID coachId, Long coachUserId, String tier) {
        if ("SCOUT".equalsIgnoreCase(tier)) {
            throw new PaymentGatewayException("payment.subscription.cannotSubscribeToFreeTier");
        }

        PaymentCoachSubscription sub = findOrCreateCoachSubscription(coachId);
        assertCanSubscribe(sub.getStatus(), sub.getStripeSubscriptionId(), "COACH", coachId);

        String priceId = configService.getString(
            "subscription.coach." + tier.toLowerCase() + ".monthly.priceId");
        if (priceId == null || priceId.isBlank()) {
            throw new PaymentGatewayException("payment.subscription.priceNotConfigured");
        }

        com.softropic.skillars.platform.payment.repo.StripeCustomer stripeCustomer =
            stripeCustomerRepository.findById(coachUserId)
                .orElseThrow(() -> new PaymentGatewayException("payment.noPaymentMethod"));
        String paymentMethodId = stripeCustomer.getStripePaymentMethodId();
        if (paymentMethodId == null || paymentMethodId.isBlank()) {
            throw new PaymentGatewayException("payment.noPaymentMethod");
        }

        // Stripe call outside @Transactional.
        // No attachPaymentMethod call here: the payment method was already attached to this
        // exact customer when it was saved (POST /setup-intent creates a customer-scoped
        // SetupIntent; confirmCardSetup + POST /save-payment-method complete it). Calling
        // attach again on an already-attached payment method throws on Stripe's side.
        Subscription stripeSub = createPaidStripeSubscription(
            stripeCustomer.getStripeCustomerId(), priceId, paymentMethodId, "COACH", coachId);

        Optional<CoachSubscriptionResponse> persisted =
            self.persistCoachSubscription(coachId, tier.toUpperCase(), stripeSub);
        if (persisted.isEmpty()) {
            // fix/payment-subscription-entitlements: a concurrent subscribe linked another live
            // subscription first — cancel this duplicate so the coach is not billed twice.
            cancelStripeSubscriptionQuietly(stripeSub.getId(), "COACH", coachId);
            throw new PaymentGatewayException("payment.subscription.alreadyActive");
        }
        return persisted.get();
    }

    /**
     * Re-reads the row under lock (fix/payment-subscription-entitlements) rather than saving the copy
     * loaded before the Stripe call. Empty when the row is already linked to a different, still-live
     * Stripe subscription (lost a concurrent-subscribe race) — the caller cancels the new one.
     */
    @Transactional
    public Optional<CoachSubscriptionResponse> persistCoachSubscription(UUID coachId, String tier,
                                                                        Subscription stripeSub) {
        PaymentCoachSubscription sub = paymentCoachSubscriptionRepository.findByCoachIdForUpdate(coachId)
            .orElseThrow(() -> new PaymentGatewayException("payment.subscription.notFound"));
        if (isOtherLiveSubscription(sub.getStatus(), sub.getStripeSubscriptionId(), stripeSub.getId())) {
            log.warn("[COACH_SUBSCRIBE_DUPLICATE coachId={} existing={} new={}]",
                coachId, sub.getStripeSubscriptionId(), stripeSub.getId());
            return Optional.empty();
        }
        // A fresh subscription supersedes any scheduled change left over from a previous one.
        coachSubscriptionChangeRepository.voidAllPendingForCoach(coachId, Instant.now());
        sub.setTier(tier);
        sub.setStripeSubscriptionId(stripeSub.getId());
        sub.setStatus(normalizeStripeStatus(stripeSub.getStatus()));
        sub.setCancelAtPeriodEnd(false);
        sub.setPastDueSince(null);
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
        // reconcileMarketplaceTiers, and getCoachSubscriptionTier's SCOUT default makes a stale/missing
        // tier fail closed in the meantime, so deferring the sync on lock contention is safe.
        syncMarketplaceTierDeferrable(coachId, tier, "COACH_SUBSCRIBE_TIER_SYNC_DEFERRED");

        log.info("[COACH_SUBSCRIBED coachId={} tier={} status={}]", coachId, tier, sub.getStatus());
        return Optional.of(toCoachResponse(sub));
    }

    // ─── Coach Change Tier ───────────────────────────────────────────────────────

    /**
     * fix/payment-subscription-entitlements: every change requires a billing-healthy (ACTIVE/TRIALLING)
     * Stripe subscription. Upgrades charge the proration immediately (StripeClient ALWAYS_INVOICE +
     * error_if_incomplete) and are granted only once that charge succeeded. A paid downgrade moves the
     * Stripe price NOW with no proration (the current period is already paid at the higher price, the
     * next renewal bills the lower one) and the local tier follows at period end via
     * applyPendingChanges — changing the price only once the period had ended would bill one more
     * period at the old price, because Stripe renews before the daily applicator runs. A downgrade to
     * SCOUT (free) is a cancel-at-period-end. Re-selecting the current tier cancels a pending downgrade.
     */
    public void changeCoachTier(UUID coachId, String newTier) {
        PaymentCoachSubscription sub = paymentCoachSubscriptionRepository.findByCoachId(coachId)
            .orElseThrow(() -> new PaymentGatewayException("payment.subscription.notFound"));

        String currentTier = sub.getTier().toUpperCase();
        String targetTier = newTier.toUpperCase();
        int currentRank = COACH_TIERS.indexOf(currentTier);
        int newRank = COACH_TIERS.indexOf(targetTier);
        if (newRank < 0) {
            throw new PaymentGatewayException("payment.subscription.invalidTier");
        }
        String stripeSubId = requireBillingHealthy(sub.getStatus(), sub.getStripeSubscriptionId());
        boolean pendingDowngrade =
            coachSubscriptionChangeRepository.existsByCoachIdAndAppliedFalseAndVoidedAtIsNull(coachId);

        if (newRank > currentRank) {
            String newPriceId = requireConfiguredPrice(coachPriceKey(targetTier));
            if (pendingDowngrade) {
                restoreCoachPriceAndVoidDowngrade(coachId, stripeSubId, currentTier);
            }
            Subscription updated = updateStripePrice(stripeSubId, newPriceId, true, "sub-upgrade-",
                "COACH_UPGRADE", coachId);
            self.persistCoachTierUpgrade(coachId, stripeSubId, targetTier, updated);
        } else if (newRank == currentRank) {
            if (pendingDowngrade) {
                restoreCoachPriceAndVoidDowngrade(coachId, stripeSubId, currentTier);
            }
        } else if (COACH_FREE_TIER.equals(targetTier)) {
            cancelCoachSubscription(coachId);
        } else {
            String newPriceId = requireConfiguredPrice(coachPriceKey(targetTier));
            updateStripePrice(stripeSubId, newPriceId, false, "sub-downgrade-", "COACH_DOWNGRADE", coachId);
            self.persistCoachTierDowngrade(coachId, stripeSubId, targetTier);
        }
    }

    /**
     * A pending downgrade already moved the Stripe price down; put it back (no proration) before an
     * upgrade so the upgrade's proration credits what was actually paid, or when the coach re-selects
     * the current tier.
     */
    private void restoreCoachPriceAndVoidDowngrade(UUID coachId, String stripeSubId, String currentTier) {
        updateStripePrice(stripeSubId, requireConfiguredPrice(coachPriceKey(currentTier)), false,
            "sub-restore-", "COACH_DOWNGRADE_RESTORE", coachId);
        self.voidCoachPendingChanges(coachId);
    }

    @Transactional
    public void voidCoachPendingChanges(UUID coachId) {
        paymentCoachSubscriptionRepository.findByCoachIdForUpdate(coachId);
        int voided = coachSubscriptionChangeRepository.voidAllPendingForCoach(coachId, Instant.now());
        log.info("[COACH_PENDING_CHANGES_VOIDED coachId={} count={}]", coachId, voided);
    }

    @Transactional
    public void persistCoachTierUpgrade(UUID coachId, String stripeSubId, String newTier, Subscription updated) {
        PaymentCoachSubscription sub = paymentCoachSubscriptionRepository.findByCoachIdForUpdate(coachId)
            .orElseThrow(() -> new PaymentGatewayException("payment.subscription.notFound"));
        if (!Objects.equals(stripeSubId, sub.getStripeSubscriptionId())) {
            log.warn("[COACH_TIER_UPGRADE_SUPERSEDED coachId={} stripeSubId={}]", coachId, stripeSubId);
            return;
        }
        coachSubscriptionChangeRepository.voidAllPendingForCoach(coachId, Instant.now());
        sub.setTier(newTier);
        if (updated != null && updated.getCurrentPeriodEnd() != null) {
            sub.setCurrentPeriodEnd(Instant.ofEpochSecond(updated.getCurrentPeriodEnd()));
        }
        paymentCoachSubscriptionRepository.save(sub);
        // skillars-deferred-132 AC1 Fix 3: mirrors persistCoachSubscription's own Decision 3
        // catch-and-defer above — the Stripe update already happened outside this transaction, so
        // rolling back here on a contended lock would leave Stripe billing at newTier while
        // payment.coach_subscriptions rolls back to the OLD tier. reconcileMarketplaceTiers closes the
        // temporary staleness this defer creates.
        syncMarketplaceTierDeferrable(coachId, newTier, "COACH_TIER_UPGRADE_SYNC_DEFERRED");
        log.info("[COACH_TIER_UPGRADED coachId={} newTier={}]", coachId, newTier);
    }

    @Transactional
    public void persistCoachTierDowngrade(UUID coachId, String stripeSubId, String newTier) {
        PaymentCoachSubscription sub = paymentCoachSubscriptionRepository.findByCoachIdForUpdate(coachId)
            .orElseThrow(() -> new PaymentGatewayException("payment.subscription.notFound"));
        if (!Objects.equals(stripeSubId, sub.getStripeSubscriptionId())) {
            log.warn("[COACH_DOWNGRADE_SUPERSEDED coachId={} stripeSubId={}]", coachId, stripeSubId);
            return;
        }
        coachSubscriptionChangeRepository.voidAllPendingForCoach(coachId, Instant.now());

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

    // ─── Coach Cancel ────────────────────────────────────────────────────────────

    public void cancelCoachSubscription(UUID coachId) {
        PaymentCoachSubscription sub = paymentCoachSubscriptionRepository.findByCoachId(coachId)
            .orElseThrow(() -> new PaymentGatewayException("payment.subscription.notFound"));

        String stripeSubId = sub.getStripeSubscriptionId();
        if (stripeSubId == null) {
            throw new PaymentGatewayException("payment.subscription.noActiveStripeSubscription");
        }

        try {
            Instant periodEnd = stripeClient.cancelSubscriptionAtPeriodEnd(stripeSubId);
            self.persistCoachCancellation(coachId, stripeSubId, periodEnd);
        } catch (StripeException e) {
            log.error("[COACH_CANCEL_STRIPE_FAILED coachId={}]", coachId, e);
            throw new PaymentGatewayException("payment.subscription.stripeError");
        }
    }

    @Transactional
    public void persistCoachCancellation(UUID coachId, String stripeSubId, Instant periodEnd) {
        PaymentCoachSubscription sub = paymentCoachSubscriptionRepository.findByCoachIdForUpdate(coachId)
            .orElseThrow(() -> new PaymentGatewayException("payment.subscription.notFound"));
        if (!Objects.equals(stripeSubId, sub.getStripeSubscriptionId())) {
            log.warn("[COACH_CANCEL_SUPERSEDED coachId={} stripeSubId={}]", coachId, stripeSubId);
            return;
        }
        sub.setCancelAtPeriodEnd(true);
        if (periodEnd != null) sub.setCurrentPeriodEnd(periodEnd);
        paymentCoachSubscriptionRepository.save(sub);
        // fix/payment-subscription-entitlements: a scheduled downgrade must not outlive the
        // cancellation (applied after the deletion it re-granted a paid tier with no subscription).
        coachSubscriptionChangeRepository.voidAllPendingForCoach(coachId, Instant.now());
        log.info("[COACH_CANCEL_AT_PERIOD_END coachId={} periodEnd={}]", coachId, periodEnd);
    }

    // ─── Player Subscribe ────────────────────────────────────────────────────────

    public PlayerSubscriptionResponse subscribePlayer(Long callerUserId, Long playerId,
                                                       String tier, String billingInterval) {
        assertPlayerOwnership(callerUserId, playerId);
        validatePlayerTierInterval(tier, billingInterval);

        PaymentPlayerSubscription sub = findOrCreatePlayerSubscription(playerId);
        assertCanSubscribe(sub.getStatus(), sub.getStripeSubscriptionId(), "PLAYER", playerId);

        String priceId = configService.getString(playerPriceKey(tier, billingInterval));
        if (priceId == null || priceId.isBlank()) {
            throw new PaymentGatewayException("payment.subscription.priceNotConfigured");
        }

        com.softropic.skillars.platform.payment.repo.StripeCustomer stripeCustomer =
            stripeCustomerRepository.findById(callerUserId)
                .orElseThrow(() -> new PaymentGatewayException("payment.noPaymentMethod"));
        String paymentMethodId = stripeCustomer.getStripePaymentMethodId();
        if (paymentMethodId == null || paymentMethodId.isBlank()) {
            throw new PaymentGatewayException("payment.noPaymentMethod");
        }

        // No attachPaymentMethod call here: see subscribeCoach.
        Subscription stripeSub = createPaidStripeSubscription(
            stripeCustomer.getStripeCustomerId(), priceId, paymentMethodId, "PLAYER", playerId);

        Optional<PlayerSubscriptionResponse> persisted = self.persistPlayerSubscription(
            playerId, tier.toUpperCase(), billingInterval.toUpperCase(), stripeSub);
        if (persisted.isEmpty()) {
            cancelStripeSubscriptionQuietly(stripeSub.getId(), "PLAYER", playerId);
            throw new PaymentGatewayException("payment.subscription.alreadyActive");
        }
        return persisted.get();
    }

    /** See {@link #persistCoachSubscription}. */
    @Transactional
    public Optional<PlayerSubscriptionResponse> persistPlayerSubscription(Long playerId, String tier,
                                                                          String billingInterval,
                                                                          Subscription stripeSub) {
        PaymentPlayerSubscription sub = paymentPlayerSubscriptionRepository.findByPlayerIdForUpdate(playerId)
            .orElseThrow(() -> new PaymentGatewayException("payment.subscription.notFound"));
        if (isOtherLiveSubscription(sub.getStatus(), sub.getStripeSubscriptionId(), stripeSub.getId())) {
            log.warn("[PLAYER_SUBSCRIBE_DUPLICATE playerId={} existing={} new={}]",
                playerId, sub.getStripeSubscriptionId(), stripeSub.getId());
            return Optional.empty();
        }
        playerSubscriptionChangeRepository.voidAllPendingForPlayer(playerId, Instant.now());
        sub.setTier(tier);
        sub.setBillingInterval(billingInterval);
        sub.setStripeSubscriptionId(stripeSub.getId());
        sub.setStatus(normalizeStripeStatus(stripeSub.getStatus()));
        sub.setCancelAtPeriodEnd(false);
        sub.setPastDueSince(null);
        if (stripeSub.getCurrentPeriodEnd() != null) {
            sub.setCurrentPeriodEnd(Instant.ofEpochSecond(stripeSub.getCurrentPeriodEnd()));
        }
        paymentPlayerSubscriptionRepository.save(sub);
        log.info("[PLAYER_SUBSCRIBED playerId={} tier={} interval={} status={}]",
            playerId, tier, billingInterval, sub.getStatus());
        return Optional.of(toPlayerResponse(sub));
    }

    // ─── Player Change Tier ──────────────────────────────────────────────────────

    /** Same rules as {@link #changeCoachTier}; ATHLETE is a priced tier, so every downgrade is a price change. */
    public void changePlayerTier(Long callerUserId, Long playerId, String newTier) {
        assertPlayerOwnership(callerUserId, playerId);

        PaymentPlayerSubscription sub = paymentPlayerSubscriptionRepository.findByPlayerId(playerId)
            .orElseThrow(() -> new PaymentGatewayException("payment.subscription.notFound"));

        // Validate new tier's billing interval constraints
        validatePlayerTierInterval(newTier, sub.getBillingInterval());

        String currentTier = sub.getTier().toUpperCase();
        String targetTier = newTier.toUpperCase();
        int currentRank = PLAYER_TIERS.indexOf(currentTier);
        int newRank = PLAYER_TIERS.indexOf(targetTier);
        if (newRank < 0) {
            throw new PaymentGatewayException("payment.subscription.invalidTier");
        }
        String stripeSubId = requireBillingHealthy(sub.getStatus(), sub.getStripeSubscriptionId());
        String interval = sub.getBillingInterval();
        boolean pendingDowngrade =
            playerSubscriptionChangeRepository.existsByPlayerIdAndAppliedFalseAndVoidedAtIsNull(playerId);

        if (newRank > currentRank) {
            String newPriceId = requireConfiguredPrice(playerPriceKey(targetTier, interval));
            if (pendingDowngrade) {
                restorePlayerPriceAndVoidDowngrade(playerId, stripeSubId, currentTier, interval);
            }
            Subscription updated = updateStripePrice(stripeSubId, newPriceId, true, "sub-upgrade-",
                "PLAYER_UPGRADE", playerId);
            self.persistPlayerTierUpgrade(playerId, stripeSubId, targetTier, updated);
        } else if (newRank == currentRank) {
            if (pendingDowngrade) {
                restorePlayerPriceAndVoidDowngrade(playerId, stripeSubId, currentTier, interval);
            }
        } else {
            String newPriceId = requireConfiguredPrice(playerPriceKey(targetTier, interval));
            updateStripePrice(stripeSubId, newPriceId, false, "sub-downgrade-", "PLAYER_DOWNGRADE", playerId);
            self.persistPlayerTierDowngrade(playerId, stripeSubId, targetTier);
        }
    }

    private void restorePlayerPriceAndVoidDowngrade(Long playerId, String stripeSubId, String currentTier,
                                                    String interval) {
        updateStripePrice(stripeSubId, requireConfiguredPrice(playerPriceKey(currentTier, interval)), false,
            "sub-restore-", "PLAYER_DOWNGRADE_RESTORE", playerId);
        self.voidPlayerPendingChanges(playerId);
    }

    @Transactional
    public void voidPlayerPendingChanges(Long playerId) {
        paymentPlayerSubscriptionRepository.findByPlayerIdForUpdate(playerId);
        int voided = playerSubscriptionChangeRepository.voidAllPendingForPlayer(playerId, Instant.now());
        log.info("[PLAYER_PENDING_CHANGES_VOIDED playerId={} count={}]", playerId, voided);
    }

    @Transactional
    public void persistPlayerTierUpgrade(Long playerId, String stripeSubId, String newTier, Subscription updated) {
        PaymentPlayerSubscription sub = paymentPlayerSubscriptionRepository.findByPlayerIdForUpdate(playerId)
            .orElseThrow(() -> new PaymentGatewayException("payment.subscription.notFound"));
        if (!Objects.equals(stripeSubId, sub.getStripeSubscriptionId())) {
            log.warn("[PLAYER_TIER_UPGRADE_SUPERSEDED playerId={} stripeSubId={}]", playerId, stripeSubId);
            return;
        }
        playerSubscriptionChangeRepository.voidAllPendingForPlayer(playerId, Instant.now());
        sub.setTier(newTier);
        if (updated != null && updated.getCurrentPeriodEnd() != null) {
            sub.setCurrentPeriodEnd(Instant.ofEpochSecond(updated.getCurrentPeriodEnd()));
        }
        paymentPlayerSubscriptionRepository.save(sub);
        log.info("[PLAYER_TIER_UPGRADED playerId={} newTier={}]", playerId, newTier);
    }

    @Transactional
    public void persistPlayerTierDowngrade(Long playerId, String stripeSubId, String newTier) {
        PaymentPlayerSubscription sub = paymentPlayerSubscriptionRepository.findByPlayerIdForUpdate(playerId)
            .orElseThrow(() -> new PaymentGatewayException("payment.subscription.notFound"));
        if (!Objects.equals(stripeSubId, sub.getStripeSubscriptionId())) {
            log.warn("[PLAYER_DOWNGRADE_SUPERSEDED playerId={} stripeSubId={}]", playerId, stripeSubId);
            return;
        }
        playerSubscriptionChangeRepository.voidAllPendingForPlayer(playerId, Instant.now());

        PlayerSubscriptionChange change = new PlayerSubscriptionChange();
        change.setPlayerId(playerId);
        change.setFromTier(sub.getTier());
        change.setToTier(newTier);
        change.setEffectiveAt(sub.getCurrentPeriodEnd() != null ? sub.getCurrentPeriodEnd() : Instant.now());
        change.setTriggerSource("SCHEDULED");
        playerSubscriptionChangeRepository.save(change);
        log.info("[PLAYER_DOWNGRADE_SCHEDULED playerId={} newTier={} effectiveAt={}]",
            playerId, newTier, change.getEffectiveAt());
    }

    // ─── Player Cancel ────────────────────────────────────────────────────────────

    public void cancelPlayerSubscription(Long callerUserId, Long playerId) {
        assertPlayerOwnership(callerUserId, playerId);

        PaymentPlayerSubscription sub = paymentPlayerSubscriptionRepository.findByPlayerId(playerId)
            .orElseThrow(() -> new PaymentGatewayException("payment.subscription.notFound"));

        String stripeSubId = sub.getStripeSubscriptionId();
        if (stripeSubId == null) {
            throw new PaymentGatewayException("payment.subscription.noActiveStripeSubscription");
        }

        try {
            Instant periodEnd = stripeClient.cancelSubscriptionAtPeriodEnd(stripeSubId);
            self.persistPlayerCancellation(playerId, stripeSubId, periodEnd);
        } catch (StripeException e) {
            log.error("[PLAYER_CANCEL_STRIPE_FAILED playerId={}]", playerId, e);
            throw new PaymentGatewayException("payment.subscription.stripeError");
        }
    }

    @Transactional
    public void persistPlayerCancellation(Long playerId, String stripeSubId, Instant periodEnd) {
        PaymentPlayerSubscription sub = paymentPlayerSubscriptionRepository.findByPlayerIdForUpdate(playerId)
            .orElseThrow(() -> new PaymentGatewayException("payment.subscription.notFound"));
        if (!Objects.equals(stripeSubId, sub.getStripeSubscriptionId())) {
            log.warn("[PLAYER_CANCEL_SUPERSEDED playerId={} stripeSubId={}]", playerId, stripeSubId);
            return;
        }
        sub.setCancelAtPeriodEnd(true);
        if (periodEnd != null) sub.setCurrentPeriodEnd(periodEnd);
        paymentPlayerSubscriptionRepository.save(sub);
        playerSubscriptionChangeRepository.voidAllPendingForPlayer(playerId, Instant.now());
        log.info("[PLAYER_CANCEL_AT_PERIOD_END playerId={} periodEnd={}]", playerId, periodEnd);
    }

    // ─── Scheduled: Apply Pending Changes ────────────────────────────────────────

    // skillars-deferred-116 AC1/AC2: restructured from a single method-level @Transactional wrapping
    // the whole batch (one bad row rolled back every sibling coach/player change for the entire run)
    // to short-transaction batch load + per-item transactions wrapped in try/catch, mirroring
    // SessionPackForfeitureScheduler's established pattern in this same module.
    // The batch-load transaction's commit is also what releases findPendingForScheduler's
    // `FOR UPDATE SKIP LOCKED` row locks before per-item writes begin — see AC3's @SchedulerLock,
    // which closes the resulting concurrent-re-selection gap.
    //
    // fix/payment-subscription-entitlements: each change is now (1) re-checked under the subscription
    // row lock — ACTIVE/TRIALLING applies, PAST_DUE is deferred (Stripe may still recover it), anything
    // else (CANCELLED/UNPAID/INCOMPLETE/PAUSED, or no row) voids the change instead of re-granting a
    // tier; (2) the Stripe price is set to the target tier's price (no proration, idempotency key per
    // change, outside any transaction) — a no-op for downgrades scheduled since this fix, which move the
    // price at schedule time, and the correction for older ones that never changed it; a Stripe failure
    // leaves the change pending for tomorrow's run; (3) applied under the lock after re-checking both
    // rows are unchanged.
    public void applyPendingChanges() {
        Instant now = Instant.now();

        List<CoachSubscriptionChange> coachChanges = transactionTemplate.execute(
            status -> coachSubscriptionChangeRepository.findPendingForScheduler(now));
        if (coachChanges == null) coachChanges = List.of();
        log.info("[SUBSCRIPTION_CHANGE_APPLICATOR] {} pending coach downgrade(s) loaded", coachChanges.size());
        for (CoachSubscriptionChange change : coachChanges) {
            try {
                applyCoachChange(change.getChangeId());
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
                applyPlayerChange(change.getChangeId());
            } catch (Exception e) {
                log.error("Failed to apply scheduled player subscription downgrade {}",
                    change.getChangeId(), e);
            }
        }
    }

    /** Phase-1 result: the change may be applied once the Stripe price matches. */
    private record ChangePlan(String stripeSubscriptionId, String priceId) {}

    private void applyCoachChange(UUID changeId) {
        ChangePlan plan = transactionTemplate.execute(status -> {
            CoachSubscriptionChange change = pendingCoachChange(changeId);
            if (change == null) return null;
            Optional<PaymentCoachSubscription> sub =
                paymentCoachSubscriptionRepository.findByCoachIdForUpdate(change.getCoachId());
            if (!changeStillApplicable(sub.map(PaymentCoachSubscription::getStatus).orElse(null),
                    "COACH", change.getCoachId(), changeId)) {
                if (sub.isEmpty() || !"PAST_DUE".equals(sub.get().getStatus())) {
                    voidChange(change);
                    coachSubscriptionChangeRepository.save(change);
                }
                return null;
            }
            return new ChangePlan(sub.get().getStripeSubscriptionId(),
                configuredPriceOrNull(coachPriceKey(change.getToTier())));
        });
        if (plan == null || !syncStripePriceForChange(plan, changeId, "COACH")) return;

        transactionTemplate.executeWithoutResult(status -> {
            CoachSubscriptionChange change = pendingCoachChange(changeId);
            if (change == null) return;
            paymentCoachSubscriptionRepository.findByCoachIdForUpdate(change.getCoachId())
                .filter(sub -> isBillingHealthy(sub.getStatus())
                    && Objects.equals(sub.getStripeSubscriptionId(), plan.stripeSubscriptionId()))
                .ifPresent(sub -> {
                    sub.setTier(change.getToTier());
                    paymentCoachSubscriptionRepository.save(sub);
                    syncMarketplaceTier(change.getCoachId(), change.getToTier());
                    change.setApplied(true);
                    coachSubscriptionChangeRepository.save(change);
                    log.info("[COACH_SCHEDULED_DOWNGRADE_APPLIED coachId={} toTier={}]",
                        change.getCoachId(), change.getToTier());
                });
        });
    }

    private void applyPlayerChange(UUID changeId) {
        ChangePlan plan = transactionTemplate.execute(status -> {
            PlayerSubscriptionChange change = pendingPlayerChange(changeId);
            if (change == null) return null;
            Optional<PaymentPlayerSubscription> sub =
                paymentPlayerSubscriptionRepository.findByPlayerIdForUpdate(change.getPlayerId());
            if (!changeStillApplicable(sub.map(PaymentPlayerSubscription::getStatus).orElse(null),
                    "PLAYER", change.getPlayerId(), changeId)) {
                if (sub.isEmpty() || !"PAST_DUE".equals(sub.get().getStatus())) {
                    voidChange(change);
                    playerSubscriptionChangeRepository.save(change);
                }
                return null;
            }
            return new ChangePlan(sub.get().getStripeSubscriptionId(),
                configuredPriceOrNull(playerPriceKey(change.getToTier(), sub.get().getBillingInterval())));
        });
        if (plan == null || !syncStripePriceForChange(plan, changeId, "PLAYER")) return;

        transactionTemplate.executeWithoutResult(status -> {
            PlayerSubscriptionChange change = pendingPlayerChange(changeId);
            if (change == null) return;
            // No syncMarketplaceTier equivalent here, deliberately: players have no
            // marketplace-visible tier (marketplace.coach_subscriptions only models coaches).
            // Confirmed intentional at skillars-deferred-116 story creation, not an oversight.
            paymentPlayerSubscriptionRepository.findByPlayerIdForUpdate(change.getPlayerId())
                .filter(sub -> isBillingHealthy(sub.getStatus())
                    && Objects.equals(sub.getStripeSubscriptionId(), plan.stripeSubscriptionId()))
                .ifPresent(sub -> {
                    sub.setTier(change.getToTier());
                    paymentPlayerSubscriptionRepository.save(sub);
                    change.setApplied(true);
                    playerSubscriptionChangeRepository.save(change);
                    log.info("[PLAYER_SCHEDULED_DOWNGRADE_APPLIED playerId={} toTier={}]",
                        change.getPlayerId(), change.getToTier());
                    // Only publish SubscriptionExpiredEvent for deletions (scheduled downgrades are
                    // tier changes, not subscription deletions; video lifecycle only triggers on deletion)
                });
        });
    }

    private CoachSubscriptionChange pendingCoachChange(UUID changeId) {
        return coachSubscriptionChangeRepository.findById(changeId)
            .filter(c -> !c.isApplied() && c.getVoidedAt() == null)
            .orElse(null);
    }

    private PlayerSubscriptionChange pendingPlayerChange(UUID changeId) {
        return playerSubscriptionChangeRepository.findById(changeId)
            .filter(c -> !c.isApplied() && c.getVoidedAt() == null)
            .orElse(null);
    }

    private boolean changeStillApplicable(String subStatus, String kind, Object ownerId, UUID changeId) {
        if (isBillingHealthy(subStatus)) return true;
        if ("PAST_DUE".equals(subStatus)) {
            log.info("[{}_SCHEDULED_DOWNGRADE_DEFERRED ownerId={} changeId={} status=PAST_DUE]", kind, ownerId, changeId);
        } else {
            log.warn("[{}_SCHEDULED_DOWNGRADE_VOIDED ownerId={} changeId={} status={}]", kind, ownerId, changeId, subStatus);
        }
        return false;
    }

    private static void voidChange(CoachSubscriptionChange change) {
        change.setApplied(true);
        change.setVoidedAt(Instant.now());
    }

    private static void voidChange(PlayerSubscriptionChange change) {
        change.setApplied(true);
        change.setVoidedAt(Instant.now());
    }

    /** True when the change may be applied locally; false leaves it pending for the next run. */
    private boolean syncStripePriceForChange(ChangePlan plan, UUID changeId, String kind) {
        if (plan.stripeSubscriptionId() == null) return true;
        if (plan.priceId() == null) {
            log.warn("[{}_SCHEDULED_DOWNGRADE_PRICE_NOT_CONFIGURED changeId={}] applying locally only", kind, changeId);
            return true;
        }
        try {
            stripeClient.updateSubscriptionTier(plan.stripeSubscriptionId(), plan.priceId(), false,
                "sub-downgrade-apply-" + changeId);
            return true;
        } catch (StripeException e) {
            log.error("[{}_SCHEDULED_DOWNGRADE_STRIPE_FAILED changeId={}] left pending", kind, changeId, e);
            return false;
        }
    }

    // ─── Scheduled: Grace Period Check ───────────────────────────────────────────

    // skillars-deferred-116 AC2: identical restructure to applyPendingChanges() above, for the same
    // reason. A row that fails to downgrade stays PAST_DUE and past the cutoff, so it stays eligible
    // and is naturally re-selected on tomorrow's run with no extra bookkeeping needed.
    //
    // fix/payment-subscription-entitlements: grace expiry now CANCELS the Stripe subscription
    // (immediately, idempotently, outside any transaction) before revoking locally and clearing
    // stripeSubscriptionId. Before, the revoke was local-only: Stripe kept retrying the invoice, a later
    // successful retry left a paying user on the free tier, and a re-subscribe created a second billing
    // subscription. A Stripe failure leaves the row PAST_DUE so tomorrow's run retries the cancel.
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
        for (PaymentCoachSubscription loaded : pastDueCoaches) {
            UUID coachId = loaded.getCoachId();
            try {
                GraceTarget target = transactionTemplate.execute(status ->
                    paymentCoachSubscriptionRepository.findByCoachIdForUpdate(coachId)
                        .filter(sub -> isGraceExpired(sub.getStatus(), sub.getPastDueSince(), graceCutoff))
                        .map(sub -> new GraceTarget(sub.getStripeSubscriptionId()))
                        .orElse(null));
                if (target == null || !cancelAtStripeForGrace(target, "COACH", coachId)) continue;
                transactionTemplate.executeWithoutResult(status ->
                    paymentCoachSubscriptionRepository.findByCoachIdForUpdate(coachId)
                        .filter(sub -> target.matches(sub.getStripeSubscriptionId(), sub.getStatus()))
                        .ifPresent(sub -> {
                            revokeCoachSubscription(sub);
                            log.warn("[COACH_GRACE_PERIOD_EXPIRED coachId={} downgradedTo=SCOUT]", coachId);
                        }));
            } catch (Exception e) {
                log.error("Failed to apply grace-period downgrade for coach {}", coachId, e);
            }
        }

        List<PaymentPlayerSubscription> pastDuePlayers = transactionTemplate.execute(
            status -> paymentPlayerSubscriptionRepository.findByStatusAndPastDueSinceBefore("PAST_DUE", graceCutoff));
        if (pastDuePlayers == null) pastDuePlayers = List.of();
        log.info("[SUBSCRIPTION_GRACE_PERIOD_CHECKER] {} past-due player subscription(s) loaded", pastDuePlayers.size());
        for (PaymentPlayerSubscription loaded : pastDuePlayers) {
            Long playerId = loaded.getPlayerId();
            try {
                GraceTarget target = transactionTemplate.execute(status ->
                    paymentPlayerSubscriptionRepository.findByPlayerIdForUpdate(playerId)
                        .filter(sub -> isGraceExpired(sub.getStatus(), sub.getPastDueSince(), graceCutoff))
                        .map(sub -> new GraceTarget(sub.getStripeSubscriptionId()))
                        .orElse(null));
                if (target == null || !cancelAtStripeForGrace(target, "PLAYER", playerId)) continue;
                // No syncMarketplaceTier equivalent here, deliberately — see the identical note
                // in applyPendingChanges()'s player loop: players have no marketplace-visible tier.
                transactionTemplate.executeWithoutResult(status ->
                    paymentPlayerSubscriptionRepository.findByPlayerIdForUpdate(playerId)
                        .filter(sub -> target.matches(sub.getStripeSubscriptionId(), sub.getStatus()))
                        .ifPresent(sub -> {
                            revokePlayerSubscription(sub);
                            log.warn("[PLAYER_GRACE_PERIOD_EXPIRED playerId={} downgradedTo=ATHLETE]", playerId);
                        }));
            } catch (Exception e) {
                log.error("Failed to apply grace-period downgrade for player {}", playerId, e);
            }
        }
    }

    /** The Stripe subscription a grace-expired row was linked to when it was selected. */
    private record GraceTarget(String stripeSubscriptionId) {
        /**
         * Revoke only if the row still points at the same subscription (a re-subscribe or deletion in
         * between wins). Once its Stripe subscription was cancelled the row is revoked even if an
         * invoice.paid raced in; a row with no Stripe subscription must still be PAST_DUE.
         */
        boolean matches(String currentStripeSubscriptionId, String currentStatus) {
            if (!Objects.equals(stripeSubscriptionId, currentStripeSubscriptionId)) return false;
            return stripeSubscriptionId != null || "PAST_DUE".equals(currentStatus);
        }
    }

    private static boolean isGraceExpired(String status, Instant pastDueSince, Instant graceCutoff) {
        return "PAST_DUE".equals(status) && pastDueSince != null && pastDueSince.isBefore(graceCutoff);
    }

    private boolean cancelAtStripeForGrace(GraceTarget target, String kind, Object ownerId) {
        if (target.stripeSubscriptionId() == null) return true;
        try {
            stripeClient.cancelSubscriptionImmediately(target.stripeSubscriptionId());
            return true;
        } catch (StripeException e) {
            log.error("[{}_GRACE_CANCEL_STRIPE_FAILED ownerId={} stripeSubId={}] left PAST_DUE for retry",
                kind, ownerId, target.stripeSubscriptionId(), e);
            return false;
        }
    }

    // ─── Scheduled: Marketplace Tier Reconciliation ──────────────────────────────

    /**
     * skillars-deferred-132 AC1 Fix 3. Neither {@link #applyPendingChanges()} nor
     * {@link #checkPastDueGracePeriod()} re-syncs a coach outside its own narrow trigger, and every
     * persist path defers the sync on lock contention, so the {@code marketplace.coach_subscriptions}
     * projection can go stale. This sweep corrects every mismatch via the same already-locked
     * {@link #syncMarketplaceTier} write path.
     *
     * <p>fix/payment-subscription-entitlements: widened from ACTIVE/TRIALLING rows to every payment-side
     * coach row, comparing the projection against the ENTITLED tier
     * ({@link SubscriptionEntitlement#effectiveCoachTier}) — a coach whose subscription is CANCELLED,
     * UNPAID, INCOMPLETE or PAUSED is forced back to SCOUT instead of being skipped. A coach with no
     * projection row whose entitled tier is SCOUT is left alone (getCoachSubscriptionTier already
     * defaults to SCOUT).
     *
     * <p>This is a payment → marketplace sweep only — it treats {@code payment.coach_subscriptions} as
     * the source of truth. See {@link #reconcileStripeSubscriptions()} for the Stripe → payment side.
     */
    public void reconcileMarketplaceTiers() {
        List<PaymentCoachSubscription> all = transactionTemplate.execute(
            status -> paymentCoachSubscriptionRepository.findAll());
        if (all == null) all = List.of();
        log.info("[SUBSCRIPTION_TIER_RECONCILIATION] {} coach subscription(s) loaded", all.size());
        for (PaymentCoachSubscription sub : all) {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    String entitledTier = SubscriptionEntitlement.effectiveCoachTier(sub.getTier(), sub.getStatus());
                    CoachSubscriptionTier expected;
                    try {
                        expected = CoachSubscriptionTier.valueOf(entitledTier);
                    } catch (IllegalArgumentException e) {
                        // skillars-deferred-132 AC1 Fix 3: a payment row with a tier outside
                        // CoachSubscriptionTier's own values (COACH_TIERS above uses raw strings, not
                        // this enum) is skipped, not thrown — a per-row data issue must not abort the
                        // rest of the sweep.
                        log.error("[COACH_TIER_RECONCILE_SKIPPED coachId={} unrecognizedTier={}]",
                            sub.getCoachId(), sub.getTier(), e);
                        return;
                    }
                    Optional<CoachSubscription> marketplace = coachSubscriptionRepository.findByCoachId(sub.getCoachId());
                    boolean mismatch = marketplace.isEmpty()
                        ? expected != CoachSubscriptionTier.SCOUT
                        : marketplace.get().getTier() != expected;
                    if (mismatch) {
                        syncMarketplaceTier(sub.getCoachId(), entitledTier);
                        log.warn("[COACH_TIER_RECONCILED coachId={} tier={} status={}]",
                            sub.getCoachId(), entitledTier, sub.getStatus());
                    }
                });
            } catch (Exception e) {
                log.error("Failed to reconcile marketplace tier for coach {}", sub.getCoachId(), e);
            }
        }
    }

    // ─── Scheduled: Stripe → Payment Reconciliation ──────────────────────────────

    // skillars-deferred-135 AC2: mirrors StripeWebhookService.LIVE_SUBSCRIPTION_STATUSES exactly,
    // derived from that SAME field rather than a second hand-typed literal set — Stripe's list API
    // (confirmed via javap against the pinned stripe-java 28.4.0 jar) accepts exactly one status per
    // call, unlike the 3-status Set<String> that field is, so this sweep issues one paginated
    // StripeClient.listSubscriptionsByStatus(...) call per entry here.
    private static final List<SubscriptionListParams.Status> STRIPE_LIVE_STATUSES =
        StripeWebhookService.LIVE_SUBSCRIPTION_STATUSES.stream()
            .map(s -> SubscriptionListParams.Status.valueOf(s.toUpperCase(java.util.Locale.ROOT)))
            .toList();

    /**
     * skillars-deferred-135 AC2. {@link #reconcileMarketplaceTiers()}'s own Javadoc (above) names the
     * gap this closes but does not close it: that sweep treats {@code payment.coach_subscriptions} as
     * the source of truth and only corrects the DOWNSTREAM {@code marketplace.coach_subscriptions}
     * projection — it never asks whether the payment-side row itself is missing.
     * {@link StripeWebhookService#maybeAlertOrphanedLiveSubscription}/{@code
     * maybeAlertOrphanedInvoicePaymentFailed} only fire when a relevant Stripe webhook event actually
     * arrives for the drifted subscription — a coach whose local row was deleted, or never created, for
     * some other reason, with no subsequent webhook ever touching that specific subscription again,
     * generates no signal at all, indefinitely. This sweep asks Stripe directly instead of waiting for
     * Stripe to tell us: it lists every live Stripe subscription and reuses the EXACT resolution chain
     * both webhook handlers already share ({@link StripeWebhookService#resolveCoachAndAlertIfOrphaned}),
     * rather than duplicating that chain a third time.
     *
     * <p><strong>Explicitly NOT auto-heal</strong>, matching {@code skillars-deferred-133} AC3's own
     * precedent (see {@link #syncMarketplaceTier}'s own comment on the deliberate no-{@code priceId}
     * -to-tier reverse-map constraint) — this sweep only alerts; a human reconciles, exactly like the
     * webhook path.
     *
     * <p><strong>Rate limits: deliberately no bespoke retry.</strong> This is the first Stripe-{@code
     * list}-heavy call site in this codebase ({@link StripeTransferErrorClassifier} already classifies
     * {@code RateLimitException} as retryable, but for {@code Transfer.create}/{@code createReversal} —
     * a narrower, different call shape, triggered per-payout, not by a daily sweep). At most 3
     * sequential paginated list calls, once a day, against a marketplace with no production deployment
     * yet ({@code skillars-deferred-117}) — a {@code RateLimitException} (or any other {@link
     * StripeException}) simply propagates out of this method (or, if raised mid-pagination, as the
     * SDK's own unchecked wrapper — see {@link StripeClient#listSubscriptionsByStatus}'s own Javadoc),
     * is caught and logged per-status below, and the remaining statuses/next day's cron tick still run.
     * Accepted for now, per this story's own explicit decision point; revisit if actual coach volume
     * ever approaches Stripe's real rate-limit thresholds.
     *
     * <p><strong>No new {@code ConfigBounds} key.</strong> Mirrors the three sibling schedulers' own
     * fixed-cron precedent (none of them expose a config-driven cadence either) — the per-status page
     * size ({@link StripeClient#listSubscriptionsByStatus}'s own fixed {@code 100}, Stripe's own
     * per-request maximum) is a fixed SDK-level constant, not a business-tunable value, so a registry
     * key for it would be unwarranted scope (this story's own Dev Notes decision point).
     */
    public void reconcileStripeSubscriptions() {
        int checked = 0;
        for (SubscriptionListParams.Status status : STRIPE_LIVE_STATUSES) {
            try {
                for (Subscription sub : stripeClient.listSubscriptionsByStatus(status)) {
                    checked++;
                    reconcileOneStripeSubscription(sub);
                }
            } catch (StripeException e) {
                log.error("[STRIPE_RECONCILIATION_LIST_FAILED status={}]", status, e);
            } catch (RuntimeException e) {
                // StripeClient.listSubscriptionsByStatus's own Javadoc: a StripeException raised
                // mid-pagination surfaces wrapped in an unchecked RuntimeException from the SDK itself,
                // not via this method's own checked StripeException catch above.
                log.error("[STRIPE_RECONCILIATION_PAGE_FAILED status={}]", status, e);
            }
        }
        log.info("[STRIPE_RECONCILIATION] checked {} live Stripe subscription(s) across {} status(es)",
            checked, STRIPE_LIVE_STATUSES.size());
    }

    /**
     * A per-item failure (an unexpected exception from either repository, or from the alert-publish
     * chain) must not abort the rest of the sweep — mirrors {@link #reconcileMarketplaceTiers()}'s own
     * per-row try/catch shape exactly.
     */
    private void reconcileOneStripeSubscription(Subscription sub) {
        try {
            if (paymentCoachSubscriptionRepository.findByStripeSubscriptionId(sub.getId()).isPresent()) {
                return;
            }
            stripeWebhookService.resolveCoachAndAlertIfOrphaned(sub.getCustomer(), sub.getId());
        } catch (Exception e) {
            log.error("[STRIPE_RECONCILIATION_ROW_FAILED stripeSubId={}]", sub.getId(), e);
        }
    }

    // ─── Webhook Handler ──────────────────────────────────────────────────────────

    /**
     * fix/payment-subscription-entitlements: rows are read under lock; {@code data.event_created}
     * (epoch seconds, the Stripe event's {@code created}) is compared against the row's
     * lastStripeEventAt so a late, older customer.subscription.updated / invoice.* event cannot
     * overwrite newer state (dedupe is by event id only). Deletion is terminal and always applied.
     * A customer.subscription.updated carrying {@code canceled}/{@code incomplete_expired} is treated
     * as a deletion. An optimistic-lock failure propagates, rolling back the idempotency row so Stripe
     * redelivers.
     */
    @Transactional
    public void handleSubscriptionWebhook(String eventType, String stripeSubscriptionId,
                                           Map<String, Object> data) {
        Instant eventCreated = extractEventCreated(data);
        if ("customer.subscription.updated".equals(eventType)) {
            String status = extractStatus(data);
            if ("canceled".equals(status) || "incomplete_expired".equals(status)) {
                handleSubscriptionDeleted(stripeSubscriptionId, eventCreated);
            } else {
                handleSubscriptionUpdated(stripeSubscriptionId, data, eventCreated);
            }
        } else if ("customer.subscription.deleted".equals(eventType)) {
            handleSubscriptionDeleted(stripeSubscriptionId, eventCreated);
        } else if ("invoice.payment_failed".equals(eventType)) {
            handleInvoicePaymentFailed(stripeSubscriptionId, eventCreated);
        } else if ("invoice.paid".equals(eventType)) {
            handleInvoicePaid(stripeSubscriptionId, eventCreated);
        }
    }

    private void handleSubscriptionUpdated(String stripeSubscriptionId, Map<String, Object> data,
                                           Instant eventCreated) {
        // Only sync status/period fields — do NOT sync tier from Stripe (no priceId→tier map)
        Instant periodEnd = extractCurrentPeriodEnd(data);
        Boolean cancelAtPeriodEnd = extractCancelAtPeriodEnd(data);
        String newStatus = extractStatus(data);

        paymentCoachSubscriptionRepository.findByStripeSubscriptionIdForUpdate(stripeSubscriptionId)
            .ifPresent(sub -> {
                if (isStaleEvent(sub.getLastStripeEventAt(), eventCreated, "COACH", stripeSubscriptionId)) return;
                String entitledBefore = SubscriptionEntitlement.effectiveCoachTier(sub.getTier(), sub.getStatus());
                if (newStatus != null) applyStripeStatus(sub::getPastDueSince, sub::setPastDueSince,
                    sub::setStatus, normalizeStripeStatus(newStatus));
                if (periodEnd != null) sub.setCurrentPeriodEnd(periodEnd);
                if (cancelAtPeriodEnd != null) sub.setCancelAtPeriodEnd(cancelAtPeriodEnd);
                sub.setLastStripeEventAt(newer(sub.getLastStripeEventAt(), eventCreated));
                paymentCoachSubscriptionRepository.save(sub);
                syncCoachEntitlementChange(sub, entitledBefore);
            });

        paymentPlayerSubscriptionRepository.findByStripeSubscriptionIdForUpdate(stripeSubscriptionId)
            .ifPresent(sub -> {
                if (isStaleEvent(sub.getLastStripeEventAt(), eventCreated, "PLAYER", stripeSubscriptionId)) return;
                if (newStatus != null) applyStripeStatus(sub::getPastDueSince, sub::setPastDueSince,
                    sub::setStatus, normalizeStripeStatus(newStatus));
                if (periodEnd != null) sub.setCurrentPeriodEnd(periodEnd);
                if (cancelAtPeriodEnd != null) sub.setCancelAtPeriodEnd(cancelAtPeriodEnd);
                sub.setLastStripeEventAt(newer(sub.getLastStripeEventAt(), eventCreated));
                paymentPlayerSubscriptionRepository.save(sub);
            });
    }

    private void handleSubscriptionDeleted(String stripeSubscriptionId, Instant eventCreated) {
        paymentCoachSubscriptionRepository.findByStripeSubscriptionIdForUpdate(stripeSubscriptionId)
            .ifPresent(sub -> {
                sub.setLastStripeEventAt(newer(sub.getLastStripeEventAt(), eventCreated));
                revokeCoachSubscription(sub);
                log.info("[COACH_SUBSCRIPTION_DELETED coachId={}]", sub.getCoachId());
            });

        // Only publish SubscriptionExpiredEvent for player subscriptions (coaches have no video lifecycle)
        paymentPlayerSubscriptionRepository.findByStripeSubscriptionIdForUpdate(stripeSubscriptionId)
            .ifPresent(sub -> {
                sub.setLastStripeEventAt(newer(sub.getLastStripeEventAt(), eventCreated));
                revokePlayerSubscription(sub);
                log.info("[PLAYER_SUBSCRIPTION_DELETED playerId={}]", sub.getPlayerId());
            });
    }

    private void handleInvoicePaymentFailed(String stripeSubscriptionId, Instant eventCreated) {
        paymentCoachSubscriptionRepository.findByStripeSubscriptionIdForUpdate(stripeSubscriptionId)
            .ifPresent(sub -> {
                if (isStaleEvent(sub.getLastStripeEventAt(), eventCreated, "COACH", stripeSubscriptionId)) return;
                // fix/payment-subscription-entitlements: only a billing-healthy row moves to PAST_DUE —
                // a later failure on an UNPAID/INCOMPLETE/PAUSED row must not re-entitle it.
                if (isBillingHealthy(sub.getStatus())) sub.setStatus("PAST_DUE");
                // Only set pastDueSince on the first failure — do not overwrite on repeated failures
                if ("PAST_DUE".equals(sub.getStatus()) && sub.getPastDueSince() == null) sub.setPastDueSince(Instant.now());
                sub.setLastStripeEventAt(newer(sub.getLastStripeEventAt(), eventCreated));
                paymentCoachSubscriptionRepository.save(sub);
                log.warn("[COACH_PAYMENT_FAILED coachId={} pastDueSince={}]",
                    sub.getCoachId(), sub.getPastDueSince());
            });

        paymentPlayerSubscriptionRepository.findByStripeSubscriptionIdForUpdate(stripeSubscriptionId)
            .ifPresent(sub -> {
                if (isStaleEvent(sub.getLastStripeEventAt(), eventCreated, "PLAYER", stripeSubscriptionId)) return;
                if (isBillingHealthy(sub.getStatus())) sub.setStatus("PAST_DUE");
                if ("PAST_DUE".equals(sub.getStatus()) && sub.getPastDueSince() == null) sub.setPastDueSince(Instant.now());
                sub.setLastStripeEventAt(newer(sub.getLastStripeEventAt(), eventCreated));
                paymentPlayerSubscriptionRepository.save(sub);
                log.warn("[PLAYER_PAYMENT_FAILED playerId={} pastDueSince={}]",
                    sub.getPlayerId(), sub.getPastDueSince());
            });
    }

    /**
     * fix/payment-subscription-entitlements: a paid invoice returns a PAST_DUE/UNPAID/INCOMPLETE
     * subscription to ACTIVE (Stripe does the same) and clears pastDueSince, so the next failure gets a
     * full grace period. Previously only customer.subscription.updated could clear PAST_DUE and
     * nothing ever cleared pastDueSince.
     */
    private void handleInvoicePaid(String stripeSubscriptionId, Instant eventCreated) {
        paymentCoachSubscriptionRepository.findByStripeSubscriptionIdForUpdate(stripeSubscriptionId)
            .ifPresent(sub -> {
                if (isStaleEvent(sub.getLastStripeEventAt(), eventCreated, "COACH", stripeSubscriptionId)) return;
                String entitledBefore = SubscriptionEntitlement.effectiveCoachTier(sub.getTier(), sub.getStatus());
                if (RECOVERABLE_ON_PAYMENT.contains(sub.getStatus())) sub.setStatus("ACTIVE");
                sub.setPastDueSince(null);
                sub.setLastStripeEventAt(newer(sub.getLastStripeEventAt(), eventCreated));
                paymentCoachSubscriptionRepository.save(sub);
                syncCoachEntitlementChange(sub, entitledBefore);
                log.info("[COACH_INVOICE_PAID coachId={} status={}]", sub.getCoachId(), sub.getStatus());
            });

        paymentPlayerSubscriptionRepository.findByStripeSubscriptionIdForUpdate(stripeSubscriptionId)
            .ifPresent(sub -> {
                if (isStaleEvent(sub.getLastStripeEventAt(), eventCreated, "PLAYER", stripeSubscriptionId)) return;
                if (RECOVERABLE_ON_PAYMENT.contains(sub.getStatus())) sub.setStatus("ACTIVE");
                sub.setPastDueSince(null);
                sub.setLastStripeEventAt(newer(sub.getLastStripeEventAt(), eventCreated));
                paymentPlayerSubscriptionRepository.save(sub);
                log.info("[PLAYER_INVOICE_PAID playerId={} status={}]", sub.getPlayerId(), sub.getStatus());
            });
    }

    /** Status write shared by both subscription kinds: pastDueSince starts on PAST_DUE, clears on recovery. */
    private static void applyStripeStatus(java.util.function.Supplier<Instant> pastDueSince,
                                          java.util.function.Consumer<Instant> setPastDueSince,
                                          java.util.function.Consumer<String> setStatus, String status) {
        setStatus.accept(status);
        if (BILLING_HEALTHY.contains(status)) {
            setPastDueSince.accept(null);
        } else if ("PAST_DUE".equals(status) && pastDueSince.get() == null) {
            setPastDueSince.accept(Instant.now());
        }
    }

    private boolean isStaleEvent(Instant lastApplied, Instant eventCreated, String kind, String stripeSubscriptionId) {
        if (lastApplied != null && eventCreated != null && eventCreated.isBefore(lastApplied)) {
            log.info("[{}_STRIPE_EVENT_STALE stripeSubId={} eventCreated={} lastApplied={}] ignored",
                kind, stripeSubscriptionId, eventCreated, lastApplied);
            return true;
        }
        return false;
    }

    private static Instant newer(Instant current, Instant candidate) {
        if (candidate == null) return current;
        return current == null || candidate.isAfter(current) ? candidate : current;
    }

    /** Re-syncs the marketplace projection when a status change flipped the coach's entitled tier. */
    private void syncCoachEntitlementChange(PaymentCoachSubscription sub, String entitledBefore) {
        String entitledAfter = SubscriptionEntitlement.effectiveCoachTier(sub.getTier(), sub.getStatus());
        if (!entitledAfter.equals(entitledBefore)) {
            syncMarketplaceTierDeferrable(sub.getCoachId(), entitledAfter, "COACH_ENTITLEMENT_SYNC_DEFERRED");
            log.info("[COACH_ENTITLEMENT_CHANGED coachId={} from={} to={} status={}]",
                sub.getCoachId(), entitledBefore, entitledAfter, sub.getStatus());
        }
    }

    // ─── Revocation (deletion, grace expiry) ──────────────────────────────────────

    /**
     * Terminal revoke: free tier, CANCELLED, Stripe link cleared, and — fix/payment-subscription-entitlements
     * — every pending scheduled change voided (applied later, it used to re-grant a paid tier). Caller
     * holds the row lock.
     */
    private void revokeCoachSubscription(PaymentCoachSubscription sub) {
        sub.setTier(COACH_FREE_TIER);
        sub.setStatus("CANCELLED");
        sub.setStripeSubscriptionId(null);
        sub.setPastDueSince(null);
        sub.setCancelAtPeriodEnd(false);
        paymentCoachSubscriptionRepository.save(sub);
        coachSubscriptionChangeRepository.voidAllPendingForCoach(sub.getCoachId(), Instant.now());
        syncMarketplaceTierDeferrable(sub.getCoachId(), COACH_FREE_TIER, "COACH_REVOKE_TIER_SYNC_DEFERRED");
    }

    /** See {@link #revokeCoachSubscription}; also starts the video lifecycle via SubscriptionExpiredEvent. */
    private void revokePlayerSubscription(PaymentPlayerSubscription sub) {
        String billingInterval = sub.getBillingInterval();
        sub.setTier(PLAYER_FREE_TIER);
        sub.setStatus("CANCELLED");
        sub.setStripeSubscriptionId(null);
        sub.setPastDueSince(null);
        sub.setCancelAtPeriodEnd(false);
        paymentPlayerSubscriptionRepository.save(sub);
        playerSubscriptionChangeRepository.voidAllPendingForPlayer(sub.getPlayerId(), Instant.now());
        eventPublisher.publishEvent(
            new SubscriptionExpiredEvent(this, sub.getPlayerId(), billingInterval, Instant.now()));
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

    // ─── Subscribe / Stripe helpers (fix/payment-subscription-entitlements) ───────

    /**
     * A row still linked to a Stripe subscription blocks a new subscribe: ACTIVE/TRIALLING is
     * "already active"; PAST_DUE/UNPAID/INCOMPLETE/PAUSED must fix the payment method on the existing
     * subscription (a second subscription would bill in parallel). A CANCELLED row that still carries
     * an id is a pre-fix local-only grace cancel — its Stripe subscription is cancelled first.
     */
    private void assertCanSubscribe(String status, String stripeSubId, String kind, Object ownerId) {
        if (stripeSubId == null) return;
        if (isBillingHealthy(status)) {
            throw new PaymentGatewayException("payment.subscription.alreadyActive");
        }
        if ("CANCELLED".equals(status)) {
            try {
                stripeClient.cancelSubscriptionImmediately(stripeSubId);
                log.warn("[{}_STALE_STRIPE_SUBSCRIPTION_CANCELLED ownerId={} stripeSubId={}]", kind, ownerId, stripeSubId);
                return;
            } catch (StripeException e) {
                log.error("[{}_STALE_STRIPE_SUBSCRIPTION_CANCEL_FAILED ownerId={} stripeSubId={}]",
                    kind, ownerId, stripeSubId, e);
                throw new PaymentGatewayException("payment.subscription.stripeError");
            }
        }
        throw new PaymentGatewayException("payment.subscription.updatePaymentMethod");
    }

    /**
     * Creates the Stripe subscription with a per-attempt idempotency key and returns it only if it is
     * paid (ACTIVE/TRIALLING). A declined first payment throws CardException (error_if_incomplete) →
     * payment.subscription.paymentDeclined; any other non-active result is cancelled, never granted.
     */
    private Subscription createPaidStripeSubscription(String stripeCustomerId, String priceId,
                                                      String paymentMethodId, String kind, Object ownerId) {
        Subscription stripeSub;
        try {
            stripeSub = stripeClient.createSubscription(stripeCustomerId, priceId, paymentMethodId,
                "sub-create-" + UUID.randomUUID());
        } catch (StripeException e) {
            throw stripeFailure(kind + "_SUBSCRIBE", ownerId, e);
        }
        if (!isBillingHealthy(normalizeStripeStatus(stripeSub.getStatus()))) {
            log.warn("[{}_SUBSCRIBE_NOT_ACTIVE ownerId={} stripeSubId={} status={}] not granted, cancelling",
                kind, ownerId, stripeSub.getId(), stripeSub.getStatus());
            cancelStripeSubscriptionQuietly(stripeSub.getId(), kind, ownerId);
            throw new PaymentGatewayException("payment.subscription.paymentDeclined");
        }
        return stripeSub;
    }

    private void cancelStripeSubscriptionQuietly(String stripeSubId, String kind, Object ownerId) {
        try {
            stripeClient.cancelSubscriptionImmediately(stripeSubId);
        } catch (StripeException e) {
            // Left for StripeSubscriptionReconciliationScheduler's orphan alert.
            log.error("[{}_STRIPE_CANCEL_FAILED ownerId={} stripeSubId={}]", kind, ownerId, stripeSubId, e);
        }
    }

    /** Stripe price change with a per-attempt idempotency key; a declined proration charge is not granted. */
    private Subscription updateStripePrice(String stripeSubId, String priceId, boolean chargeNow,
                                           String keyPrefix, String logTag, Object ownerId) {
        try {
            return stripeClient.updateSubscriptionTier(stripeSubId, priceId, chargeNow, keyPrefix + UUID.randomUUID());
        } catch (StripeException e) {
            throw stripeFailure(logTag, ownerId, e);
        }
    }

    private PaymentGatewayException stripeFailure(String logTag, Object ownerId, StripeException e) {
        if (e instanceof CardException) {
            log.warn("[{}_PAYMENT_DECLINED ownerId={} code={}]", logTag, ownerId, e.getCode());
            return new PaymentGatewayException("payment.subscription.paymentDeclined", e);
        }
        log.error("[{}_STRIPE_FAILED ownerId={}]", logTag, ownerId, e);
        return new PaymentGatewayException("payment.subscription.stripeError", e);
    }

    /** Tier changes act on the live Stripe subscription, so they require it to be billing-healthy. */
    private static String requireBillingHealthy(String status, String stripeSubId) {
        if (stripeSubId == null) {
            throw new PaymentGatewayException("payment.subscription.noActiveStripeSubscription");
        }
        if (!isBillingHealthy(status)) {
            throw new PaymentGatewayException("payment.subscription.notActive");
        }
        return stripeSubId;
    }

    private static boolean isBillingHealthy(String status) {
        return status != null && BILLING_HEALTHY.contains(status);
    }

    private static boolean isOtherLiveSubscription(String status, String existingId, String newId) {
        return existingId != null && !existingId.equals(newId) && !"CANCELLED".equals(status);
    }

    private String requireConfiguredPrice(String key) {
        String priceId = configuredPriceOrNull(key);
        if (priceId == null) {
            throw new PaymentGatewayException("payment.subscription.priceNotConfigured");
        }
        return priceId;
    }

    private String configuredPriceOrNull(String key) {
        String priceId = configService.getString(key);
        return priceId == null || priceId.isBlank() ? null : priceId;
    }

    private static String coachPriceKey(String tier) {
        return "subscription.coach." + tier.toLowerCase() + ".monthly.priceId";
    }

    private static String playerPriceKey(String tier, String billingInterval) {
        return "subscription.player." + tier.toLowerCase() + "." + billingInterval.toLowerCase() + ".priceId";
    }

    private void syncMarketplaceTierDeferrable(UUID coachId, String tier, String deferredLogTag) {
        try {
            syncMarketplaceTier(coachId, tier);
        } catch (PessimisticLockingFailureException e) {
            log.warn("[{} coachId={} tier={}]", deferredLogTag, coachId, tier, e);
        }
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
            .orElseThrow(() -> new PaymentGatewayException(
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

    private void assertPlayerOwnership(Long callerUserId, Long playerId) {
        boolean owned = parentPlayerLinkRepository.existsByParentIdAndPlayerId(callerUserId, playerId)
            || playerProfileRepository.existsByIdAndUserId(playerId, callerUserId);
        if (!owned) {
            throw new PaymentGatewayException(
                "payment.subscription.playerOwnership");
        }
    }

    private void validatePlayerTierInterval(String tier, String billingInterval) {
        if (("SEMI_PRO".equalsIgnoreCase(tier) || "PRO".equalsIgnoreCase(tier))
                && !"YEARLY".equalsIgnoreCase(billingInterval)) {
            throw new PaymentGatewayException(
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

    private Instant extractEventCreated(Map<String, Object> data) {
        Object val = data == null ? null : data.get("event_created");
        return val instanceof Number n ? Instant.ofEpochSecond(n.longValue()) : null;
    }

    // fix/payment-subscription-entitlements: incomplete/unpaid/paused are persisted as non-entitled
    // statuses (V194 widened the CHECKs); incomplete_expired/canceled are handled as deletions upstream.
    private String normalizeStripeStatus(String stripeStatus) {
        if (stripeStatus == null) return null;
        return switch (stripeStatus) {
            case "active" -> "ACTIVE";
            case "past_due" -> "PAST_DUE";
            case "canceled", "cancelled", "incomplete_expired" -> "CANCELLED";
            case "trialing" -> "TRIALLING";
            case "incomplete" -> "INCOMPLETE";
            case "unpaid" -> "UNPAID";
            case "paused" -> "PAUSED";
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
