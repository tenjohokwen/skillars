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
import com.softropic.skillars.infrastructure.ses.SesHealthIndicator;
import com.softropic.skillars.infrastructure.ses.SesSendRateLimiter;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
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
            SesSendRateLimiter.class,
            LoggingEmailSender.class, EmailTransportProperties.class,
            SmtpConfig.class, SmtpEmailSender.class, MailSenderProvider.class, SmtpErrorClassifier.class);

    /**
     * Story ses-1.3 Task 6: {@link SesEmailSender} gained a {@link SesSendRateLimiter} constructor
     * dependency (AC3), which in turn needs a {@link MeterRegistry} bean — supplied here as a
     * {@link SimpleMeterRegistry} since no {@code MeterRegistry} bean otherwise exists in this file.
     */
    @Test
    void transportSes_wiresSesEmailSenderAndSesV2Client() {
        runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withPropertyValues(
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
     *
     * <p>Story ses-1.3: {@code SmtpHealthIndicator}'s bean existence stays gated on provider-presence
     * only (see that class's own javadoc for why bean-level transport gating was reverted) — the
     * {@code EmailTransportProperties} bean it now also depends on must simply be present in this
     * runner's configuration for autowiring to succeed.
     */
    @Test
    void smtpHealthIndicator_activatesOnlyWhenAProviderIsConfigured() {
        ApplicationContextRunner healthRunner = new ApplicationContextRunner()
            .withUserConfiguration(SmtpConfig.class, SmtpHealthIndicator.class)
            .withPropertyValues("app.email.transport=smtp");

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

    /**
     * Story ses-1.3 AC2/AC6 — the regression test for the actual production defect this phase closes.
     * The real prod shape is {@code app.email.transport=ses} <strong>with</strong> an SMTP provider
     * host also set: the base {@code application.yaml} sets {@code provider-configs[0].host}
     * unconditionally and {@code application-prod.yaml} never clears it, so before this story prod
     * really did register {@link SmtpHealthIndicator} and open sockets to SMTP hosts it never sends
     * through. With the transport now part of that bean's activation condition, only the indicator
     * for the active transport is registered at all.
     *
     * <p>Asserted as bean existence rather than reported status, because bean existence is the
     * mechanism: the {@code notification} health group's {@code include} list is declared per profile
     * and Spring Boot's {@code HealthEndpointGroupMembershipValidator} resolves every name in it
     * against registered beans at refresh, so "which beans exist" is exactly what the group contract
     * depends on. A status assertion would also require a live {@code GetAccount} call.
     */
    @Test
    void transportSesWithSmtpProviderConfigured_registersOnlyTheSesIndicator() {
        new ApplicationContextRunner()
            .withUserConfiguration(SesConfig.class, SmtpConfig.class,
                SmtpHealthIndicator.class, SesHealthIndicator.class)
            .withPropertyValues(
                "app.email.transport=ses",
                "app.ses.from-address=noreply@example.com",
                "app.email.smtp.provider-configs[0].host=mail.example.com",
                "app.email.smtp.provider-configs[0].port=587")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx).hasSingleBean(SesHealthIndicator.class);
                assertThat(ctx).doesNotHaveBean(SmtpHealthIndicator.class);
            });
    }

    /**
     * The mirror of the case above — {@code transport=smtp} with a provider configured registers the
     * SMTP indicator and not the SES one. Trivially true given {@link SesHealthIndicator}'s single
     * {@code transport=ses} condition, but asserted anyway: a broken composite condition on the SMTP
     * side is exactly the kind of bug that compiles fine and silently never fires.
     */
    @Test
    void transportSmtpWithProviderConfigured_registersOnlyTheSmtpIndicator() {
        new ApplicationContextRunner()
            .withUserConfiguration(SesConfig.class, SmtpConfig.class,
                SmtpHealthIndicator.class, SesHealthIndicator.class)
            .withPropertyValues(
                "app.email.transport=smtp",
                "app.email.smtp.provider-configs[0].host=mail.example.com",
                "app.email.smtp.provider-configs[0].port=587")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx).hasSingleBean(SmtpHealthIndicator.class);
                assertThat(ctx).doesNotHaveBean(SesHealthIndicator.class);
            });
    }

    /**
     * The {@code log} transport — and the property-absent state — register <strong>neither</strong>
     * indicator. This is why the {@code notification} health group is declared per profile and is
     * absent from the base {@code application.yaml}: naming either contributor in a
     * profile-independent {@code include} list would fail startup here with
     * {@code NoSuchHealthContributorException}.
     */
    @Test
    void transportLog_registersNeitherIndicator() {
        new ApplicationContextRunner()
            .withUserConfiguration(SesConfig.class, SmtpConfig.class,
                SmtpHealthIndicator.class, SesHealthIndicator.class)
            .withPropertyValues(
                "app.email.transport=log",
                "app.email.smtp.provider-configs[0].host=mail.example.com",
                "app.email.smtp.provider-configs[0].port=587")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx).doesNotHaveBean(SmtpHealthIndicator.class);
                assertThat(ctx).doesNotHaveBean(SesHealthIndicator.class);
            });
    }

    /**
     * Story ses-1.3, code review 2026-09-12 — the regression test for the boot failure that drove the
     * whole health-group design, pinned against the real
     * {@code HealthEndpointGroupMembershipValidator} rather than described in prose.
     *
     * <p>Positive: the shipped prod shape — {@code transport=ses} with {@code include: ses} — refreshes
     * cleanly. Negative: the profile-independent {@code include: smtp,ses} that this review replaced
     * fails refresh, because {@code SmtpHealthIndicator} is not registered under {@code transport=ses}.
     * The negative case is what makes the positive one meaningful: without it, a future change that
     * silently disabled the validator would leave the positive assertion green and prove nothing.
     */
    @Test
    void healthGroupInclude_mustNameOnlyIndicatorsTheActiveTransportRegisters() {
        ApplicationContextRunner healthEndpointRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HealthEndpointAutoConfiguration.class))
            .withUserConfiguration(SesConfig.class, SmtpConfig.class,
                SmtpHealthIndicator.class, SesHealthIndicator.class)
            .withPropertyValues(
                "app.email.transport=ses",
                "app.ses.from-address=noreply@example.com",
                "app.email.smtp.provider-configs[0].host=mail.example.com",
                "app.email.smtp.provider-configs[0].port=587");

        healthEndpointRunner
            .withPropertyValues("management.endpoint.health.group.notification.include=ses")
            .run(ctx -> assertThat(ctx).hasNotFailed());

        healthEndpointRunner
            .withPropertyValues("management.endpoint.health.group.notification.include=smtp,ses")
            .run(ctx -> assertThat(ctx).hasFailed());
    }
}
