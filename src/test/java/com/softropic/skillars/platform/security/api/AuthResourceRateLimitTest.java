package com.softropic.skillars.platform.security.api;

import com.softropic.skillars.infrastructure.security.RateLimited;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-180: {@code POST /api/auth/refresh} is per-IP rate limited, so one source cannot queue unlimited
 * requests on one refresh-token row lock and exhaust the connection pool; {@code /logout} deliberately is not (a drained
 * bucket would let anyone on a shared IP suppress other users' logouts). The aspect is disabled in the test profile, so
 * the limit is pinned by annotation presence; the aspect's enforcement itself is covered by RateLimitingAspectIT.
 */
class AuthResourceRateLimitTest {

    @Test
    void refresh_isRateLimited() throws Exception {
        RateLimited limit = AuthResource.class
            .getMethod("refresh", HttpServletRequest.class, HttpServletResponse.class)
            .getAnnotation(RateLimited.class);

        assertThat(limit).as("@RateLimited on AuthResource.refresh").isNotNull();
        assertThat(limit.key()).isEqualTo("auth_refresh");
        assertThat(limit.capacity()).isEqualTo(5);
        assertThat(limit.duration()).isEqualTo(1);
        assertThat(limit.unit()).isEqualTo(TimeUnit.MINUTES);
    }

    @Test
    void logout_isNotRateLimited() throws Exception {
        assertThat(AuthResource.class
            .getMethod("logout", HttpServletRequest.class, HttpServletResponse.class)
            .getAnnotation(RateLimited.class))
            .as("a per-IP logout bucket lets anyone on a shared IP block other users' logouts")
            .isNull();
    }
}
