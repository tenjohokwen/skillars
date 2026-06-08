package com.softropic.skillars.infrastructure.validation;


import com.softropic.skillars.infrastructure.validation.PhoneNumberDto;
import com.softropic.skillars.infrastructure.exception.ApplicationException;
import com.softropic.skillars.infrastructure.exception.ErrorCode;

import org.apache.commons.lang3.StringUtils;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Validator class for Cameroon mobile numbers
 * Validates mobile numbers for MTN, Orange, and NextTel operators
 * General Validation Rules:
 *
 * Must be exactly 9 digits after country code removal
 * Must start with digit 6
 * No spaces or special characters allowed
 *
 *
 * Operator-Specific Validation:
 *
 * MTN: Numbers starting with 68, 67, or 65(0-4)
 * Orange: Numbers starting with 69 or 65(5-9)
 * NextTel: Numbers starting with 66
 */
public class CamMobileValidator {
    // Regex patterns for each operator
    private static final Pattern MTN_PATTERN = Pattern.compile("^(68\\d{7}|67\\d{7}|65[0-4]\\d{6})$");
    private static final Pattern ORANGE_PATTERN = Pattern.compile("^(69\\d{7}|65[5-9]\\d{6})$");
    private static final Pattern NEXTTEL_PATTERN = Pattern.compile("^66\\d{7}$");

    // Country code patterns
    private static final Pattern COUNTRY_CODE_PATTERN = Pattern.compile("^(\\+237|00237)");

    /**
     * Validates a Cameroon mobile number and returns the operator name
     *
     * @param mobileNumber The mobile number to validate
     * @return The PhoneNumber object
     * @throws InvalidMobileNumberException if the number is invalid
     */
    public static PhoneNumberDto validate(String mobileNumber) throws InvalidMobileNumberException {
        final String numberWithoutCountryCode = validateFormat(mobileNumber);

        // Check operator patterns and return operator name
        final Provider provider = identifyOperator(numberWithoutCountryCode);

        return new PhoneNumberDto(numberWithoutCountryCode, provider, "CM");

    }

    private static String validateFormat(String mobileNumber) {
        if (StringUtils.isBlank(mobileNumber)) {
            throw new InvalidMobileNumberException("Mobile number cannot be null",
                                                   Map.of(),
                                                   InvalidMobileNumberError.CAMO_BLANK_NO);
        }

        // Remove whitespace
        String cleanNumber = mobileNumber.replaceAll("\\s+", "");

        // Check for special characters or letters (except + at the beginning for country code)
        if (!cleanNumber.matches("^(\\+237|00237)?[0-9]+$")) {
            throw new InvalidMobileNumberException("Mobile number contains invalid characters",
                                                   Map.of("number", mobileNumber),
                                                   InvalidMobileNumberError.CAMO_INVALID_NO);
        }

        // Strip country code if present
        String numberWithoutCountryCode = stripCountryCode(cleanNumber);

        // Validate general rules
        validateGeneralRules(numberWithoutCountryCode);
        return numberWithoutCountryCode;
    }

    public static void validateProviderNumber(Provider provider, String number) {
        final String numberWithoutCountryCode = validateFormat(number);
        if (provider == Provider.MTN) {
            if (!MTN_PATTERN.matcher(numberWithoutCountryCode).matches()) {
                throw new InvalidMobileNumberException(
                        "Number does not match MTN pattern: " + number,
                        Map.of("number", number),
                        InvalidMobileNumberError.CAMO_INVALID_OPERATOR
                );
            }
        } else if (provider == Provider.ORANGE) {
            if (!ORANGE_PATTERN.matcher(numberWithoutCountryCode).matches()) {
                throw new InvalidMobileNumberException(
                        "Number does not match Orange pattern: " + number,
                        Map.of("number", number),
                        InvalidMobileNumberError.CAMO_INVALID_OPERATOR
                );
            }
        } else if (provider == Provider.NEXTTEL) {
            if (!NEXTTEL_PATTERN.matcher(numberWithoutCountryCode).matches()) {
                throw new InvalidMobileNumberException(
                        "Number does not match NextTel pattern: " + number,
                        Map.of("number", number),
                        InvalidMobileNumberError.CAMO_INVALID_OPERATOR
                );
            }
        } else {
            throw new InvalidMobileNumberException(
                    "Number does not match any known operator pattern: " + number,
                    Map.of("number", number),
                    InvalidMobileNumberError.CAMO_INVALID_OPERATOR
            );
        }
    }

