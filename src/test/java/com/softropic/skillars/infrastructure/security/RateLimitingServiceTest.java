package com.softropic.skillars.infrastructure.security;

import com.softropic.skillars.platform.config.service.ConfigService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class RateLimitingServiceTest {

    private ConfigService configService;
    private RateLimitingService rateLimitingService;
    /** Mutable so a test can advance "now" without a real sleep — see {@link #advanceClockBy}. */
    private Instant now;

    @BeforeEach
    void setUp() {
        configService = mock(ConfigService.class);
        // Default TTL matches ConfigBounds.RATE_LIMIT_BUCKET_TTL_HOURS' own code default (24h) —
        // individual eviction tests override this where a shorter TTL makes the case clearer.
        when(configService.getBoundedLong(eq("security.rate_limiting.bucket_ttl_hours"), anyLong(), anyLong(), anyLong()))
            .thenReturn(24L);

        rateLimitingService = new RateLimitingService(configService);
        now = Instant.parse("2026-01-01T00:00:00Z");
        rateLimitingService.clock = Clock.fixed(now, ZoneOffset.UTC);
    }

    private void advanceClockBy(Duration duration) {
        now = now.plus(duration);
        rateLimitingService.clock = Clock.fixed(now, ZoneOffset.UTC);
    }

    @Test
    void testTryConsume_Success() {
        boolean allowed = rateLimitingService.tryConsume("user1", "testKey", 5, 1, TimeUnit.MINUTES);
        assertThat(allowed).isTrue();
    }

    @Test
    void testTryConsume_ExceedLimit() {
        String id = "user2";
        String key = "testKey";
        int capacity = 2;

        // Consume all tokens
        assertThat(rateLimitingService.tryConsume(id, key, capacity, 1, TimeUnit.MINUTES)).isTrue();
        assertThat(rateLimitingService.tryConsume(id, key, capacity, 1, TimeUnit.MINUTES)).isTrue();

        // Should be blocked
        assertThat(rateLimitingService.tryConsume(id, key, capacity, 1, TimeUnit.MINUTES)).isFalse();
    }

    @Test
    void testTryConsume_IndependentBuckets() {
        String key = "testKey";
        int capacity = 1;

        // User 1 consumes their token
        assertThat(rateLimitingService.tryConsume("user3", key, capacity, 1, TimeUnit.MINUTES)).isTrue();
        assertThat(rateLimitingService.tryConsume("user3", key, capacity, 1, TimeUnit.MINUTES)).isFalse();

        // User 4 should still be allowed
        assertThat(rateLimitingService.tryConsume("user4", key, capacity, 1, TimeUnit.MINUTES)).isTrue();
    }

    // skillars-deferred-117 AC4: a bucket idle past the configured TTL is evicted by the sweep. The
    // clock is injected and advanced explicitly — no real Thread.sleep of wall-clock time.
    @Test
    void sweepIdleBuckets_bucketIdlePastTtl_isEvicted() {
        when(configService.getBoundedLong(eq("security.rate_limiting.bucket_ttl_hours"), anyLong(), anyLong(), anyLong()))
            .thenReturn(1L); // 1-hour TTL for this test

        assertThat(rateLimitingService.tryConsume("user5", "sweepKey", 1, 1, TimeUnit.MINUTES)).isTrue();
        // Bucket now exists and is exhausted (capacity 1, already consumed).
        assertThat(rateLimitingService.tryConsume("user5", "sweepKey", 1, 1, TimeUnit.MINUTES)).isFalse();

        advanceClockBy(Duration.ofHours(2)); // idle for longer than the 1h TTL
        rateLimitingService.sweepIdleBuckets();

        // Evicted, so a fresh bucket is created on next use — full capacity again, tryConsume succeeds.
        assertThat(rateLimitingService.tryConsume("user5", "sweepKey", 1, 1, TimeUnit.MINUTES)).isTrue();
    }

    // skillars-deferred-117 AC4: a bucket accessed within the TTL is NOT evicted, and the sweep does
    // not reset an actively-in-use bucket's remaining token count — only genuinely idle buckets are
    // touched.
    @Test
    void sweepIdleBuckets_bucketAccessedWithinTtl_isNotEvictedAndRemainingTokensUnaffected() {
        when(configService.getBoundedLong(eq("security.rate_limiting.bucket_ttl_hours"), anyLong(), anyLong(), anyLong()))
            .thenReturn(24L); // 24-hour TTL for this test

        assertThat(rateLimitingService.tryConsume("user6", "sweepKey", 1, 1, TimeUnit.MINUTES)).isTrue();
        // Bucket now exhausted (capacity 1, already consumed).

        advanceClockBy(Duration.ofHours(1)); // well within the 24h TTL
        rateLimitingService.sweepIdleBuckets();

        // Still evicted-worthy check: NOT evicted, so the same (still-exhausted) bucket is reused —
        // tryConsume must still be false, not reset to a fresh, full bucket by the sweep.
        assertThat(rateLimitingService.tryConsume("user6", "sweepKey", 1, 1, TimeUnit.MINUTES)).isFalse();
    }
}
