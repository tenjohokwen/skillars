package com.softropic.skillars.infrastructure.util;

import org.apache.commons.lang3.RandomStringUtils;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Utility class for generating random Strings.
 */
public final class RandomUtil {

    private static final int DEF_COUNT = 10;

    /** 128 bits of entropy for single-use account keys; 16 bytes encode to 22 URL-safe characters. */
    private static final int KEY_BYTES = 16;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private RandomUtil() {
    }

    /**
     * Generates a password.
     *
     * @return the generated password
     */
    public static String generatePassword() {
        return RandomStringUtils.randomAlphanumeric(DEF_COUNT);
    }

    /**
     * Generates an activation key: 128 bits from a {@link SecureRandom}, URL-safe Base64 without
     * padding (22 characters, fits the {@code @Size(max = 25)} on the reset-finish DTO and the
     * {@code text} columns). Keys issued before this change were 10 random digits; they are still
     * looked up by exact match, so they stay valid until they expire.
     *
     * @return the generated activation key
     */
    public static String generateActivationKey() {
        return generateSecureKey();
    }

    /**
    * Generates a reset key.
    *
    * @return the generated reset key
    */
    public static String generateResetKey() {
        return generateSecureKey();
    }

    private static String generateSecureKey() {
        byte[] bytes = new byte[KEY_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
