package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.infrastructure.util.ClockProvider;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.security.repo.LoginAttempt;
import com.softropic.skillars.platform.security.repo.LoginAttemptRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;

import lombok.RequiredArgsConstructor;

/**
 * The one failed-login rate limiter, backed by {@code main.login_attempts} so it holds across every
 * application instance and survives restarts. Shared by both password entry points:
 * {@code AuthService.login} ({@code POST /api/auth/login}, the SPA) and the legacy
 * {@code POST /authenticate} filter (via {@link LoginAttemptsService}). Before this class the
 * legacy path counted failures in per-instance Guava caches, so behind N instances an attacker got
 * N times the budget and a restart reset it.
 *
 * <p>The key is SHA-256(lower-cased login | canonical IP), so an attacker on another IP cannot lock
 * the real user out of their own IP (account-DoS prevention). Both entry points use the SAME key,
 * so failures on one count against the other: there is no doubled budget for trying both.
 */
@Service
@RequiredArgsConstructor
public class LoginRateLimiter {

    private final LoginAttemptRepository loginAttemptRepository;
    private final ConfigService configService;

    /** A (login, ip) pair that has used up its failed-attempt budget for the current window. */
    public record Limited(long recentAttempts, long retryAfterSeconds) {}

    /**
     * @return the limit state when this (login, ip) pair has used up its failed-attempt budget for
     *         the current window, or empty when an attempt is allowed
     */
    @Transactional(readOnly = true)
    public Optional<Limited> checkLimited(String login, String clientIp) {
        int maxAttempts = maxAttempts();
        int lockWindowMin = lockWindowMinutes();
        String identifier = identifier(login, clientIp);
        Instant now = Instant.now(ClockProvider.getClock());
        long recentAttempts = loginAttemptRepository
            .countByIdentifierAndAttemptedAtAfter(identifier, now.minus(lockWindowMin, ChronoUnit.MINUTES));
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

    /**
     * Account-wide cap (any IP) for the legacy {@code /authenticate} path only, replacing its former
     * per-user cache: without it an attacker rotating IPs gets a fresh budget per IP. Deliberately not
     * applied to {@code /api/auth/login}, where an account-wide lock would let anyone lock a real user
     * out of the SPA (the reason that path keys on login|ip).
     */
    @Transactional(readOnly = true)
    public boolean isAccountLimited(String login) {
        return countRecentFailures(login) >= maxAttempts();
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
