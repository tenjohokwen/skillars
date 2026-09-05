package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.platform.admin.config.SkillarsPlatformProperties;
import com.softropic.skillars.platform.security.contract.exception.RegistrationVerificationTokenException;

import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * skillars-deferred-93 AC8 — issues and verifies the opaque phone-verification handle that replaces
 * the client-set {@code userId} query param on the {@code permitAll} {@code /verify-phone} and
 * {@code /resend-otp} endpoints.
 *
 * <p>The old flow returned the raw {@code userId} in the {@code /verify-email} response body; the SPA
 * then carried it in the {@code /verify-phone} route and POSTed it to {@code /resend-otp}. Anyone
 * could walk {@code userId} 1..N against {@code /resend-otp} and probe which accounts were
 * mid-registration. This handle is an HMAC-SHA256 MAC over {@code {role, userId, expiry}} keyed by a
 * server-only secret, so it cannot be forged for an arbitrary id, and is bound to a single role.
 *
 * <p>Format: {@code base64url(payload) + "." + base64url(hmacSha256(base64url(payload)))} where
 * {@code payload = "1:" + role + ":" + userId + ":" + expiryEpochSeconds} — the same encode-then-sign shape as a
 * JWS, without pulling in a JWT dependency for a three-field body.
 *
 * <p>Every verification failure — malformed, bad signature, expired, wrong version, or role mismatch —
 * raises the same {@link RegistrationVerificationTokenException} (a {@code 400}, uniform message), so it is never an
 * oracle and never a raw {@code 500}.
 */
@Service
public class RegistrationVerificationTokenService {

    /** Bumped if the payload layout ever changes; an old version fails closed. */
    private static final String VERSION = "1";

    /**
     * Matches the 24h life of the email-verification token it is handed off from. Long enough that a
     * user who verified their email can still complete phone verification (and use their
     * rate-limited OTP resends) after a distraction, without minting a fresh handle. TTL only bounds
     * replay of an already-captured handle — the enumeration surface is closed by the signature
     * regardless of TTL, and both downstream endpoints are independently rate-limited per userId.
     */
    private static final Duration TOKEN_TTL = Duration.ofHours(24);

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64_DEC = Base64.getUrlDecoder();

    private final byte[] secretKey;

    public RegistrationVerificationTokenService(SkillarsPlatformProperties properties) {
        String secret = properties.getRegistrationVerificationSecret();
        Assert.hasText(secret,
            "skillars.platform.registration-verification-secret must be set "
                + "(env PLATFORM_REGISTRATION_VERIFICATION_SECRET) — phone-verification handles cannot be signed without it");
        Assert.isTrue(secret.length() >= 32,
            "skillars.platform.registration-verification-secret must be at least 32 characters "
                + "(generate with: openssl rand -base64 32)");
        this.secretKey = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** @return an opaque handle the SPA carries through the phone-verification flow for {@code userId}. */
    public String issuePhoneVerificationToken(long userId, String role) {
        long expiry = Instant.now().plus(TOKEN_TTL).getEpochSecond();
        String payload = VERSION + ":" + role + ":" + userId + ":" + expiry;
        String encodedPayload = B64.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encodedPayload + "." + B64.encodeToString(hmac(encodedPayload));
    }

    /**
     * @return the {@code userId} the handle was issued for
     * @throws RegistrationVerificationTokenException if the handle is malformed, unsigned by this
     *     server, expired, of an unknown version, or the role does not match the expected role
     */
    public long resolveUserId(String token, String expectedRole) {
        if (token == null || token.isBlank()) {
            throw new RegistrationVerificationTokenException();
        }
        int dot = token.indexOf('.');
        if (dot < 1 || dot != token.lastIndexOf('.') || dot == token.length() - 1) {
            throw new RegistrationVerificationTokenException();
        }
        String encodedPayload = token.substring(0, dot);
        byte[] presentedSig;
        byte[] payloadBytes;
        try {
            presentedSig = B64_DEC.decode(token.substring(dot + 1));
            payloadBytes = B64_DEC.decode(encodedPayload);
        } catch (IllegalArgumentException e) {
            throw new RegistrationVerificationTokenException();
        }

        if (!MessageDigest.isEqual(hmac(encodedPayload), presentedSig)) {
            throw new RegistrationVerificationTokenException();
        }

        String[] parts = new String(payloadBytes, StandardCharsets.UTF_8).split(":");
        if (parts.length != 4 || !VERSION.equals(parts[0])) {
            throw new RegistrationVerificationTokenException();
        }
        String tokenRole = parts[1];
        if (!tokenRole.equals(expectedRole)) {
            throw new RegistrationVerificationTokenException();
        }
        long userId;
        long expiry;
        try {
            userId = Long.parseLong(parts[2]);
            expiry = Long.parseLong(parts[3]);
        } catch (NumberFormatException e) {
            throw new RegistrationVerificationTokenException();
        }
        if (Instant.now().getEpochSecond() > expiry) {
            throw new RegistrationVerificationTokenException();
        }
        return userId;
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
