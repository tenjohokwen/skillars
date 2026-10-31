package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.config.service.ConfigBounds;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.config.PaymentProperties;
import com.softropic.skillars.platform.payment.contract.CoachStripeStatusResponse;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.payment.repo.CoachStripeAccount;
import com.softropic.skillars.platform.payment.repo.CoachStripeAccountRepository;
import com.softropic.skillars.platform.payment.repo.StripeOAuthState;
import com.softropic.skillars.platform.payment.repo.StripeOAuthStateRepository;
import com.softropic.skillars.platform.security.contract.event.AccountChangeEvent;
import com.softropic.skillars.platform.security.contract.event.AccountChangeUserInfo;
import com.softropic.skillars.platform.security.repo.UserRepository;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.oauth.TokenResponse;
import com.stripe.net.OAuth;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
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

    /** How long an onboarding link's OAuth {@code state} stays redeemable; Stripe's consent screen is interactive. */
    static final Duration OAUTH_STATE_TTL = Duration.ofMinutes(10);

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Bound on {@link #lastStatusSyncAttempt}; past it the map is cleared (it only rate-limits). */
    private static final int MAX_TRACKED_SYNC_ATTEMPTS = 10_000;

    private final CoachStripeAccountRepository coachStripeAccountRepository;
    private final PaymentProperties paymentProperties;
    private final StripeClient stripeClient;
    private final CoachStripeAccountStatusApplier accountStatusApplier;
    private final Clock clock;
    private final StripeOAuthStateRepository oauthStateRepository;
    private final ConfigService configService;
    private final CoachProfileRepository coachProfileRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

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
            params.put("state", mintOAuthState(coachId));
            return OAuth.authorizeUrl(params, null);
        } catch (StripeException e) {
            log.error("[STRIPE_CALL_FAILED coachId={} errorMessage={}]", coachId, e.getMessage(), e);
            throw new PaymentGatewayException("payment.providerUnavailable", e);
        }
    }

    /**
     * fix/security-payout-oauth-session-revocation: the OAuth {@code state} is a random single-use nonce bound
     * to this coach and valid for {@link #OAUTH_STATE_TTL}. It used to be the coach's own (public) id, so any
     * logged-in coach could be lured into linking an attacker's Stripe account through a forged callback URL.
     */
    private String mintOAuthState(UUID coachId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        oauthStateRepository.purgeForCoachAndExpired(coachId, now);
        oauthStateRepository.save(new StripeOAuthState(sha256Hex(nonce), coachId, now.plus(OAUTH_STATE_TTL), now));
        return nonce;
    }

    /**
     * True if {@code state} was minted for this coach, has not expired and had not been used; it is consumed
     * by the call, so a replay returns false. The value is never logged.
     */
    public boolean consumeOAuthState(UUID coachId, String state) {
        if (state == null || state.isBlank()) {
            return false;
        }
        return oauthStateRepository.consume(sha256Hex(state), coachId, clock.instant()) == 1;
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
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
        Instant connectedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        self.upsertCoachStripeAccount(coachId, stripeUserId, connectedAt);
        log.info("[STRIPE_OAUTH_CALLBACK_SUCCESS coachId={} stripeAccountId={}]", coachId, stripeUserId);
        // A Standard account that already exists is often fully enabled at this point, and Stripe
        // may send no account.updated at all for it. Read the status now instead of waiting. The
        // fetch runs after the connect, so its snapshot is stamped with the connect time (equal to
        // the marker the upsert set, so it applies).
        lastStatusSyncAttempt.put(coachId, clock.instant());
        syncAccountFromStripe(coachId, stripeUserId, connectedAt);
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

    /** Connect at the current time; see {@link #upsertCoachStripeAccount(UUID, String, Instant)}. */
    @Transactional
    public void upsertCoachStripeAccount(UUID coachId, String stripeUserId) {
        upsertCoachStripeAccount(coachId, stripeUserId, clock.instant().truncatedTo(ChronoUnit.SECONDS));
    }

    /**
     * Must be public so the Spring CGLIB proxy intercepts it and @Transactional is applied.
     * Self-calls from handleOAuthCallback go through self.upsertCoachStripeAccount (not this).
     *
     * <p>A (re)connect starts a new snapshot history: status PENDING, flags and transfers capability
     * cleared (including the {@code deauthorized} sentinel, so a coach who disconnected can come
     * back), and {@code last_event_created_at} set to {@code connectedAt}. Anything Stripe created
     * before the connect, such as a late-delivered {@code account.application.deauthorized} or
     * {@code account.updated} from the previous connection, is then ignored as stale.
     */
    @Transactional
    public void upsertCoachStripeAccount(UUID coachId, String stripeUserId, Instant connectedAt) {
        coachStripeAccountRepository.findById(coachId).ifPresentOrElse(
            existing -> {
                if (!Objects.equals(existing.getStripeAccountId(), stripeUserId)) {
                    holdAndNotifyOnDestinationChange(existing, stripeUserId, connectedAt);
                }
                existing.setStripeAccountId(stripeUserId);
                existing.setOnboardingStatus("PENDING");
                existing.setChargesEnabled(false);
                existing.setPayoutsEnabled(false);
                existing.setTransfersCapability(null);
                existing.setLastEventCreatedAt(connectedAt);
                // dirty-checking persists changes at transaction commit; entity is managed within this TX
            },
            () -> {
                CoachStripeAccount acct = new CoachStripeAccount();
                acct.setCoachId(coachId);
                acct.setStripeAccountId(stripeUserId);
                acct.setOnboardingStatus("PENDING");
                acct.setLastEventCreatedAt(connectedAt);
                coachStripeAccountRepository.save(acct);
            }
        );
    }

    /**
     * fix/security-payout-oauth-session-revocation: replacing an existing payout destination is the step an
     * account takeover (or a lured OAuth link) needs, so transfers to the new account wait out a cooling-off
     * ({@link ConfigBounds#PAYOUT_ACCOUNT_CHANGE_HOLD_HOURS}, 72h) that CoachPayoutTransferHandler enforces at
     * release time, and the coach is told by email. A first-time connection never reaches this. Re-authentication
     * before the change is not part of this fix.
     */
    private void holdAndNotifyOnDestinationChange(CoachStripeAccount existing, String newStripeAccountId,
                                                  Instant connectedAt) {
        long holdHours = configService.getBoundedLong(ConfigBounds.PAYOUT_ACCOUNT_CHANGE_HOLD_HOURS);
        if (holdHours > 0) {
            Instant holdUntil = connectedAt.plus(Duration.ofHours(holdHours));
            // Never shorten a hold a previous change is still serving.
            if (existing.getPayoutHoldUntil() == null || holdUntil.isAfter(existing.getPayoutHoldUntil())) {
                existing.setPayoutHoldUntil(holdUntil);
            }
        }
        log.warn("[STRIPE_PAYOUT_DESTINATION_CHANGED coachId={} newStripeAccountId={} holdHours={}]",
            existing.getCoachId(), newStripeAccountId, holdHours);
        coachProfileRepository.findById(existing.getCoachId())
            .flatMap(coach -> userRepository.findOneById(coach.getUserId()))
            .ifPresentOrElse(
                user -> eventPublisher.publishEvent(new AccountChangeEvent(
                    AccountChangeEvent.Action.PAYOUT_ACCOUNT_CHANGED, null, null,
                    new AccountChangeUserInfo(user.getEmail(), user.getFirstName(), user.getLastName(),
                        user.getLangKey(), user.getTitle(),
                        user.getGender() != null ? user.getGender().name() : null))),
                () -> log.warn("[STRIPE_PAYOUT_DESTINATION_CHANGED_NO_RECIPIENT coachId={}]", existing.getCoachId()));
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
        // A disconnected account cannot be read by the platform any more; only a reconnect helps.
        if (!CoachStripeAccountStatusApplier.STATUS_COMPLETE.equals(account.getOnboardingStatus())
                && !CoachStripeAccountStatusApplier.isDeauthorized(account)
                && tryAcquireStatusSync(coachId)
                && syncAccountFromStripe(coachId, account.getStripeAccountId(), pollSnapshotTime())) {
            account = coachStripeAccountRepository.findById(coachId).orElse(account);
        }
        return new CoachStripeStatusResponse(coachId, account.getOnboardingStatus(), account.isChargesEnabled(),
            account.isPayoutsEnabled(), CoachStripeAccountStatusApplier.isDeauthorized(account));
    }

    /** Captured before the request: the fetched state is at least this new (minus the skew allowance). */
    private Instant pollSnapshotTime() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS).minus(FETCH_SNAPSHOT_SKEW_ALLOWANCE);
    }

    /**
     * fix/prod-readiness-gaps: re-reads every connected {@code COMPLETE} account from Stripe. The status
     * poll in {@link #getStripeStatus} stops once an account is COMPLETE, so without this a later
     * restriction (requirements due, charges or payouts disabled) reaches the platform only through an
     * {@code account.updated} webhook; one missed delivery left the coach bookable and payable. Same
     * apply rules as the webhook. Keyset pages of {@code pageSize}, at most {@code maxPages} per run.
     *
     * @return how many accounts changed
     */
    public int resyncCompleteAccounts(int pageSize, int maxPages) {
        int checked = 0;
        int changed = 0;
        // The nil UUID: Postgres orders uuid by unsigned bytes, so every real coach id sorts after it.
        UUID after = new UUID(0L, 0L);
        for (int pages = 0; pages < maxPages; pages++) {
            List<CoachStripeAccount> page = coachStripeAccountRepository.findConnectedByStatusAfter(
                CoachStripeAccountStatusApplier.STATUS_COMPLETE, CoachStripeAccountStatusApplier.TRANSFERS_DEAUTHORIZED,
                after, PageRequest.of(0, pageSize));
            for (CoachStripeAccount account : page) {
                checked++;
                if (syncAccountFromStripe(account.getCoachId(), account.getStripeAccountId(), pollSnapshotTime())) {
                    changed++;
                }
            }
            if (page.size() < pageSize) {
                break;
            }
            after = page.get(page.size() - 1).getCoachId();
        }
        log.info("[STRIPE_ACCOUNT_RESYNC] {} COMPLETE account(s) re-read from Stripe, {} changed", checked, changed);
        return changed;
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
     * Retrieves the connected account from Stripe (outside any DB transaction) and applies it, as a
     * snapshot taken at {@code snapshotAt}, through
     * {@link CoachStripeAccountStatusApplier}, the same rules as the {@code account.updated} webhook.
     * Never throws: this is a fallback, the webhook remains the primary path.
     *
     * @return true if the stored status was updated
     */
    boolean syncAccountFromStripe(UUID coachId, String stripeAccountId, Instant snapshotAt) {
        if (stripeAccountId == null || stripeAccountId.isBlank()) {
            return false;
        }
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
