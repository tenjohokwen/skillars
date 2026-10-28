package com.softropic.skillars.platform.security.service;

import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import com.softropic.skillars.infrastructure.security.RequestMetadata;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.util.concurrent.TimeUnit;


/**
 * Login decision for the legacy {@code POST /authenticate} filter chain
 * ({@code FraudAwareAuthenticationManager}).
 *
 * <p>Failed-attempt counting is delegated to the DB-backed {@link LoginRateLimiter} — the same
 * limiter, with the same SHA-256(login|ip) key and the same configurable budget/window, that
 * {@code POST /api/auth/login} enforces. It previously lived in per-instance Guava caches here
 * (per client, per ip+user, per user), which multiplied the budget by the number of instances and
 * was reset by every restart.
 *
 * <p>What remains in memory is the client blacklist fed by {@code FraudEvent}s (a tampered or
 * stolen JWT): it keys on a client identifier the client itself supplies, so it is a best-effort
 * per-instance signal, not a counter anyone relies on for brute-force protection.
 */
@Service("loginAttemptService")
public class LoginAttemptsService implements LoginDecisionManager<RequestMetadata>,
                                             LoginAttemptConsumer<RequestMetadata> {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptsService.class);

    private final Cache<String, Boolean> blacklistedClients;

    private final ClientIdAccessDecisionManager clientIdAccessDecisionVoter;

    private final LoginRateLimiter loginRateLimiter;

    @Autowired
    public LoginAttemptsService(final ClientIdAccessDecisionManager clientIdAccessDecisionMgr,
                                final LoginRateLimiter loginRateLimiter) {
        this(clientIdAccessDecisionMgr, loginRateLimiter, Ticker.systemTicker());
    }

    // Constructor for testing with a controllable ticker
    public LoginAttemptsService(final ClientIdAccessDecisionManager clientIdAccessDecisionVoter,
                                final LoginRateLimiter loginRateLimiter,
                                final Ticker ticker) {
        this.blacklistedClients = CacheBuilder.newBuilder()
                                              .ticker(ticker)
                                              .expireAfterWrite(30, TimeUnit.DAYS)
                                              .build();
        this.clientIdAccessDecisionVoter = clientIdAccessDecisionVoter;
        this.loginRateLimiter = loginRateLimiter;
    }

    /**
     * No-op: a successful login does not erase earlier failures, matching {@code /api/auth/login}.
     * Clearing them here would let anyone who knows one password reset the shared counter for the
     * (login, ip) pair; they age out of the window, or an admin clears them ("unlock login").
     */
    @Override
    public void loginSucceeded(final RequestMetadata metadata) {
        // intentionally empty
    }

    /** Records one failed {@code /authenticate} attempt in the shared DB-backed limiter. */
    @Override
    public void loginFailed(final RequestMetadata metadata) {
        //username will be blank if userName validation fails
        if (StringUtils.isNotBlank(metadata.getUserName())) {
            loginRateLimiter.recordFailure(metadata.getUserName(), metadata.getIpAddress());
        }
    }

    @Override
    public boolean isAllowed(final RequestMetadata metadata) {
        if (StringUtils.isBlank(metadata.getUserName())) {
            // Nothing is recorded for blank usernames (see loginFailed).
            // Upstream validation should reject blank usernames before this point.
            return true;
        }
        return isBelowAttemptLimit(metadata)
               //Explicitly invoke this since call does not go down to controller level. AccessDecisionVoters are called by interceptors. (interceptors are called before controllers)
               && isClientIdAllowed()
               && clientNotBlacklisted(metadata.getClientIdentifier());
    }

    private boolean isBelowAttemptLimit(final RequestMetadata metadata) {
        // Per (login, ip) — shared with /api/auth/login — plus an account-wide cap across all IPs,
        // which the old per-user cache provided and which this unused-by-the-SPA path keeps.
        final boolean limited = loginRateLimiter.checkLimited(metadata.getUserName(), metadata.getIpAddress())
                                                .isPresent()
                                || loginRateLimiter.isAccountLimited(metadata.getUserName());
        if (limited) {
            log.error("Login attempts exceeded for current window",
                kv("operation", "login_attempts"),
                kv("status", "ATTEMPTS_EXCEEDED"));
        }
        return !limited;
    }

    private boolean isClientIdAllowed() {
        final boolean clientIdAllowed = clientIdAccessDecisionVoter.isClientIdAllowed();
        if (!clientIdAllowed) {
            log.error("Client id is already blacklisted",
                kv("operation", "login_attempts"),
                kv("status", "CLIENT_BLACKLISTED"));
        }
        return clientIdAllowed;
    }

    private boolean clientNotBlacklisted(final String clientId) {
        if (StringUtils.isNotBlank(clientId) && blacklistedClients.getIfPresent(clientId) != null) {
            log.error("Client is already blacklisted",
                kv("operation", "login_attempts"),
                kv("status", "CLIENT_BLACKLISTED"));
            return false;
        }
        return true;
    }

    /**
     * Blacklists the client (by its client identifier) for 30 days, in this instance's memory only.
     * @param metadata provides the client data.
     */
    @Override
    public void blacklistClient(final RequestMetadata metadata) {
        log.warn("Blacklisting client",
            kv("operation", "login_attempts"),
            kv("status", "CLIENT_BLACKLISTING"));
        //The user is probably not known so just the client is blacklisted.
        //The getClientIdentifier here may not exist e.g for a browser (or else many users will share the same key)
        if (StringUtils.isNotBlank(metadata.getClientIdentifier())) {
            blacklistedClients.put(metadata.getClientIdentifier(), Boolean.TRUE);
        }
    }

    public void unblacklistClient(final String clientId) {
        blacklistedClients.invalidate(clientId);
    }

    /** Clears the in-memory client blacklist. Failed-attempt rows live in main.login_attempts. */
    public void resetLoginRecording() {
        blacklistedClients.invalidateAll();
    }

    @Override
    public void unblockClient(final RequestMetadata metadata) {
        if (StringUtils.isNotBlank(metadata.getClientIdentifier())) {
            blacklistedClients.invalidate(metadata.getClientIdentifier());
        }
    }
}
