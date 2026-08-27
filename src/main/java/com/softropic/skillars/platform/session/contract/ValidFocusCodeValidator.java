package com.softropic.skillars.platform.session.contract;

import com.softropic.skillars.platform.session.service.DrillSuggestionService;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class ValidFocusCodeValidator implements ConstraintValidator<ValidFocusCode, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // Null or blank values are valid here — use @NotBlank for required elements, mirroring
        // IanaTimezoneValidator's convention.
        if (value == null || value.isBlank()) {
            return true;
        }
        // NOTE: This validator is ONE of three independent copies of the allowed focus codes.
        // DrillSuggestionService.KNOWN_FOCUS_CODES is the backend source of truth, but:
        // 1. DrillSuggestionService.computeFocusScore has a switch with 8 cases
        // 2. Frontend DevelopmentFocusSelector.vue has FOCUS_OPTIONS constant
        // If any code set is modified, all three MUST be kept in sync. See Story Deferred-75 AC11.
        if (DrillSuggestionService.KNOWN_FOCUS_CODES.contains(value)) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(
                "validation.developmentFocus.invalid|Unrecognized development focus code: " + value)
            .addConstraintViolation();
        return false;
    }
}
