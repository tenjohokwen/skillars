package com.softropic.skillars.infrastructure.security;

/**
 * Supplies how long an idle rate-limit bucket survives before {@link RateLimitingService}'s sweep
 * evicts it.
 *
 * <p>A port, not a config lookup: the value is runtime-tunable {@code platform_config}, which lives
 * in {@code platform.config}, and infrastructure must not import platform. The platform side
 * implements this ({@code platform.config.service.ConfigRateLimitBucketTtlSource}), so the
 * dependency points platform → infrastructure as the layering rule requires.
 */
@FunctionalInterface
public interface RateLimitBucketTtlSource {

    /** Idle-bucket TTL in hours; read on every sweep so a config change applies without a restart. */
    long bucketTtlHours();
}
