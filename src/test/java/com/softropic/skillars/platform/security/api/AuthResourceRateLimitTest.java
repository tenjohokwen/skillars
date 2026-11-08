package com.softropic.skillars.platform.security.api;

import com.softropic.skillars.infrastructure.security.RateLimited;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-180: {@code POST /api/auth/refresh} and {@code /logout} are per-IP rate limited, so one source cannot
 * queue unlimited requests on one refresh-token row lock and exhaust the connection pool. The aspect is disabled in the
 * test profile, so the limit is pinned by annotation presence; the aspect's enforcement itself is covered by
 * RateLimitingAspectIT.
 */
class AuthResourceRateLimitTest {

    @ParameterizedTest
    @CsvSource({"refresh, auth_refresh", "logout, auth_logout"})
    void tokenEndpoints_areRateLimited(String method, String expectedKey) throws Exception {
        RateLimited limit = AuthResource.class
            .getMethod(method, HttpServletRequest.class, HttpServletResponse.class)
            .getAnnotation(RateLimited.class);

        assertThat(limit).as("@RateLimited on AuthResource.%s", method).isNotNull();
        assertThat(limit.key()).isEqualTo(expectedKey);
        assertThat(limit.capacity()).isEqualTo(20);
        assertThat(limit.duration()).isEqualTo(1);
        assertThat(limit.unit()).isEqualTo(TimeUnit.MINUTES);
    }
}
