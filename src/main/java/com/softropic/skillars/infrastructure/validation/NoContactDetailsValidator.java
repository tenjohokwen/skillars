package com.softropic.skillars.infrastructure.validation;

import com.softropic.skillars.infrastructure.sanitizer.ContactDetailSanitizer;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class NoContactDetailsValidator implements ConstraintValidator<NoContactDetails, String> {

    // The sanitizer is stateless; constructing it directly keeps the validator usable outside a Spring
    // context (plain Validator in unit tests) with the exact same rules the redaction paths use.
    private final ContactDetailSanitizer sanitizer = new ContactDetailSanitizer();

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        if (!sanitizer.sanitize(value).wasModified()) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        // key|fallback shape, as IanaTimezoneValidator: ApiAdvice resolves the key and falls back to the text.
        context.buildConstraintViolationWithTemplate(
                "validation.contactDetails.notAllowed|Please remove contact details (email, phone number, links or social handles) from this field")
            .addConstraintViolation();
        return false;
    }
}
