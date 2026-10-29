package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.payment.config.PaymentProperties;
import com.softropic.skillars.platform.payment.contract.CoachStripeStatusResponse;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.payment.repo.CoachStripeAccount;
import com.softropic.skillars.platform.payment.repo.CoachStripeAccountRepository;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.oauth.TokenResponse;
import com.stripe.net.OAuth;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class StripeOnboardingService {

    /**
     * fix/payment-stripe-connect-webhook-endpoint: minimum gap between two status-poll-triggered
     * {@code Account.retrieve} calls for the same coach. The status endpoint is called on every visit
     * to the payment-settings page, so this keeps a page that is reloaded (or a script) from turning
     * into one Stripe API call per request. Per instance, in memory: a soft guard, not a quota.
     */
    static final Duration STATUS_SYNC_MIN_INTERVAL = Duration.ofSeconds(30);

    /**
     * The fallback has no Stripe event {@code created} to order by, so it records its snapshot as
     * taken this long BEFORE the request started. Any {@code account.updated} created after that
     * point (by Stripe's clock) is treated as newer than the fetch: a fetch never overwrites it, and
     * a late delivery of it is not rejected as stale because of a few seconds of clock skew between
     * this server and Stripe.
     */
    static final Duration FETCH_SNAPSHOT_SKEW_ALLOWANCE = Duration.ofSeconds(30);

    /** Bound on {@link #lastStatusSyncAttempt}; past it the map is cleared (it only rate-limits). */
    private static final int MAX_TRACKED_SYNC_ATTEMPTS = 10_000;

    private final CoachStripeAccountRepository coachStripeAccountRepository;
    private final PaymentProperties paymentProperties;
    private final StripeClient stripeClient;
    private final CoachStripeAccountStatusApplier accountStatusApplier;
    private final Clock clock;

    private final Map<UUID, Instant> lastStatusSyncAttempt = new ConcurrentHashMap<>();

    /** Self-reference so @Transactional on upsertCoachStripeAccount is honoured via the Spring proxy. */
    @Autowired
    @Lazy
    private StripeOnboardingService self;

    /** Outside @Transactional — Stripe OAuth call must not run inside a DB transaction. */
    public String generateOnboardingUrl(UUID coachId) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("scope", "read_write");
            // oauthCallbackUrl points to the backend callback endpoint, not the frontend page
            params.put("redirect_uri", paymentProperties.getOauthCallbackUrl());
            params.put("state", coachId.toString());
            return OAuth.authorizeUrl(params, null);
        } catch (StripeException e) {
            log.error("[STRIPE_CALL_FAILED coachId={} errorMessage={}]", coachId, e.getMessage(), e);
            throw new PaymentGatewayException("payment.providerUnavailable", e);
        }
    }

    /** OAuth exchange is outside @Transactional; DB upsert runs via self-proxy to honour @Transactional. */
    public void handleOAuthCallback(UUID coachId, String code) {
        TokenResponse token = exchangeOAuthCode(coachId, code);
        String stripeUserId = token.getStripeUserId();
        if (stripeUserId == null || stripeUserId.isBlank()) {
            log.error("[STRIPE_OAUTH_NULL_USER_ID coachId={}]", coachId);
            throw new PaymentGatewayException("payment.providerUnavailable");
        }
        self.upsertCoachStripeAccount(coachId, stripeUserId);
        log.info("[STRIPE_OAUTH_CALLBACK_SUCCESS coachId={} stripeAccountId={}]", coachId, stripeUserId);
        // A Standard account that already exists is often fully enabled at this point, and Stripe
        // may send no account.updated at all for it. Read the status now instead of waiting.
        lastStatusSyncAttempt.put(coachId, clock.instant());
        syncAccountFromStripe(coachId, stripeUserId);
    }

    private TokenResponse exchangeOAuthCode(UUID coachId, String code) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("grant_type", "authorization_code");
            params.put("code", code);
            return OAuth.token(params, null);
        } catch (StripeException e) {
            log.error("[STRIPE_CALL_FAILED coachId={} errorMessage={}]", coachId, e.getMessage(), e);
            throw new PaymentGatewayException("payment.providerUnavailable", e);
        }
    }

    /**
     * Must be public so the Spring CGLIB proxy intercepts it and @Transactional is applied.
     * Self-calls from handleOAuthCallback go through self.upsertCoachStripeAccount (not this).
     */
    @Transactional
    public void upsertCoachStripeAccount(UUID coachId, String stripeUserId) {
        coachStripeAccountRepository.findById(coachId).ifPresentOrElse(
            existing -> {
                if (!stripeUserId.equals(existing.getStripeAccountId())) {
                    // A different Stripe account: the old one's capability does not describe it.
                    existing.setTransfersCapability(null);
                }
                // The row is reset to PENDING below, so the previous snapshot's ordering marker no
                // longer describes it either; without clearing it, the post-callback fetch (whose
                // snapshot time is deliberately conservative) could be skipped as "stale" and leave a
                // re-connected, fully enabled account PENDING until its next account.updated.
                existing.setLastEventCreatedAt(null);
                existing.setStripeAccountId(stripeUserId);
                existing.setOnboardingStatus("PENDING");
                existing.setChargesEnabled(false);
                existing.setPayoutsEnabled(false);
                // dirty-checking persists changes at transaction commit; entity is managed within this TX
            },
            () -> {
                CoachStripeAccount acct = new CoachStripeAccount();
                acct.setCoachId(coachId);
                acct.setStripeAccountId(stripeUserId);
                acct.setOnboardingStatus("PENDING");
                coachStripeAccountRepository.save(acct);
            }
        );
    }

    /**
     * Outside {@code @Transactional}: while the account is not yet {@code COMPLETE}, this may first
     * retrieve it from Stripe (rate-limited per coach, see {@link #STATUS_SYNC_MIN_INTERVAL}) so a
     * missed {@code account.updated} cannot leave the coach stuck. A Stripe failure is logged and the
     * stored status is returned unchanged.
     */
    public CoachStripeStatusResponse getStripeStatus(UUID coachId) {
        Optional<CoachStripeAccount> stored = coachStripeAccountRepository.findById(coachId);
        if (stored.isEmpty()) {
            return new CoachStripeStatusResponse(coachId, "NOT_CONNECTED", false, false);
        }
        CoachStripeAccount account = stored.get();
        if (!CoachStripeAccountStatusApplier.STATUS_COMPLETE.equals(account.getOnboardingStatus())
                && tryAcquireStatusSync(coachId)
                && syncAccountFromStripe(coachId, account.getStripeAccountId())) {
            account = coachStripeAccountRepository.findById(coachId).orElse(account);
        }
        return new CoachStripeStatusResponse(
            coachId, account.getOnboardingStatus(), account.isChargesEnabled(), account.isPayoutsEnabled());
    }

    /** True at most once per {@link #STATUS_SYNC_MIN_INTERVAL} per coach (atomic per key). */
    boolean tryAcquireStatusSync(UUID coachId) {
        if (lastStatusSyncAttempt.size() > MAX_TRACKED_SYNC_ATTEMPTS) {
            lastStatusSyncAttempt.clear();
        }
        Instant now = clock.instant();
        boolean[] acquired = {false};
        lastStatusSyncAttempt.compute(coachId, (id, last) -> {
            if (last == null || !now.isBefore(last.plus(STATUS_SYNC_MIN_INTERVAL))) {
                acquired[0] = true;
                return now;
            }
            return last;
        });
        return acquired[0];
    }

    /**
     * Retrieves the connected account from Stripe (outside any DB transaction) and applies it through
     * {@link CoachStripeAccountStatusApplier}, the same rules as the {@code account.updated} webhook.
     * Never throws: this is a fallback, the webhook remains the primary path.
     *
     * @return true if the stored status was updated
     */
    boolean syncAccountFromStripe(UUID coachId, String stripeAccountId) {
        if (stripeAccountId == null || stripeAccountId.isBlank()) {
            return false;
        }
        // Captured before the request: the snapshot reflects Stripe's state at some point after this.
        Instant snapshotAt = clock.instant().truncatedTo(ChronoUnit.SECONDS).minus(FETCH_SNAPSHOT_SKEW_ALLOWANCE);
        Account acct;
        try {
            acct = stripeClient.retrieveAccount(stripeAccountId);
        } catch (StripeException e) {
            log.warn("[STRIPE_ACCOUNT_SYNC_FAILED coachId={} stripeAccountId={} errorMessage={}]",
                coachId, stripeAccountId, e.getMessage());
            return false;
        } catch (RuntimeException e) {
            log.warn("[STRIPE_ACCOUNT_SYNC_FAILED coachId={} stripeAccountId={}]", coachId, stripeAccountId, e);
            return false;
        }
        if (acct == null) {
            return false;
        }
        try {
            return self.applyFetchedAccount(coachId, acct, snapshotAt);
        } catch (ObjectOptimisticLockingFailureException e) {
            // A webhook updated the row concurrently; its (newer) snapshot wins.
            log.info("[STRIPE_ACCOUNT_SYNC_CONCURRENT_UPDATE coachId={}] skipped", coachId);
            return false;
        } catch (RuntimeException e) {
            log.warn("[STRIPE_ACCOUNT_SYNC_APPLY_FAILED coachId={} stripeAccountId={}]", coachId, stripeAccountId, e);
            return false;
        }
    }

    /** Must be public so the Spring proxy applies {@code @Transactional}; called via {@link #self}. */
    @Transactional
    public boolean applyFetchedAccount(UUID coachId, Account acct, Instant snapshotAt) {
        return coachStripeAccountRepository.findById(coachId)
            // The coach may have re-connected a different account while the request was in flight.
            .filter(row -> Objects.equals(row.getStripeAccountId(), acct.getId()))
            .map(row -> accountStatusApplier.apply(row, acct, snapshotAt, "fetch"))
            .orElse(false);
    }
}