    /**
     * Strips the country code from the mobile number
     *
     * @param number The mobile number with potential country code
     * @return The mobile number without country code
     */
    private static String stripCountryCode(String number) {
        return COUNTRY_CODE_PATTERN.matcher(number).replaceFirst("");
    }

    /**
     * Validates general rules for Cameroon mobile numbers
     *
     * @param number The mobile number without country code
     * @throws InvalidMobileNumberException if general rules are violated
     */
    private static void validateGeneralRules(String number) throws InvalidMobileNumberException {
        // Check if number has exactly 9 digits
        if (number.length() != 9) {
            throw new InvalidMobileNumberException(
                    "Mobile number must have exactly 9 digits, found: " + number.length(),
                    Map.of("number", number),
                    InvalidMobileNumberError.CAMO_INVALID_DIGIT_COUNT
            );
        }

        // Check if number starts with 6
        if (!number.startsWith("6")) {
            throw new InvalidMobileNumberException(
                    "Mobile number must start with digit 6, found: " + number.charAt(0),
                    Map.of("number", number),
                    InvalidMobileNumberError.CAMO_INVALID_FIRST_DIGIT
            );
        }

        // Ensure all characters are digits
        if (!number.matches("\\d{9}")) {
            throw new InvalidMobileNumberException("Mobile number must contain only digits",
                                                   Map.of("number", number),
                                                   InvalidMobileNumberError.CAMO_INVALID_NO);
        }

        //ensure it does not contain a sequence of 3 or above zero digits
        if(number.contains("000")) {
            throw new InvalidMobileNumberException("Mobile number cannot contain a sequence of 3 or above zero digits",
                                                   Map.of("number", number),
                                                   InvalidMobileNumberError.CAMO_INVALID_NO);

        }
    }

    /**
     * Identifies the mobile operator based on number patterns
     *
     * @param number The validated mobile number
     * @return The operator
     * @throws InvalidMobileNumberException if no operator pattern matches
     */
    private static Provider identifyOperator(String number) throws InvalidMobileNumberException {
        if (MTN_PATTERN.matcher(number).matches()) {
            return Provider.MTN;
        } else if (ORANGE_PATTERN.matcher(number).matches()) {
            return Provider.ORANGE;
        } else if (NEXTTEL_PATTERN.matcher(number).matches()) {
            return Provider.NEXTTEL;
        } else {
            throw new InvalidMobileNumberException(
                    "Number does not match any valid operator pattern: " + number,
                    Map.of("number", number),
                    InvalidMobileNumberError.CAMO_INVALID_OPERATOR
            );
        }
    }

    /**
     * Demo method to test the validator
     */
    /*public static void main(String[] args) {
        // Test cases
        String[] testNumbers = {
                "688684749",     // MTN - valid
                "+237678684749", // MTN - valid with country code
                "651684749",     // MTN - valid (650-654 range)
                "655684749",     // Orange - valid (655-659 range)
                "698684749",     // Orange - valid
                "00237667684749", // NextTel - valid with country code
                "677684749",     // Invalid - doesn't match any pattern
                "788684749",     // Invalid - doesn't start with 6
                "68868474",      // Invalid - too short
                "6886847499",    // Invalid - too long
                "68868474a",     // Invalid - contains letter
                "688 684 749"    // Invalid - contains spaces
        };

    }*/

    public enum InvalidMobileNumberError implements ErrorCode {
        CAMO_INVALID_DIGIT_COUNT,
        CAMO_INVALID_FIRST_DIGIT,
        CAMO_INVALID_NO,
        CAMO_BLANK_NO,
        CAMO_INVALID_OPERATOR
        ;

        @Override
        public String getErrorCode() {
            return this.name();
        }
    }
    /**
     * Custom exception for invalid mobile number violations
     */
    public static class InvalidMobileNumberException extends ApplicationException {
        public InvalidMobileNumberException(String msg, Map<String, Object> logContext, ErrorCode errorCode) {
            super(msg, logContext, errorCode);
        }
    }
}
