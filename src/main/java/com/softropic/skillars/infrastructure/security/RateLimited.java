package com.softropic.skillars.infrastructure.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * Annotation to mark methods for rate limiting.
 * <p>
 * Rate limiting is applied based on the client's IP address by default.
 * Custom identifiers can be used if needed.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimited {

    /**
     * Unique key for the rate limit bucket (e.g., "registration", "login").
     */
    String key();

    /**
     * Number of allowed requests within the given time unit.
     */
    long capacity() default 5;

    /**
     * Time unit for the rate limit duration.
     */
    long duration() default 1;

    /**
     * Unit of time for the duration.
     */
    TimeUnit unit() default TimeUnit.MINUTES;
}
