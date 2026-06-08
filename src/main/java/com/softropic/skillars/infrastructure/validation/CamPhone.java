package com.softropic.skillars.infrastructure.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Validates that a String contains a valid Cameroon mobile number.
 * Uses CamMobileValidator for validation logic.
 *
 * Validation includes:
 * - Must be exactly 9 digits (after country code removal)
 * - Must start with digit 6
 * - Must match a valid operator pattern (MTN, Orange, NextTel)
 * - No sequences of 3+ consecutive zeros
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
