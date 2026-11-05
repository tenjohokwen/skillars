package com.softropic.skillars.platform.security.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Confirm and undo tokens of an email change (skillars-deferred-172): 256 bits from a {@link SecureRandom},
 * URL-safe Base64 without padding (43 characters). Only {@link #hash} of a token is stored, as for refresh
 * tokens, so a database read alone cannot be turned into a working link.
 */
final class EmailChangeTokens {

    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private EmailChangeTokens() {
    }

    static String generate() {
        final byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(final String rawToken) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256")
                                               .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
