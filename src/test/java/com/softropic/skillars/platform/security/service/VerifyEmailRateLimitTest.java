package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.infrastructure.security.RateLimited;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-162 AC1.1: every role's public {@code /verify-email} is per-IP rate limited. The aspect
 * is disabled in the test profile, so the limit is pinned by annotation presence; the aspect's enforcement
 * itself is covered by RateLimitingAspectIT.
 */
class VerifyEmailRateLimitTest {

    @ParameterizedTest
    @CsvSource({
        "com.softropic.skillars.platform.security.service.CoachRegistrationService, coach_verify_email",
        "com.softropic.skillars.platform.security.service.ParentRegistrationService, parent_verify_email",
        "com.softropic.skillars.platform.security.service.PlayerRegistrationService, player_verify_email"
    })
    void verifyEmail_isRateLimited(String serviceClass, String expectedKey) throws Exception {
        RateLimited limit = Class.forName(serviceClass)
                .getMethod("verifyEmail", UUID.class)
                .getAnnotation(RateLimited.class);

        assertThat(limit).as("@RateLimited on %s.verifyEmail", serviceClass).isNotNull();
        assertThat(limit.key()).isEqualTo(expectedKey);
        assertThat(limit.capacity()).isEqualTo(10);
        assertThat(limit.duration()).isEqualTo(10);
        assertThat(limit.unit()).isEqualTo(TimeUnit.MINUTES);
    }
}
