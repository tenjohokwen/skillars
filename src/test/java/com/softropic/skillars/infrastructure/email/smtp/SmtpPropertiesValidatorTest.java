package com.softropic.skillars.infrastructure.email.smtp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-110 AC3 — {@link SmtpPropertiesValidator} exists (and enforces) only while
 * {@code app.email.transport=smtp}, mirroring {@code SesPropertiesValidationTest}'s convention for
 * its sibling. As documented on the class itself, this validator is redundant defense —
 * {@link MailSenderProviderTest} covers the load-bearing constructor-level checks.
 */
class SmtpPropertiesValidatorTest {

    @Configuration
    @EnableConfigurationProperties(SmtpProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(Config.class, SmtpPropertiesValidator.class);

    @Test
    void transportSmtp_validBaseline_validatorBeanPresent() {
        runner.withPropertyValues(
                "app.email.transport=smtp",
                "app.email.smtp.provider-configs[0].host=smtp.example.com",
                "app.email.smtp.provider-configs[0].port=587",
                "app.email.smtp.provider-configs[0].username=user",
                "app.email.smtp.provider-configs[0].password=secret")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx).hasSingleBean(SmtpPropertiesValidator.class);
            });
    }

    @Test
    void transportSes_validatorBeanAbsent_checksDoNotFire() {
        runner.withPropertyValues("app.email.transport=ses")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx).doesNotHaveBean(SmtpPropertiesValidator.class);
            });
    }

    @Test
    void transportLog_validatorBeanAbsent_checksDoNotFire() {
        runner.withPropertyValues("app.email.transport=log")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx).doesNotHaveBean(SmtpPropertiesValidator.class);
            });
    }

    @Test
    void emptyProviderConfigs_underTransportSmtp_failsStartup() {
        runner.withPropertyValues("app.email.transport=smtp")
            .run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(fullCauseChainMessage(ctx.getStartupFailure())).contains("provider-configs");
            });
    }

    @Test
    void blankHost_underTransportSmtp_failsStartupNamingTheProperty() {
        runner.withPropertyValues(
                "app.email.transport=smtp",
                "app.email.smtp.provider-configs[0].port=587",
                "app.email.smtp.provider-configs[0].username=user")
            .run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(fullCauseChainMessage(ctx.getStartupFailure())).contains("provider-configs[0].host");
            });
    }

    @Test
    void blankUsername_underTransportSmtp_failsStartupNamingTheProperty() {
        runner.withPropertyValues(
                "app.email.transport=smtp",
                "app.email.smtp.provider-configs[0].host=smtp.example.com",
                "app.email.smtp.provider-configs[0].port=587")
            .run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(fullCauseChainMessage(ctx.getStartupFailure())).contains("provider-configs[0].username");
            });
    }

    @Test
    void nonNumericPort_underTransportSmtp_failsStartupNamingTheProperty() {
        runner.withPropertyValues(
                "app.email.transport=smtp",
                "app.email.smtp.provider-configs[0].host=smtp.example.com",
                "app.email.smtp.provider-configs[0].port=not-a-number",
                "app.email.smtp.provider-configs[0].username=user",
                "app.email.smtp.provider-configs[0].password=secret")
            .run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(fullCauseChainMessage(ctx.getStartupFailure())).contains("provider-configs[0].port");
            });
    }

    /** skillars-deferred-110 code review 2026-09-14, owner decision D-2. */
    @Test
    void blankPassword_underTransportSmtp_failsStartupNamingTheProperty() {
        runner.withPropertyValues(
                "app.email.transport=smtp",
                "app.email.smtp.provider-configs[0].host=smtp.example.com",
                "app.email.smtp.provider-configs[0].port=587",
                "app.email.smtp.provider-configs[0].username=user")
            .run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(fullCauseChainMessage(ctx.getStartupFailure())).contains("provider-configs[0].password");
            });
    }

    /** skillars-deferred-110 code review 2026-09-14 (patch): parseInt alone accepts out-of-range values. */
    @Test
    void outOfRangePort_underTransportSmtp_failsStartupNamingTheProperty() {
        runner.withPropertyValues(
                "app.email.transport=smtp",
                "app.email.smtp.provider-configs[0].host=smtp.example.com",
                "app.email.smtp.provider-configs[0].port=70000",
                "app.email.smtp.provider-configs[0].username=user",
                "app.email.smtp.provider-configs[0].password=secret")
            .run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(fullCauseChainMessage(ctx.getStartupFailure())).contains("provider-configs[0].port");
            });
    }

    /** Same helper convention as {@code SesPropertiesValidationTest}. */
    private static String fullCauseChainMessage(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = ex; t != null; t = t.getCause()) {
            sb.append(t.getMessage()).append(" | ");
        }
        return sb.toString();
    }
}
