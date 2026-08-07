package com.softropic.skillars.infrastructure.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.time.DateTimeException;
import java.time.ZoneId;

public class IanaTimezoneValidator implements ConstraintValidator<IanaTimezone, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // Null or blank values are valid here — use @NotBlank for required fields.
        if (value == null || value.isBlank()) {
            return true;
        }

        try {
            ZoneId.of(value);
            return true;
        } catch (DateTimeException e) {
            context.disableDefaultConstraintViolation();
            // Pipe separator to pass both the message key and its fallback to ApiAdvice, mirroring
            // CamPhoneValidator — the default-message-template shape (LangIso2) leaves the raw,
            // unresolved "{...}" placeholder in the API response when no ValidationMessages.properties
            // entry exists, which nothing in this codebase provides.
            // Wording deliberately says "recognized", not "valid IANA": ZoneId.of also accepts
            // fixed offsets, so promising IANA in the message would be untrue. See @IanaTimezone's
            // Javadoc for the scope decision (2026-08-07 code review).
            context.buildConstraintViolationWithTemplate(
                    "validation.timezone.invalid|Timezone must be a recognized timezone identifier, for example Europe/Berlin")
                .addConstraintViolation();
            return false;
        }
    }
}
