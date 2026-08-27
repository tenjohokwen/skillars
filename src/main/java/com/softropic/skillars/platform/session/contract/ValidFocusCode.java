package com.softropic.skillars.platform.session.contract;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Story Deferred-75 AC11: validates that a {@code developmentFocus} list element is one of
 * {@link com.softropic.skillars.platform.session.service.DrillSuggestionService#KNOWN_FOCUS_CODES} —
 * the same set {@code computeFocusScore} scores against. Without this, an unrecognized code silently
 * passed through and scored 0 in {@code computeFocusScore}'s {@code default} branch rather than being
 * rejected at the request boundary.
 */
@Documented
@Constraint(validatedBy = ValidFocusCodeValidator.class)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidFocusCode {

    String message() default "{validation.developmentFocus.invalid}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
