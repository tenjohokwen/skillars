package com.softropic.skillars.utils;

import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * Utility class for generating encryption keys.
 * This should be used only for development/testing purposes.
 * In production, keys should be managed by a secure vault service.
 */
public class KeyGen {

    /**
     * Generates a new AES-256 encryption key and prints it in Base64 format.
     */
    /**public static void main(String[] args) throws Exception {
        // Generate a new AES-256 key
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256, new SecureRandom());
        SecretKey secretKey = keyGen.generateKey();

        // Convert to Base64 for storage/configuration
        String encodedKey = Base64.getEncoder().encodeToString(secretKey.getEncoded());

        System.out.println("Generated AES-256 key (Base64):");
        System.out.println(encodedKey);
        System.out.println("\nStore this securely and use it as the value for app.encryption.master-key");
    }**/
}