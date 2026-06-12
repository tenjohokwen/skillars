package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.infrastructure.security.CookieUtil;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
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
import com.softropic.skillars.platform.security.repo.UserRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
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

        if (!user.isActivated()) {
            throw new DisabledException("Account is not activated");
        }

        if (user.getSkillarsRole() != null &&
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
        String json = "{\"id\":" + user.getId() + ",\"role\":\"" + role + "\"}";
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
                        clearAuthCookies(res);
                        throw new BadCredentialsException("Token reuse detected — all sessions revoked");
                    });
            } else {
                refreshTokenRepository.markAllUsedByUserId(ownerId);
                clearAuthCookies(res);
                throw new BadCredentialsException("Token reuse detected — all sessions revoked");
            }
        }

        if (token.getExpiresAt().isBefore(Instant.now(ClockProvider.getClock()))) {
            clearAuthCookies(res);
            throw new BadCredentialsException("Refresh token has expired");
        }

        token.setUsed(true);
        token.setRotatedAt(Instant.now(ClockProvider.getClock()));
        try {
            refreshTokenRepository.saveAndFlush(token);
        } catch (ObjectOptimisticLockingFailureException ex) {
            // True concurrent refresh (two requests in flight simultaneously): the loser gets a
            // clean 401 rather than the default 409, so the client re-enters the login flow.
            clearAuthCookies(res);
            throw new BadCredentialsException("Concurrent refresh detected — please sign in again");
        }

        var user = userRepository.findById(token.getUserId()).orElseThrow(() -> {
            clearAuthCookies(res);
            return new BadCredentialsException("User not found for refresh token");
        });

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
        String json = "{\"id\":" + user.getId() + ",\"role\":\"" + role + "\"}";
        String skpValue = URLEncoder.encode(json, StandardCharsets.UTF_8);
        CookieUtil.addCookie(res, SKILLARS_PROFILE_COOKIE, skpValue,
            false, (int) REFRESH_TOKEN_TTL.toSeconds(), "Lax");

        return new LoginResponse(user.getId(), role, principal.getDisplayName());
    }

    public void logout(HttpServletRequest req, HttpServletResponse res) {
        String rawToken = CookieUtil.getCookieValue(req, REFRESH_TOKEN_COOKIE);
        if (rawToken != null) {
            String hash = sha256Hex(rawToken);
            refreshTokenRepository.findByTokenHash(hash)
                .filter(t -> !t.isUsed())
                .ifPresent(t -> {
                    t.setUsed(true);
                    refreshTokenRepository.save(t);
                });
        }
        loginTokenManager.deleteLoginToken(res);
        CookieUtil.removeCookie(REFRESH_TOKEN_COOKIE, res, true, "Lax");
        CookieUtil.removeCookie(SKILLARS_PROFILE_COOKIE, res, false, "Lax");
    }

    private void clearAuthCookies(HttpServletResponse res) {
        loginTokenManager.deleteLoginToken(res);
        CookieUtil.removeCookie(REFRESH_TOKEN_COOKIE, res, true, "Lax");
        CookieUtil.removeCookie(SKILLARS_PROFILE_COOKIE, res, false, "Lax");
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
