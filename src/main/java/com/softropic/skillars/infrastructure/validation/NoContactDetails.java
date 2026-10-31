package com.softropic.skillars.infrastructure.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Rejects a String that carries off-platform contact details (email, phone, link, messaging handle),
 * as judged by {@code ContactDetailSanitizer}. For short public identity fields — display name,
 * specialties, languages, pack labels — where silently redacting would leave a mangled name
 * ("[contact details removed]") on the coach's profile; the coach is told instead and fixes it.
 * Free-text fields such as the bio keep the redact-in-place behaviour.
 *
 * <p>Null and blank values are valid here — pair with {@code @NotBlank} where required. Usable on a
 * type argument ({@code List<@NoContactDetails String>}).
 */
@Documented
@Constraint(validatedBy = NoContactDetailsValidator.class)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE_USE, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface NoContactDetails {

    String message() default "{validation.contactDetails.notAllowed}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
