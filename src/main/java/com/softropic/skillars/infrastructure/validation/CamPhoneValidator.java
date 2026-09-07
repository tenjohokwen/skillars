package com.softropic.skillars.infrastructure.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import lombok.extern.slf4j.Slf4j;

import java.util.regex.Pattern;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Bean Validation constraint validator for phone numbers.
 *
 * <p><strong>skillars-deferred-99 AC16:</strong> no longer Cameroon-specific. The rule is
 * config-driven per market — {@code app.validation.phone} ({@link PhoneValidationProperties}),
 * resolved through {@link PhoneRuleRegistry}/{@link MarketResolver} — defaulting to a permissive
 * international pattern (optional leading {@code +}, 7–15 digits). The annotation keeps its
 * {@code @CamPhone} name to avoid churning every DTO that references it; the name is now a misnomer.
 *
 * <p>Emits the project's pipe-template violation message ({@code key|fallback}) so {@code ApiAdvice}
 * can localise it, per the {@code deferred-18} lesson about bare {@code {...}} templates.
 */
@Slf4j
public class CamPhoneValidator implements ConstraintValidator<CamPhone, String> {

    @Override
    public boolean isValid(String phone, ConstraintValidatorContext context) {
        // Null / empty is valid here — use @NotNull / @NotBlank for required fields.
        if (phone == null || phone.isBlank()) {
            return true;
        }

        String normalized = phone.replaceAll("\\s+", "");
        Pattern pattern = resolvePattern();
        if (pattern.matcher(normalized).matches()) {
            return true;
        }

        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(
                "validation.phone.invalid|Invalid phone number format")
            .addConstraintViolation();
        log.warn("Phone validation failed",
            kv("operation", "phone_validation"),
            kv("reason", "validation.phone.invalid"),
            kv("status", "INVALID"));
        return false;
    }

    private Pattern resolvePattern() {
        PhoneRuleRegistry registry = PhoneRuleRegistry.current();
        if (registry == null) {
            // No Spring context (a hand-instantiated validator in a unit test) — permissive default.
            return PhoneRuleRegistry.DEFAULT_FALLBACK_PATTERN;
        }
        return registry.ruleFor(null).pattern();
    }
}
