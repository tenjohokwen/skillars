package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.infrastructure.util.ClockProvider;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.security.repo.LoginAttempt;
import com.softropic.skillars.platform.security.repo.LoginAttemptRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;

import lombok.RequiredArgsConstructor;

/**
 * The failed-login rate limiter for the one password entry point, {@code AuthService.login}
 * ({@code POST /api/auth/login}). Backed by {@code main.login_attempts}, so it holds across every
 * application instance and survives restarts.
 *
 * <p>The key is SHA-256(lower-cased login | canonical IP), so an attacker on another IP cannot lock
 * the real user out of their own IP (account-DoS prevention).
 *
 * <p><b>Atomicity.</b> {@link #checkLimited} claims a transaction-scoped advisory lock on that key
 * before counting, and must run inside the caller's transaction ({@code MANDATORY}) so the lock is
 * held until the caller has recorded (and committed) its own failure. Without it, N parallel wrong
 * passwords all read a count below the budget and then all insert — the budget was exceeded by the
 * degree of parallelism. The lock is a TRY lock re-tried for a bounded {@link #CLAIM_WAIT}: an
 * ordinary overlap (two tabs, a client retry) just waits for the other attempt's bcrypt check and
 * transaction to finish, while a sustained burst on one key gives up after that bound and is refused
 * as rate limited (429, retry after 1s) — so a burst cannot park pooled connections behind the lock
 * for longer than the bound. A blocking {@code pg_advisory_xact_lock} would have no such bound.
 */
@Service
@RequiredArgsConstructor
public class LoginRateLimiter {

    private final LoginAttemptRepository loginAttemptRepository;
    private final ConfigService configService;

    /** Retry-After for an attempt refused because the (login, ip) key stayed busy for the whole {@link #CLAIM_WAIT}. */
    static final long CONCURRENT_ATTEMPT_RETRY_AFTER_SECONDS = 1L;
    /** How long an attempt keeps re-trying the key's try-lock (one bcrypt check plus commit is ~0.1-0.2s). */
    static final Duration CLAIM_WAIT = Duration.ofSeconds(2);
    private static final long CLAIM_RETRY_SLEEP_MS = 50L;

    /** A (login, ip) pair that has used up its failed-attempt budget for the current window. */
    public record Limited(long recentAttempts, long retryAfterSeconds) {}

    /**
     * Claims this (login, ip) pair for the caller's transaction (waiting up to {@link #CLAIM_WAIT} for
     * an in-flight attempt on the same pair), then reports whether the pair has used up its budget.
     * If the pair stays busy for the whole wait, this attempt is reported as limited (retry after 1s)
     * without being counted.
     *
     * @return the limit state when this (login, ip) pair has used up its failed-attempt budget for
     *         the current window (or is busy), or empty when an attempt is allowed
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Limited> checkLimited(String login, String clientIp) {
        int maxAttempts = maxAttempts();
        int lockWindowMin = lockWindowMinutes();
        String identifier = identifier(login, clientIp);
        boolean claimed = claim(identifier);
        Instant now = Instant.now(ClockProvider.getClock());
        long recentAttempts = loginAttemptRepository
            .countByIdentifierAndAttemptedAtAfter(identifier, now.minus(lockWindowMin, ChronoUnit.MINUTES));
        if (!claimed) {
            return Optional.of(new Limited(recentAttempts, CONCURRENT_ATTEMPT_RETRY_AFTER_SECONDS));
        }
        if (recentAttempts < maxAttempts) {
            return Optional.empty();
        }
        // Oldest attempt INSIDE the window: rows live 24h (AuthCleanupService), so the oldest row
        // overall can predate the window and would yield a Retry-After of 1s for a 15-min lock.
        long retryAfterSeconds = loginAttemptRepository
            .findFirstByIdentifierAndAttemptedAtAfterOrderByAttemptedAtAsc(
                identifier, now.minus(lockWindowMin, ChronoUnit.MINUTES))
            .map(a -> Math.max(1L, ChronoUnit.SECONDS.between(
                now, a.getAttemptedAt().plus(lockWindowMin, ChronoUnit.MINUTES))))
            .orElse((long) lockWindowMin * 60);
        return Optional.of(new Limited(recentAttempts, retryAfterSeconds));
    }

    private boolean claim(String identifier) {
        long deadline = System.nanoTime() + CLAIM_WAIT.toNanos();
        while (true) {
            if (loginAttemptRepository.tryAcquireIdentifierLock(identifier)) {
                return true;
            }
            if (System.nanoTime() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(CLAIM_RETRY_SLEEP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /**
     * Records one failed attempt. Joins the caller's transaction when there is one (see
     * {@code AuthService.login}'s {@code noRollbackFor}), otherwise commits on its own.
     */
    @Transactional
    public void recordFailure(String login, String clientIp) {
        loginAttemptRepository.save(new LoginAttempt(
            identifier(login, clientIp), subjectHash(login), Instant.now(ClockProvider.getClock())));
    }

    /**
     * Admin "unlock login" (V171): deletes every recorded failed attempt for this account across all
     * IPs, lifting the limit immediately instead of waiting out the window.
     *
     * @return the number of attempt rows removed
     */
    @Transactional
    public int clear(String login) {
        return loginAttemptRepository.deleteBySubjectHash(subjectHash(login));
    }

    /** Admin read-out (V171): failed attempts for this account, any IP, inside the current window. */
    @Transactional(readOnly = true)
    public long countRecentFailures(String login) {
        Instant windowStart = Instant.now(ClockProvider.getClock()).minus(lockWindowMinutes(), ChronoUnit.MINUTES);
        return loginAttemptRepository.countBySubjectHashAndAttemptedAtAfter(subjectHash(login), windowStart);
    }

    private int maxAttempts() {
        return configService.find("security.login.max-attempts").map(Integer::parseInt).orElse(5);
    }

    private int lockWindowMinutes() {
        return configService.find("security.login.lock-window-minutes").map(Integer::parseInt).orElse(15);
    }

    private static String identifier(String login, String clientIp) {
        return sha256Hex(login.toLowerCase() + "|" + canonicaliseIp(clientIp));
    }

    private static String subjectHash(String login) {
        return sha256Hex(login.toLowerCase());
    }

    private static String canonicaliseIp(String ip) {
        if (ip == null) return "unknown";
        if ("0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) return "127.0.0.1";
        if (ip.startsWith("::ffff:")) return ip.substring(7);
        return ip;
    }

    private static String sha256Hex(String raw) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
