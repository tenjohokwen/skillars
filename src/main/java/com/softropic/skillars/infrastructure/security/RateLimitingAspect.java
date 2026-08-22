package com.softropic.skillars.infrastructure.security;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import static com.softropic.skillars.infrastructure.security.SecurityError.TOO_MANY_REQUESTS;

/**
 * Aspect for enforcing rate limits on methods annotated with {@link RateLimited}.
 * <p>
 * Rate limiting can be disabled entirely via the {@code rate.limiting.enabled} property
 * (defaults to {@code true}). Setting it to {@code false} is useful in integration tests
 * where the same method is called multiple times without triggering the limit.
 * <p>
 * A low explicit order guarantees this aspect wraps outside Spring's {@code @Transactional}
 * advisor (which defaults to {@code LOWEST_PRECEDENCE}) for any method carrying both annotations,
 * so a rejected call never opens a DB transaction/connection first. Without an explicit order,
 * relative advisor ordering between two default-precedence advisors is unspecified (found by code
 * review against {@code ReportGenerationService.generateReport}, the first call site combining
 * {@code @RateLimited} with {@code @Transactional}).
 * <p>
 * Deliberately {@code HIGHEST_PRECEDENCE + 100}, not literal {@code HIGHEST_PRECEDENCE}: Spring's
 * own {@code ExposeInvocationInterceptor} also runs at exactly {@code HIGHEST_PRECEDENCE}, and tying
 * that value flips this aspect ahead of it non-deterministically, breaking
 * {@code AopContext.currentProxy()}/{@code MethodInvocation}-dependent machinery elsewhere in the
 * proxy chain (reproduced by {@code RateLimitingAspectIT} with the literal value — Spring's own
 * {@code IllegalStateException} message names this exact hazard). {@code + 100} still sorts well
 * before {@code LOWEST_PRECEDENCE} without contending for the single reserved earliest slot.
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
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
