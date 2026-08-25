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
        } catch (DateTimeException e) {
            return fail(context);
        }

        // Requires genuine IANA region-id membership, not just ZoneId.of parseability — ZoneId.of
        // alone also accepts DST-blind fixed offsets ("+01:00", "GMT+2") and other non-region forms
        // ("Z"), which this constraint now rejects. See @IanaTimezone's Javadoc for the scope
        // decision (2026-08-25, reversing the 2026-08-07 permissive scope).
        if (!ZoneId.getAvailableZoneIds().contains(value)) {
            return fail(context);
        }

        return true;
    }

    private static boolean fail(ConstraintValidatorContext context) {
        context.disableDefaultConstraintViolation();
        // Pipe separator to pass both the message key and its fallback to ApiAdvice, mirroring
        // CamPhoneValidator — the default-message-template shape (LangIso2) leaves the raw,
        // unresolved "{...}" placeholder in the API response when no ValidationMessages.properties
        // entry exists, which nothing in this codebase provides.
        context.buildConstraintViolationWithTemplate(
                "validation.timezone.invalid|Timezone must be a recognized timezone identifier, for example Europe/Berlin")
            .addConstraintViolation();
        return false;
    }
}
