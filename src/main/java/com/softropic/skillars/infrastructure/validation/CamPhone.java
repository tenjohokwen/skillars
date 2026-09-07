package com.softropic.skillars.infrastructure.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Validates a phone-number String against the market-resolved rule (skillars-deferred-99 AC16).
 *
 * <p>The rule comes from {@code app.validation.phone} config, resolved per market, and defaults to a
 * permissive international pattern (optional leading {@code +}, 7–15 digits). The annotation name is
 * a historical misnomer — it is no longer Cameroon-specific — kept to avoid churning every DTO that
 * references it. See {@code CamPhoneValidator}.
 */
@Documented
@Constraint(validatedBy = CamPhoneValidator.class)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface CamPhone {

    String message() default "{validation.phone.invalid}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
