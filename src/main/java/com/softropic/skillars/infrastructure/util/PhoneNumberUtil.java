package com.softropic.skillars.infrastructure.util;

import com.softropic.skillars.infrastructure.validation.PhoneNumberDto;
import com.softropic.skillars.infrastructure.validation.CamMobileValidator;
import com.softropic.skillars.infrastructure.validation.PhoneNumber;

import java.util.Objects;

/**
 * Utility class for phone number validation and normalization.
 */
public final class PhoneNumberUtil {

    private PhoneNumberUtil() {
        // Prevent instantiation
    }

    /**
     * Converts a raw phone string into a {@link PhoneNumber} entity,
     * enriching it with provider, country code, and phone type via {@link CamMobileValidator}.
     *
     * @param phone the raw phone string
     * @return a populated {@link PhoneNumber}, or {@code null} if the input is blank
     */
    public static PhoneNumber fromString(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        PhoneNumberDto phoneNoDto = CamMobileValidator.validate(phone);
        PhoneNumber phoneNumber = new PhoneNumber();
        phoneNumber.setPhone(phoneNoDto.getPhone());
        phoneNumber.setIso2Country(phoneNoDto.getIso2Country());
        phoneNumber.setPhoneType(Objects.equals(phoneNoDto.getPhoneType(), PhoneNumberDto.PhoneType.MOBILE)
                ? PhoneNumber.PhoneType.MOBILE : PhoneNumber.PhoneType.FIXED);
        phoneNumber.setProvider(phoneNoDto.getProvider());
        return phoneNumber;
    }

    /**
     * Normalizes a phone number by removing all non-numeric characters.
     * This includes spaces, dashes, parentheses, and the + prefix.
     *
     * @param phoneNumber the phone number to normalize
     * @return the normalized phone number containing only digits, or empty string if input is null
     *
     * @example
     * "+237 6 77 89 12 34" -> "237677891234"
     * "(123) 456-7890" -> "1234567890"
     */
    public static String normalize(String phoneNumber) {
        if (phoneNumber == null) {
            return "";
        }
        return phoneNumber.replaceAll("[^0-9]", "");
    }

    /**
     * Validates if a phone number meets length requirements after normalization.
     *
     * @param phoneNumber the phone number to validate
     * @param minLength minimum allowed length (inclusive)
     * @param maxLength maximum allowed length (inclusive)
     * @return true if the normalized phone number length is within the specified range
     *
     * @example
     * isValidLength("237677891234", 9, 15) -> true (length is 12)
     * isValidLength("123", 9, 15) -> false (length is 3, below minimum)
     */
    public static boolean isValidLength(String phoneNumber, int minLength, int maxLength) {
        if (phoneNumber == null) {
            return false;
        }
        String normalized = normalize(phoneNumber);
        int length = normalized.length();
        return length >= minLength && length <= maxLength;
    }

    /**
     * Validates if a phone number starts with specific country/operator prefixes.
     *
     * @param phoneNumber the phone number to validate
     * @param validPrefixes array of valid prefixes (e.g., "237", "244")
     * @return true if the normalized phone number starts with any of the valid prefixes
     *
     * @example
     * startsWithPrefix("+237 677 89 12 34", "237", "244") -> true
     * startsWithPrefix("677 89 12 34", "237", "244") -> false
     */
    public static boolean startsWithPrefix(String phoneNumber, String... validPrefixes) {
        if (phoneNumber == null || validPrefixes == null || validPrefixes.length == 0) {
            return false;
        }
        String normalized = normalize(phoneNumber);
        for (String prefix : validPrefixes) {
            if (normalized.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Comprehensive validation combining length and prefix checks.
     *
     * @param phoneNumber the phone number to validate
     * @param minLength minimum allowed length
     * @param maxLength maximum allowed length
     * @param validPrefixes optional valid prefixes (if empty, only length is checked)
     * @return true if phone number meets all specified criteria
     */
    public static boolean isValid(String phoneNumber, int minLength, int maxLength, String... validPrefixes) {
        if (!isValidLength(phoneNumber, minLength, maxLength)) {
            return false;
        }
        if (validPrefixes == null || validPrefixes.length == 0) {
            return true; // No prefix requirement
        }
        return startsWithPrefix(phoneNumber, validPrefixes);
    }
}
