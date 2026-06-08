package com.softropic.skillars.infrastructure.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import lombok.extern.slf4j.Slf4j;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Bean Validation constraint validator for Cameroon mobile numbers.
 * Validates a phone number String using CamMobileValidator logic.
 *
 * This validator is used with the @CamPhone annotation and integrates
 * with Spring's validation framework to produce field-level errors
 * that are properly returned in the API response.
 */
@Slf4j
public class CamPhoneValidator implements ConstraintValidator<CamPhone, String> {

    @Override
    public void initialize(CamPhone constraintAnnotation) {
        // No initialization needed
    }

    @Override
    public boolean isValid(String phone, ConstraintValidatorContext context) {
        // Null or empty values are valid (use @NotNull/@NotBlank for required fields)
        if (phone == null || phone.isBlank()) {
            return true;
        }

        try {
            // Use the existing CamMobileValidator to validate the number
            CamMobileValidator.validate(phone);
            return true;
        } catch (CamMobileValidator.InvalidMobileNumberException e) {
            // Map the error code to a user-friendly message key and fallback
            String[] msgParts = mapErrorCodeToMessage(e.getErrorCode());
            addConstraintViolation(context, msgParts[0], msgParts[1]);
            return false;
        }
    }

    /**
     * Maps CamMobileValidator error codes to i18n message keys and fallback messages.
     * Returns array: [messageKey, fallbackMessage]
     */
    private String[] mapErrorCodeToMessage(com.softropic.skillars.infrastructure.exception.ErrorCode errorCode) {
        if (errorCode == null) {
            return new String[]{"validation.phone.invalid", "Invalid phone number format"};
        }

        return switch (errorCode.getErrorCode()) {
            case "CAMO_BLANK_NO" -> new String[]{"validation.phone.blank", "Phone number cannot be blank"};
            case "CAMO_INVALID_DIGIT_COUNT" -> new String[]{"validation.phone.digitCount", "Phone number must have exactly 9 digits"};
            case "CAMO_INVALID_FIRST_DIGIT" -> new String[]{"validation.phone.firstDigit", "Phone number must start with digit 6"};
            case "CAMO_INVALID_NO" -> new String[]{"validation.phone.invalid", "Invalid phone number format"};
            case "CAMO_INVALID_OPERATOR" -> new String[]{"validation.phone.operator", "Phone number does not match any valid operator (MTN, Orange, NextTel)"};
            default -> new String[]{"validation.phone.invalid", "Invalid phone number format"};
        };
    }

    /**
     * Adds a custom constraint violation with the specified message key.
     * Format: "messageKey|fallbackMessage" to preserve both for ApiAdvice processing.
     */
    private void addConstraintViolation(ConstraintValidatorContext context, String messageKey, String fallbackMessage) {
        context.disableDefaultConstraintViolation();
        // Use pipe separator to pass both key and fallback to ApiAdvice
        context.buildConstraintViolationWithTemplate(messageKey + "|" + fallbackMessage)
               .addConstraintViolation();
        log.warn("Phone validation failed",
                kv("operation", "phone_validation"),
                kv("reason", messageKey),
                kv("status", "INVALID"));
    }
}
