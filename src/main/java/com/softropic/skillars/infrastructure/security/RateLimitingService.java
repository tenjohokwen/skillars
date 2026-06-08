package com.softropic.skillars.infrastructure.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Service for managing rate limit buckets using Bucket4j.
 */
@Service
public class RateLimitingService {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * Attempts to consume a token from the bucket associated with the given identifier.
     *
     * @param identifier unique identifier for the client (e.g., IP address)
     * @param limitKey   unique key for the rate limit type (e.g., "registration")
     * @param capacity   maximum tokens in the bucket
     * @param duration   duration for tokens refill
     * @param unit       time unit for duration
     * @return true if a token was consumed, false if rate limit was exceeded
     */
    public boolean tryConsume(String identifier, String limitKey, long capacity, long duration, TimeUnit unit) {
        String bucketKey = limitKey + ":" + identifier;
        Bucket bucket = buckets.computeIfAbsent(bucketKey, k -> createBucket(capacity, duration, unit));
        return bucket.tryConsume(1);
    }

    private Bucket createBucket(long capacity, long duration, TimeUnit unit) {
        Refill refill = Refill.intervally(capacity, Duration.of(duration, unit.toChronoUnit()));
        Bandwidth limit = Bandwidth.classic(capacity, refill);
        return Bucket.builder()
                .addLimit(limit)
                .build();
    }
}
