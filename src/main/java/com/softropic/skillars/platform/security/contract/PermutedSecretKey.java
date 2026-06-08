package com.softropic.skillars.platform.security.contract;



import com.softropic.skillars.platform.security.contract.exception.SecError;
import com.softropic.skillars.platform.security.contract.exception.SecException;
import com.softropic.skillars.platform.security.contract.util.Cryptopher;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The purpose of this class is just to ensure that secrets are never in plain text at rest.
 * An instance of this class is used to hold a secret instead of directly holding the secret.
 * The secret is stored in a permuted form.
 * Note that the secret/password is called key in this class
 */
public final class PermutedSecretKey {
    private static final List<Integer> KEY_ORDER = List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
    private static final int KEY_LENGTH = KEY_ORDER.size();
    public static final String ACTION = "Action";

    private final String permKey;
    private final String     seq;
    private final Cryptopher cryptopher;


    /**
     * This constructor is private because the factory method has to first create a sequence that is used permute the given key (AKA secret)
     * The sequence (seq) is also used as the key (AKA secret) to eventually encrypt the permKey so that you end up with an encrypted permuted key
     * @param permKey is the key (AKA secret) that is permuted at rest
     * @param seq is used to unpermute the permKey to get the key or encrypt the permKey to get an encrypted permkey in the db
     */
    private PermutedSecretKey(String permKey, String seq) {
        this.permKey = permKey;
        this.seq = seq;
        //seq is used to encrypt the permuted key
        cryptopher = new Cryptopher(seq);
    }

    public static PermutedSecretKey instanceFromPlainKey(String key) {
        validateKey(key, Map.of(ACTION, "instanceFromPlainKey"));
        final String sequence = generateSequence();
        final String permutedKey = permute(key, sequence);
        return new PermutedSecretKey(permutedKey, sequence);
    }

    public static PermutedSecretKey instanceFromEncryptedKey(String encryptedKey, String sequence) {
        validateEncryptedKey(encryptedKey, Map.of(ACTION, "instanceFromEncryptedKey"));
        validateSequence(sequence, Map.of(ACTION, "instanceFromEncryptedKey"));
        final String permKey = decryptPermKey(encryptedKey, sequence);
        return new PermutedSecretKey(permKey, sequence);
    }

    public static PermutedSecretKey instanceFromPermutedKey(String permutedKey, String sequence) {
        validateKey(permutedKey, Map.of(ACTION, "instanceFromPermutedKey"));
        validateSequence(sequence, Map.of(ACTION, "instanceFromPermutedKey"));
        return new PermutedSecretKey(permutedKey, sequence);
    }

    public String getEncryptedPermKey() {
        return cryptopher.encrypt(this.permKey);
    }

    public String getPlainKey() {
        return reversePermute();
    }

    public String getSequence() {
        return seq;
    }

    public String getPermKey() {
        return permKey;
    }

    private static String generateSequence() {
        final List<Integer> actualOrder = new ArrayList<>(KEY_ORDER);
        Collections.shuffle(actualOrder);
        return actualOrder.stream().map(Object::toString).collect(Collectors.joining(""));
    }

    private static String decryptPermKey(String encryptedPermKey, String seq) {
        return new Cryptopher(seq).decrypt(encryptedPermKey);
    }

    /**
     * Change order of key. The order  is determined by seq
     * @param key the string to be permuted
     * @param seq order in which the new string should be
     * @return
     */
    private static String permute(String key, String seq) {
        return seq.chars()
                  .map(Character::getNumericValue)
                  .boxed()
                  .map(key::charAt)//get character at a particular position in the string key
                  .map(Object::toString)
                  .reduce((partialString, element) -> partialString + element).orElse(null);
    }

    /**
     * Restore string to original sequence before it was permuted
     * @return
     */
    private String reversePermute() {
        final Character[] chars = new Character[KEY_LENGTH];
        for (int i = 0; i < KEY_LENGTH; i++) {
            final int pos = Integer.parseInt(String.valueOf(seq.charAt(i)));
            chars[pos] = permKey.charAt(i);
        }
        return Arrays.stream(chars).map(Object::toString).reduce((cum, xter) -> cum + xter).orElse(null);
    }

    private static void validateKey(String key, Map<String, Object> context) {
        if(StringUtils.isBlank(key)) {
            throw new SecException("secret key cannot be blank", context, SecError.BLANK_KEY);
        }
        if(key.length() != KEY_LENGTH) {
            throw new SecException("Key length should be 10", context, SecError.ILLEGAL_KEY_LENGTH);
        }
    }

    private static void validateSequence(String key, Map<String, Object> context) {
        if(StringUtils.isBlank(key)) {
            throw new SecException("sequence cannot be blank", context, SecError.BLANK_SEQUENCE);
        }
        if(key.length() != KEY_LENGTH) {
            throw new SecException("sequence  length should be 10", context, SecError.ILLEGAL_SEQUENCE_LENGTH);
        }
    }

    private static void validateEncryptedKey(String key, Map<String, Object> context) {
        if(StringUtils.isBlank(key)) {
            throw new SecException("Encrypted key cannot be blank", context, SecError.BLANK_KEY);
        }
    }

}
