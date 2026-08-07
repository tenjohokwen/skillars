package com.softropic.skillars.infrastructure.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Validates that a String is a timezone identifier {@code java.time.ZoneId.of(...)} accepts —
 * typically an IANA region ID such as {@code "Europe/Berlin"}.
 *
 * <p><strong>Scope:</strong> this is a parseability check, not a canonical-IANA-name check.
 * {@code ZoneId.of} also accepts fixed offsets ({@code "+05:00"}, {@code "Z"}, {@code "GMT+2"}),
 * and this constraint deliberately lets them through: the defect it exists to close is outright
 * garbage ({@code "Not/AZone"}) reaching the database with no validation at all. Fixed offsets are
 * DST-blind, so a value like {@code "+01:00"} yields wall-clock times that are an hour wrong for
 * half the year — tightening to {@code ZoneId.of(v) instanceof ZoneRegion} would close that, but it
 * is an independently-scoped change with its own test surface and its own migration question for
 * values already stored. See {@code deferred-work.md} (skillars-deferred-18 review, D4).
 */
@Documented
@Constraint(validatedBy = IanaTimezoneValidator.class)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface IanaTimezone {

    String message() default "{validation.timezone.invalid}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
