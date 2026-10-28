package com.softropic.skillars.platform.notification.service;

import com.softropic.skillars.platform.admin.config.SkillarsPlatformProperties;
import com.softropic.skillars.platform.notification.contract.NotificationCategory;

import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs and verifies one-click email unsubscribe tokens (notification centre, UI-gap chunk C9).
 *
 * <p>Format: {@code v1.<base64url(userId ":" CATEGORY)>.<base64url(HmacSHA256(key, "v1." payload))>}.
 * The token is the only credential on the unauthenticated unsubscribe endpoint, so it must be
 * unforgeable for an arbitrary user id or category — a keyed MAC over both, checked in constant time.
 *
 * <p><strong>Key.</strong> No new secret configuration: derived from the existing
 * {@code skillars.platform.registration-verification-secret} with a fixed purpose label, exactly as
 * {@code OtpHasher} (skillars-deferred-162) derives its OTP key, so the derived key never equals the
 * one {@code RegistrationVerificationTokenService} or {@code OtpHasher} uses. Rotating that secret
 * invalidates every unsubscribe link already sent — acceptable: the user can still opt out from the
 * Profile page, and links in old emails simply stop working.
 *
 * <p><strong>No expiry, deliberately.</strong> RFC 8058 one-click links live in mailboxes for months;
 * an unsubscribe link that silently stops working is exactly the experience the header exists to
 * prevent, and the worst a replayed token can do is re-apply the same opt-out to the same user.
 */
@Component
public class UnsubscribeTokenService {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String KEY_PURPOSE = "skillars-email-unsubscribe-v1";
    private static final String VERSION = "v1";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final int MAX_TOKEN_LENGTH = 512;

    private final byte[] key;

    public UnsubscribeTokenService(final SkillarsPlatformProperties properties) {
        final String secret = properties.getRegistrationVerificationSecret();
        Assert.hasText(secret,
            "skillars.platform.registration-verification-secret must be set "
                + "(env PLATFORM_REGISTRATION_VERIFICATION_SECRET) — unsubscribe links cannot be signed without it");
        this.key = hmac(secret.getBytes(StandardCharsets.UTF_8), KEY_PURPOSE);
    }

    /** Claims carried by a verified token. */
    public record Claims(long userId, NotificationCategory category) {
    }

    public String mint(final long userId, final NotificationCategory category) {
        Assert.isTrue(category.optOutable(), "only opt-out-able categories get an unsubscribe token");
        final String payload = B64.encodeToString((userId + ":" + category.name()).getBytes(StandardCharsets.UTF_8));
        final String signed = VERSION + "." + payload;
        return signed + "." + B64.encodeToString(hmac(key, signed));
    }

    /**
     * @return the claims of a well-formed, correctly signed token naming an opt-out-able category;
     *     empty for anything else (malformed, tampered, wrong version, non-opt-out-able category)
     */
    public Optional<Claims> verify(final String token) {
        if (token == null || token.isEmpty() || token.length() > MAX_TOKEN_LENGTH) {
            return Optional.empty();
        }
        final String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            return Optional.empty();
        }
        final byte[] presentedMac;
        final String payload;
        try {
            presentedMac = B64D.decode(parts[2]);
            payload = new String(B64D.decode(parts[1]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        final byte[] expectedMac = hmac(key, parts[0] + "." + parts[1]);
        if (!MessageDigest.isEqual(expectedMac, presentedMac)) {
            return Optional.empty();
        }
        final int sep = payload.indexOf(':');
        if (sep <= 0) {
            return Optional.empty();
        }
        try {
            final long userId = Long.parseLong(payload.substring(0, sep));
            final NotificationCategory category = NotificationCategory.valueOf(payload.substring(sep + 1));
            return category.optOutable() ? Optional.of(new Claims(userId, category)) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static byte[] hmac(final byte[] key, final String data) {
        try {
            final Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(ALGORITHM + " unavailable", e);
        }
    }
}
