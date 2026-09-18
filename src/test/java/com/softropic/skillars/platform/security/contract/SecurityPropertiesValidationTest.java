package com.softropic.skillars.platform.security.contract;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Code review 2026-09-18 (skillars-deferred-122): {@link SecurityProperties} gained {@code @Validated}
 * plus {@code @Min(1)} on {@code userCleanupBatchSize} so an unvalidated 0/negative batch size fails
 * startup instead of surfacing at runtime as an {@code ArithmeticException}/{@code
 * IllegalArgumentException} inside {@code UserAdminService.removeNotActivatedUsers}, silently killing
 * the scheduled cleanup job with no operator-visible cause at boot. Mirrors {@code
 * SesHealthPropertiesValidationTest}'s established {@code ApplicationContextRunner} shape.
 */
class SecurityPropertiesValidationTest {

    @Configuration
    @EnableConfigurationProperties(SecurityProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(Config.class);

    @Test
    void defaults_bindSuccessfully() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            SecurityProperties props = context.getBean(SecurityProperties.class);
            assertThat(props.getUserCleanupBatchSize()).isEqualTo(100);
        });
    }

    @Test
    void userCleanupBatchSizeOfZero_failsToBind() {
        runner.withPropertyValues("app.security.user-cleanup-batch-size=0")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void userCleanupBatchSizeNegative_failsToBind() {
        runner.withPropertyValues("app.security.user-cleanup-batch-size=-1")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void userCleanupBatchSizeOfOne_bindsSuccessfully() {
        runner.withPropertyValues("app.security.user-cleanup-batch-size=1")
            .run(context -> assertThat(context).hasNotFailed());
    }
}
