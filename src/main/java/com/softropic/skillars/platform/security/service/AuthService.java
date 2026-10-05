package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.infrastructure.security.CookieUtil;
import com.softropic.skillars.infrastructure.util.ClockProvider;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.security.contract.LoginResponse;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.contract.SkillarsVerificationStatus;
import com.softropic.skillars.platform.security.contract.exception.LoginRateLimitedException;
import com.softropic.skillars.platform.security.contract.exception.SkillarsAccountNotVerifiedException;
import com.softropic.skillars.platform.security.repo.LoginAttempt;
import com.softropic.skillars.platform.security.repo.LoginAttemptRepository;
import com.softropic.skillars.platform.security.repo.RefreshToken;
import com.softropic.skillars.platform.security.repo.RefreshTokenRepository;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static com.softropic.skillars.infrastructure.security.SecurityConstants.REFRESH_TOKEN_COOKIE;
import static com.softropic.skillars.infrastructure.security.SecurityConstants.REFRESH_TOKEN_TTL;
import static com.softropic.skillars.infrastructure.security.SecurityConstants.SKILLARS_PROFILE_COOKIE;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AuthService {

    // A recently-rotated token presented within this window is treated as a legitimate
    // multi-tab refresh race, not a theft event — the caller is redirected to the successor.
    private static final Duration REFRESH_GRACE_WINDOW = Duration.ofSeconds(30);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenRepository refreshTokenRepository;
    private final LoginAttemptRepository loginAttemptRepository;
    private final ConfigService configService;
    private final LoginTokenManager loginTokenManager;
    private final SecurityUtil securityUtil;

    public LoginResponse login(String email, String rawPassword, String clientIp, HttpServletResponse res) {
        int maxAttempts = configService.find("security.login.max-attempts")
            .map(Integer::parseInt).orElse(5);
        int lockWindowMin = configService.find("security.login.lock-window-minutes")
            .map(Integer::parseInt).orElse(15);

        // Rate-limit key is SHA-256(email|ip) so an attacker on a different IP cannot
        // lock the legitimate user's own IP+email combination (account-DoS prevention).
        String identifier = sha256Hex(email.toLowerCase() + "|" + canonicaliseIp(clientIp));
        Instant windowStart = Instant.now(ClockProvider.getClock()).minus(lockWindowMin, ChronoUnit.MINUTES);
        long recentAttempts = loginAttemptRepository.countByIdentifierAndAttemptedAtAfter(identifier, windowStart);
        if (recentAttempts >= maxAttempts) {
            long retryAfterSeconds = loginAttemptRepository
                .findFirstByIdentifierOrderByAttemptedAtAsc(identifier)
                .map(a -> Math.max(1L, ChronoUnit.SECONDS.between(
                        Instant.now(ClockProvider.getClock()),
                        a.getAttemptedAt().plus(lockWindowMin, ChronoUnit.MINUTES))))
                .orElse((long) lockWindowMin * 60);
            throw new LoginRateLimitedException(
                "Too many failed login attempts",
                Map.of("attempts", recentAttempts),
                retryAfterSeconds);
        }

        var user = userRepository.findOneByLogin(email.toLowerCase()).orElseThrow(() -> {
            recordAttempt(identifier);
            return new BadCredentialsException("Invalid credentials");
        });

        if (!passwordEncoder.matches(rawPassword, user.getPassword())) {
            recordAttempt(identifier);
            throw new BadCredentialsException("Invalid credentials");
        }

        ensureAccountIsLive(user);

        boolean phoneOtpRequired = configService.getBoolean("security.registration.phone-otp-required", true);
        if (user.getSkillarsRole() != null && phoneOtpRequired &&
            user.getVerificationStatus() != SkillarsVerificationStatus.BASIC_VERIFIED) {
            throw new SkillarsAccountNotVerifiedException();
        }

        Principal principal = Principal.instanceFrom(user);

        // Persist the refresh token before writing any response cookies — ensures DB state
        // is committed (or rolled back) before the client receives auth credentials.
        String rawToken = UUID.randomUUID().toString();
        RefreshToken rt = new RefreshToken();
        rt.setUserId(user.getId());
        rt.setTokenHash(sha256Hex(rawToken));
        rt.setExpiresAt(Instant.now(ClockProvider.getClock()).plus(REFRESH_TOKEN_TTL));
        rt.setUsed(false);
        refreshTokenRepository.save(rt);

        loginTokenManager.createLoginToken(res, principal);
        CookieUtil.addCookie(res, REFRESH_TOKEN_COOKIE, rawToken,
            true, (int) REFRESH_TOKEN_TTL.toSeconds(), "Lax");

        String role = user.getSkillarsRole() != null ? user.getSkillarsRole().name() : "ADMIN";
        // `id` is quoted deliberately — CommonConfig.longToStringModule() applies this same
        // string-encoding to every Jackson-serialized Long response body for exactly this reason
        // (JS cannot represent a Tsid-sized long losslessly), but this cookie is hand-built JSON,
        // outside that pipeline. Found manually testing (2026-10-01): with `id` bare, the frontend's
        // own hydrateFromCookie() (auth.store.js) — which every page load/refresh calls — silently
        // corrupted authStore.userId via IEEE-754 double rounding, long before any upload was
        // attempted. CoachProfileBuilderPlaceholderPage.vue's photo-upload step sends that value
        // straight back as signUpload's entityId, which StorageResource rejects with a 403 the
        // instant it no longer matches the JWT's own (uncorrupted) business ID.
        String json = "{\"id\":\"" + user.getId() + "\",\"role\":\"" + role + "\"}";
        String skpValue = URLEncoder.encode(json, StandardCharsets.UTF_8);
        CookieUtil.addCookie(res, SKILLARS_PROFILE_COOKIE, skpValue,
            false, (int) REFRESH_TOKEN_TTL.toSeconds(), "Lax");

        return new LoginResponse(user.getId(), role, principal.getDisplayName());
    }

    public LoginResponse refresh(HttpServletRequest req, HttpServletResponse res) {
        String rawToken = CookieUtil.getCookieValue(req, REFRESH_TOKEN_COOKIE);
        if (rawToken == null || rawToken.isBlank()) {
            throw new BadCredentialsException("Missing refresh token");
        }

        String hash = sha256Hex(rawToken);
        RefreshToken token = refreshTokenRepository.findByTokenHash(hash)
            .orElseThrow(() -> new BadCredentialsException("Invalid refresh token"));

        if (token.isUsed()) {
            // Capture before any reassignment — required for lambda capture below.
            final Long ownerId = token.getUserId();
            // If the token was rotated recently it is likely a multi-tab race: Tab A refreshed
            // and Tab B still holds the old cookie. Redirect Tab B to use the successor token
            // rather than treating the event as theft and revoking all sessions.
            if (token.getRotatedAt() != null &&
                    Instant.now(ClockProvider.getClock()).isBefore(token.getRotatedAt().plus(REFRESH_GRACE_WINDOW))) {
                token = refreshTokenRepository
                    .findFirstByUserIdAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
                            ownerId, Instant.now(ClockProvider.getClock()))
                    .orElseGet(() -> {
                        refreshTokenRepository.markAllUsedByUserId(ownerId);
                        securityUtil.terminateSession(req, res);
                        throw new BadCredentialsException("Token reuse detected — all sessions revoked");
                    });
            } else {
                refreshTokenRepository.markAllUsedByUserId(ownerId);
                securityUtil.terminateSession(req, res);
                throw new BadCredentialsException("Token reuse detected — all sessions revoked");
            }
        }

        if (token.getExpiresAt().isBefore(Instant.now(ClockProvider.getClock()))) {
            securityUtil.terminateSession(req, res);
            throw new BadCredentialsException("Refresh token has expired");
        }

        // skillars-deferred-143 AC2: the account is resolved and its liveness checked BEFORE the
        // presented token is rotated below — deliberately earlier than the story sketched it.
        //
        // The story placed both after the saveAndFlush, which self-deadlocks: that flush issues
        // `UPDATE refresh_tokens ... WHERE token_hash = ?` inside THIS (outermost) transaction and
        // holds the row lock until it ends, while the rejection teardown's revocation runs in a
        // REQUIRES_NEW transaction that must update the very same row. The inner transaction waits
        // on a lock only the outer one can release, and the outer one is waiting on the inner —
        // measured empirically as `ERROR: canceling statement due to lock timeout` (SQLState
        // 55P03) surfacing as a 409 instead of the intended 401.
        //
        // Checking first removes the hazard at the source rather than working around it: on the
        // rejection path this transaction has issued no write to refresh_tokens at all, so the
        // REQUIRES_NEW revocation takes an uncontended lock and commits. It is also better
        // behaviour independently — a locked account's refresh attempt no longer consumes and
        // rotates a token before being turned away.
        // Code review [Patch] 2026-10-05: revoke the token this request actually RESOLVED to, not
        // only the raw cookie value `terminateSession` derives its target from. The grace-window
        // branch above can have reassigned `token` to a live SUCCESSOR row (:158-165) while the
        // cookie still carries the stale, already-used original — in that case the successor is
        // the account's one live credential, and revoking only the cookie's row would leave it
        // standing after the very denial meant to kill it. In the ordinary (non-reassigned) case
        // this is the same row `terminateSession` targets, and the write is idempotent because
        // `used` is monotonic, so the cost is one redundant UPDATE on a rejection-only path.
        //
        // Not needed on the expiry branch above: the successor query filters on
        // `ExpiresAtAfter(now)`, so a reassigned `token` can never be the expired one, and an
        // unreassigned `token` hashes to exactly the cookie value.
        //
        // Deadlock-safe for the same reason the liveness check moved ahead of the rotation write
        // (see below): on every path reaching here this transaction has issued no write against
        // refresh_tokens, so the REQUIRES_NEW revocation takes an uncontended lock.
        final String resolvedTokenHash = token.getTokenHash();

        var user = userRepository.findById(token.getUserId()).orElseThrow(() -> {
            refreshTokenRepository.markUsedByTokenHash(resolvedTokenHash);
            securityUtil.terminateSession(req, res);
            return new BadCredentialsException("User not found for refresh token");
        });

        try {
            ensureAccountIsLive(user);
        } catch (DisabledException | LockedException e) {
            // Rolls back this method's transaction, which is exactly why the revocation inside
            // terminateSession (and the explicit one here) is REQUIRES_NEW — see
            // RefreshTokenRepository.markUsedByTokenHash.
            refreshTokenRepository.markUsedByTokenHash(resolvedTokenHash);
            securityUtil.terminateSession(req, res);
            throw e;
        }

        token.setUsed(true);
        token.setRotatedAt(Instant.now(ClockProvider.getClock()));
        try {
            refreshTokenRepository.saveAndFlush(token);
        } catch (ObjectOptimisticLockingFailureException ex) {
            // True concurrent refresh (two requests in flight simultaneously): the loser gets a
            // clean 401 rather than the default 409, so the client re-enters the login flow.
            //
            // Cookies only, deliberately: this is the one teardown that runs AFTER this
            // transaction has already issued its UPDATE against the token row, so a REQUIRES_NEW
            // revocation here would hit the same self-deadlock described above. It is also
            // unnecessary — losing the optimistic-lock race means the winning request has already
            // committed `used = true` on this exact row.
            securityUtil.clearAuthCookies(res);
            throw new BadCredentialsException("Concurrent refresh detected — please sign in again");
        }

        Principal principal = Principal.instanceFrom(user);

        String newRaw = UUID.randomUUID().toString();
        RefreshToken newRt = new RefreshToken();
        newRt.setUserId(user.getId());
        newRt.setTokenHash(sha256Hex(newRaw));
        newRt.setExpiresAt(Instant.now(ClockProvider.getClock()).plus(REFRESH_TOKEN_TTL));
        newRt.setUsed(false);
        refreshTokenRepository.save(newRt);

        loginTokenManager.createLoginToken(res, principal);
        CookieUtil.addCookie(res, REFRESH_TOKEN_COOKIE, newRaw,
            true, (int) REFRESH_TOKEN_TTL.toSeconds(), "Lax");

        String role = user.getSkillarsRole() != null ? user.getSkillarsRole().name() : "ADMIN";
        // `id` is quoted deliberately — CommonConfig.longToStringModule() applies this same
        // string-encoding to every Jackson-serialized Long response body for exactly this reason
        // (JS cannot represent a Tsid-sized long losslessly), but this cookie is hand-built JSON,
        // outside that pipeline. Found manually testing (2026-10-01): with `id` bare, the frontend's
        // own hydrateFromCookie() (auth.store.js) — which every page load/refresh calls — silently
        // corrupted authStore.userId via IEEE-754 double rounding, long before any upload was
        // attempted. CoachProfileBuilderPlaceholderPage.vue's photo-upload step sends that value
        // straight back as signUpload's entityId, which StorageResource rejects with a 403 the
        // instant it no longer matches the JWT's own (uncorrupted) business ID.
        String json = "{\"id\":\"" + user.getId() + "\",\"role\":\"" + role + "\"}";
        String skpValue = URLEncoder.encode(json, StandardCharsets.UTF_8);
        CookieUtil.addCookie(res, SKILLARS_PROFILE_COOKIE, skpValue,
            false, (int) REFRESH_TOKEN_TTL.toSeconds(), "Lax");

        return new LoginResponse(user.getId(), role, principal.getDisplayName());
    }

    public void logout(HttpServletRequest req, HttpServletResponse res) {
        securityUtil.terminateSession(req, res);
    }

    /**
     * skillars-deferred-143 AC1/AC2: the single account-liveness gate for both hand-rolled
     * authentication entry points. {@link #login} previously checked only {@code isActivated()} and
     * {@link #refresh} checked nothing at all, so {@code UserAdminService.lockUserAccount()} — which
     * sets {@code locked = true} without deactivating — was a silent no-op against this login path.
     *
     * <p>Mirrors the existing reject-a-locked-user shape used by the registration services (e.g.
     * {@code CoachRegistrationService}), and needs no new error-handling code: {@code ApiAdvice}
     * already maps {@link DisabledException} to a 401 {@code security.accNotEnabled} and
     * {@link LockedException} to a 401 {@code security.accLocked}, both already translated in all
     * three locales.
     *
     * <p>Deactivation is checked first so a GDPR-erased account (which
     * {@code GdprErasureService.eraseTransactional} leaves {@code activated = false} AND
     * {@code locked = true}) keeps reporting the key it reports today.
     */
    private void ensureAccountIsLive(User user) {
        if (!user.isActivated()) {
            throw new DisabledException("Account is not activated");
        }
        if (user.isLocked()) {
            throw new LockedException("Account is locked");
        }
    }

    private void recordAttempt(String identifier) {
        loginAttemptRepository.save(
            new LoginAttempt(identifier, Instant.now(ClockProvider.getClock())));
    }

    private static String canonicaliseIp(String ip) {
        if (ip == null) return "unknown";
        if ("0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) return "127.0.0.1";
        if (ip.startsWith("::ffff:")) return ip.substring(7);
        return ip;
    }

    private static String sha256Hex(String raw) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                                       .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
