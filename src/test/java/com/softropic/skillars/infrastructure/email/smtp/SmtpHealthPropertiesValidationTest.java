package com.softropic.skillars.infrastructure.email.smtp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Code review 2026-09-15 (M2) — {@link SmtpHealthProperties} gained {@code @Validated} plus
 * {@code @DurationMin}/cross-field constraints so a nonsensical {@code down-ttl} fails startup
 * instead of silently defeating the TTL cache (an unvalidated {@code down-ttl: 0s} makes
 * {@link SmtpHealthIndicator} re-probe on every scrape; an unvalidated {@code down-ttl > ttl} inverts
 * AC4's "surface recovery sooner" intent). Uses {@link ApplicationContextRunner}, per the existing
 * {@code infrastructure.email.smtp}/{@code infrastructure.ses} test convention for bare
 * {@code @ConfigurationProperties} validation (see {@code SesPropertiesValidationTest}).
 */
class SmtpHealthPropertiesValidationTest {

    @Configuration
    @EnableConfigurationProperties(SmtpHealthProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(Config.class);

    @Test
    void defaults_bindSuccessfully() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            SmtpHealthProperties props = context.getBean(SmtpHealthProperties.class);
            assertThat(props.getTtl().toSeconds()).isEqualTo(60);
            assertThat(props.getDownTtl().toSeconds()).isEqualTo(15);
        });
    }

    @Test
    void downTtlOfZero_failsToBind() {
        runner.withPropertyValues("app.email.smtp.health.down-ttl=0s")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void downTtlGreaterThanTtl_failsToBind() {
        runner.withPropertyValues(
                "app.email.smtp.health.ttl=30s",
                "app.email.smtp.health.down-ttl=60s")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void downTtlEqualToTtl_bindsSuccessfully() {
        runner.withPropertyValues(
                "app.email.smtp.health.ttl=30s",
                "app.email.smtp.health.down-ttl=30s")
            .run(context -> assertThat(context).hasNotFailed());
    }
}
