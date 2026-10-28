package com.softropic.skillars.platform.config.service;

import com.softropic.skillars.infrastructure.security.RateLimitBucketTtlSource;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link RateLimitBucketTtlSource} backed by {@code platform_config}: reads
 * {@link ConfigBounds#RATE_LIMIT_BUCKET_TTL_HOURS} with the registry's own default and range, so an
 * absent or out-of-range value falls back to 24h.
 */
@Component
@RequiredArgsConstructor
public class ConfigRateLimitBucketTtlSource implements RateLimitBucketTtlSource {

    private final ConfigService configService;

    @Override
    public long bucketTtlHours() {
        return configService.getBoundedLong(ConfigBounds.RATE_LIMIT_BUCKET_TTL_HOURS);
    }
}
