package com.softropic.skillars.infrastructure.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Validates that a String is a genuine IANA timezone region id — one that both
 * {@code java.time.ZoneId.of(...)} accepts and {@code ZoneId.getAvailableZoneIds()} lists, such as
 * {@code "Europe/Berlin"}.
 *
 * <p><strong>Scope (2026-08-25):</strong> {@code ZoneId.of} alone also accepts fixed offsets
 * ({@code "+05:00"}, {@code "Z"}, {@code "GMT+2"}), which are DST-blind — a value like
 * {@code "+01:00"} yields wall-clock times that are an hour wrong for half the year. This
 * constraint rejects those, requiring {@code ZoneId.getAvailableZoneIds().contains(value)} in
 * addition to {@code ZoneId.of} parseability (checking {@code ZoneId.of(v) instanceof ZoneRegion}
 * was considered but does not compile from outside {@code java.time} — {@code ZoneRegion} is
 * package-private). This reverses the 2026-08-07 scope decision that deliberately let fixed
 * offsets through; it is tighten-only — no audit or backfill of any already-stored non-conforming
 * value. See {@code deferred-work.md} (skillars-deferred-18 review, D4; closed by
 * skillars-deferred-65 AC2).
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
