package com.softropic.skillars.infrastructure.security;

import com.softropic.skillars.infrastructure.security.RateLimitingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class RateLimitingServiceTest {

    private RateLimitingService rateLimitingService;

    @BeforeEach
    void setUp() {
        rateLimitingService = new RateLimitingService();
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
}
