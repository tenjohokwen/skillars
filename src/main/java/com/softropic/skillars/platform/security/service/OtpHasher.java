package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.platform.admin.config.SkillarsPlatformProperties;

import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * skillars-deferred-162 AC2: the single owner of the phone-OTP hash stored in
 * {@code phone_otp_tokens.otp_hash}.
 * <p>
 * The hash is a keyed {@code HmacSHA256(otpKey, userId + ":" + otp)}, lowercase hex (64 chars, so it fits
 * the existing {@code varchar(64)} column). An OTP has only 900,000 values, so the previous unkeyed
 * {@code SHA-256(otp + userId)} could be brute-forced offline from a leaked row in well under a second;
 * without the key it cannot be.
 * <p>
 * {@code otpKey} is derived once from {@code registration-verification-secret} with a fixed purpose label,
 * so it never equals the key {@link RegistrationVerificationTokenService} signs phone-verification handles
 * with. Consequence: rotating that secret also invalidates every OTP in flight (at most 10 minutes' worth);
 * the user resends.
 */
@Component
public class OtpHasher {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String KEY_PURPOSE = "skillars-otp-hash-v1";

    private final byte[] otpKey;

    public OtpHasher(SkillarsPlatformProperties properties) {
        String secret = properties.getRegistrationVerificationSecret();
        Assert.hasText(secret,
            "skillars.platform.registration-verification-secret must be set "
                + "(env PLATFORM_REGISTRATION_VERIFICATION_SECRET) — phone OTPs cannot be hashed without it");
        this.otpKey = hmac(secret.getBytes(StandardCharsets.UTF_8), KEY_PURPOSE);
    }

    /** @return the 64-char lowercase hex hash to store for {@code otp} issued to {@code userId}. */
    public String hash(long userId, String otp) {
        return HexFormat.of().formatHex(hmac(otpKey, userId + ":" + otp));
    }

    /** Constant-time check of a presented {@code otp} against the stored {@code expectedHash}. */
    public boolean matches(String expectedHash, long userId, String otp) {
        if (expectedHash == null || otp == null) {
            return false;
        }
        return MessageDigest.isEqual(
            expectedHash.getBytes(StandardCharsets.US_ASCII),
            hash(userId, otp).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(ALGORITHM + " unavailable", e);
        }
    }
}
