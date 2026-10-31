package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.platform.security.contract.IdentitySignalKind;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

/**
 * feat/security-s06-related-party: turns an email, phone number, card fingerprint or browser fingerprint into the
 * canonical string two accounts would share, then into the SHA-256 that is stored. Pure and deterministic.
 *
 * <p>The hash is a linkage key, not a secret: a low-entropy value (an email) could be brute-forced from it, so the
 * table is as sensitive as the users table and is erased with the user (GDPR).
 */
public final class IdentitySignalNormalizer {

    private static final String HASH_DOMAIN = "skillars:idsig:v1:";
    /** Country-code and trunk-prefix differences ({@code +237 6…}, {@code 06…}, {@code 6…}) all end in the same digits. */
    private static final int PHONE_SIGNIFICANT_DIGITS = 9;
    private static final int PHONE_MIN_DIGITS = 7;
    private static final int BROWSER_MIN_LENGTH = 8;
    private static final int BROWSER_MAX_LENGTH = 512;

    private IdentitySignalNormalizer() {
    }

    /**
     * Lower-cased, trimmed; the {@code +tag} of the local part dropped; for Gmail, dots dropped and
     * {@code googlemail.com} folded into {@code gmail.com}. Empty when not an address.
     */
    public static Optional<String> email(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        int at = value.lastIndexOf('@');
        if (at <= 0 || at == value.length() - 1) {
            return Optional.empty();
        }
        String local = value.substring(0, at);
        String domain = value.substring(at + 1);
        int plus = local.indexOf('+');
        if (plus >= 0) {
            local = local.substring(0, plus);
        }
        if ("googlemail.com".equals(domain)) {
            domain = "gmail.com";
        }
        if ("gmail.com".equals(domain)) {
            local = local.replace(".", "");
        }
        return local.isEmpty() ? Optional.empty() : Optional.of(local + "@" + domain);
    }

    /** The last {@value #PHONE_SIGNIFICANT_DIGITS} digits; empty when fewer than {@value #PHONE_MIN_DIGITS} digits. */
    public static Optional<String> phone(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() < PHONE_MIN_DIGITS) {
            return Optional.empty();
        }
        return Optional.of(digits.length() > PHONE_SIGNIFICANT_DIGITS
            ? digits.substring(digits.length() - PHONE_SIGNIFICANT_DIGITS) : digits);
    }

    /** A card fingerprint or browser fingerprint as sent; blank / implausible values are not signals. */
    public static Optional<String> opaque(IdentitySignalKind kind, String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String value = raw.trim();
        if (kind == IdentitySignalKind.BROWSER
                && (value.length() < BROWSER_MIN_LENGTH || value.length() > BROWSER_MAX_LENGTH)) {
            return Optional.empty();
        }
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    /** SHA-256 (hex, 64 chars) of the kind-scoped canonical value. */
    public static String hash(IdentitySignalKind kind, String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((HASH_DOMAIN + kind.name() + ":" + canonical).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
