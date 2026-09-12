package com.softropic.skillars.infrastructure.email;

import com.softropic.skillars.infrastructure.email.log.LoggingEmailSender;
import com.softropic.skillars.infrastructure.email.smtp.MailSenderProvider;
import com.softropic.skillars.infrastructure.email.smtp.SmtpConfig;
import com.softropic.skillars.infrastructure.email.smtp.SmtpEmailSender;
import com.softropic.skillars.infrastructure.email.smtp.SmtpErrorClassifier;
import com.softropic.skillars.infrastructure.email.smtp.SmtpHealthIndicator;
import com.softropic.skillars.infrastructure.ses.SesConfig;
import com.softropic.skillars.infrastructure.ses.SesEmailSender;
import com.softropic.skillars.infrastructure.ses.SesErrorClassifier;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.sesv2.SesV2Client;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story ses-1.1 Task 6, extended by story ses-1.2 Task 6 — {@code ApplicationContextRunner}-based,
 * bean-wiring only: given {@code app.email.transport} supplied directly as an already-resolved
 * property on the runner (no YAML, no {@code EnvironmentPostProcessor} involved), exactly one
 * {@link OutboundEmailSender} bean exists for {@code ses}, {@code smtp} and {@code log}, with
 * {@link SesV2Client} present only for {@code ses}.
 *
 * <p>This mechanism is correct here because {@code @ConditionalOnProperty} bean gating doesn't need
 * {@code EnvironmentPostProcessor} support — but it must <strong>not</strong> be used to prove
 * anything about {@code EmailTransportPropertyValidator} itself; see {@code EmailTransportBootIT}
 * for the real-{@code SpringApplication}, {@code spring.factories}-reading proof that this
 * validator is still registered (AC8).
 */
class TransportWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(
            SesConfig.class, SesEmailSender.class, SesErrorClassifier.class, EmailAddressParser.class,
            LoggingEmailSender.class, EmailTransportProperties.class,
            SmtpConfig.class, SmtpEmailSender.class, MailSenderProvider.class, SmtpErrorClassifier.class);

    @Test
    void transportSes_wiresSesEmailSenderAndSesV2Client() {
        runner.withPropertyValues(
                "app.email.transport=ses",
                "app.ses.from-address=noreply@example.com")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx).hasSingleBean(OutboundEmailSender.class);
                assertThat(ctx.getBean(OutboundEmailSender.class)).isInstanceOf(SesEmailSender.class);
                assertThat(ctx).hasSingleBean(SesV2Client.class);
            });
    }

    /**
     * Story ses-1.2 AC8 — the {@code smtp} case: {@code SmtpEmailSender} is the sole
     * {@link OutboundEmailSender}, backed by a real (if empty) {@code MailSenderProvider}.
     */
    @Test
    void transportSmtp_wiresSmtpEmailSender() {
        runner.withPropertyValues("app.email.transport=smtp")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx).hasSingleBean(OutboundEmailSender.class);
                assertThat(ctx.getBean(OutboundEmailSender.class)).isInstanceOf(SmtpEmailSender.class);
                assertThat(ctx).doesNotHaveBean(SesV2Client.class);
            });
    }

    /**
     * The state {@code EmailTransportPropertyValidator} deliberately waves through: an absent
     * property. With no {@code matchIfMissing} on either sender gate this wired <strong>zero</strong>
     * {@link OutboundEmailSender} beans, so the three registration listeners would die on
     * constructor injection with exactly the {@code NoSuchBeanDefinitionException} that validator
     * exists to prevent — reachable whenever a {@code spring.config.location} override shadows
     * {@code application.yaml}'s base default. The deleted {@code NoOpSesEmailService} carried
     * {@code matchIfMissing = true} for this reason (code review 2026-09-11).
     */
    @Test
    void transportAbsent_fallsBackToTheSafeLoggingTransport() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(OutboundEmailSender.class);
            assertThat(ctx.getBean(OutboundEmailSender.class)).isInstanceOf(LoggingEmailSender.class);
            assertThat(ctx).doesNotHaveBean(SesV2Client.class);
        });
    }

    @Test
    void transportLog_wiresLoggingEmailSenderAndNoSesV2Client() {
        runner.withPropertyValues("app.email.transport=log")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx).hasSingleBean(OutboundEmailSender.class);
                assertThat(ctx.getBean(OutboundEmailSender.class)).isInstanceOf(LoggingEmailSender.class);
                assertThat(ctx).doesNotHaveBean(SesV2Client.class);
            });
    }

    /**
     * Story ses-1.2 AC8 — health-indicator-exclusivity, scoped to what THIS phase actually changed:
     * {@code SmtpHealthIndicator} does not exist until this story, and {@code SesHealthIndicator}
     * does not exist until Phase 3 — so the meaningful half of the exclusivity story here is that
     * {@code SmtpHealthIndicator}'s own {@code @ConditionalOnProperty} still correctly
     * activates/deactivates based on whether an SMTP provider is configured. This is also where
     * AC2's {@code provider-configs[0].host} kebab-case casing fix gets its regression test: a naive
     * test that only checks "absent ⇒ no bean" would miss a broken conditional that also never fires
     * when configured.
     */
    @Test
    void smtpHealthIndicator_activatesOnlyWhenAProviderIsConfigured() {
        ApplicationContextRunner healthRunner = new ApplicationContextRunner()
            .withUserConfiguration(SmtpConfig.class, SmtpHealthIndicator.class);

        healthRunner
            .withPropertyValues(
                "app.email.smtp.provider-configs[0].host=mail.example.com",
                "app.email.smtp.provider-configs[0].port=587")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx).hasSingleBean(SmtpHealthIndicator.class);
            });

        healthRunner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(SmtpHealthIndicator.class);
        });
    }
}
