package com.softropic.skillars.infrastructure.ses;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Code review 2026-09-15 (M2) — mirrors {@code SmtpHealthPropertiesValidationTest}: {@link
 * SesHealthProperties} gained {@code @Validated} plus {@code @DurationMin}/cross-field constraints so
 * a nonsensical {@code down-ttl} fails startup instead of silently defeating {@link
 * SesHealthIndicator}'s TTL cache.
 */
class SesHealthPropertiesValidationTest {

    @Configuration
    @EnableConfigurationProperties(SesHealthProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(Config.class);

    @Test
    void defaults_bindSuccessfully() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            SesHealthProperties props = context.getBean(SesHealthProperties.class);
            assertThat(props.getTtl().toSeconds()).isEqualTo(60);
            assertThat(props.getDownTtl().toSeconds()).isEqualTo(15);
        });
    }

    @Test
    void downTtlOfZero_failsToBind() {
        runner.withPropertyValues("app.ses.health.down-ttl=0s")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void downTtlGreaterThanTtl_failsToBind() {
        runner.withPropertyValues(
                "app.ses.health.ttl=30s",
                "app.ses.health.down-ttl=60s")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void downTtlEqualToTtl_bindsSuccessfully() {
        runner.withPropertyValues(
                "app.ses.health.ttl=30s",
                "app.ses.health.down-ttl=30s")
            .run(context -> assertThat(context).hasNotFailed());
    }
}
