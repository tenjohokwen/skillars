package com.softropic.skillars.infrastructure.security;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import static com.softropic.skillars.infrastructure.security.SecurityError.TOO_MANY_REQUESTS;

/**
 * Aspect for enforcing rate limits on methods annotated with {@link RateLimited}.
 * <p>
 * Rate limiting can be disabled entirely via the {@code rate.limiting.enabled} property
 * (defaults to {@code true}). Setting it to {@code false} is useful in integration tests
 * where the same method is called multiple times without triggering the limit.
 */
@Aspect
@Component
public class RateLimitingAspect {

    private static final Logger LOGGER = LoggerFactory.getLogger(RateLimitingAspect.class);

    @Value("${rate.limiting.enabled:true}")
    private boolean enabled;

    private final RateLimitingService rateLimitingService;

    public RateLimitingAspect(RateLimitingService rateLimitingService) {
        this.rateLimitingService = rateLimitingService;
    }

    @Before("@annotation(rateLimited)")
    public void enforceRateLimit(JoinPoint joinPoint, RateLimited rateLimited) {
        if (!enabled) {
            return;
        }
        String identifier = getClientIdentifier();
        
        boolean allowed = rateLimitingService.tryConsume(
                identifier,
                rateLimited.key(),
                rateLimited.capacity(),
                rateLimited.duration(),
                rateLimited.unit()
        );

        if (!allowed) {
            LOGGER.warn("Rate limit exceeded for client: {}, key: {}", identifier, rateLimited.key());
            // Using a generic exception or creating a specific one.
            // Based on SecurityError, we might want to throw something that results in 429.
            throw new AuthorizationException("Too many requests. Please try again later.", TOO_MANY_REQUESTS);
        }
    }

    private String getClientIdentifier() {
        try {
            return RequestMetadataProvider.getClientInfo().getIpAddress();
        } catch (Exception e) {
            LOGGER.warn("Failed to get client IP address for rate limiting, using 'unknown'");
            return "unknown";
        }
    }
}
