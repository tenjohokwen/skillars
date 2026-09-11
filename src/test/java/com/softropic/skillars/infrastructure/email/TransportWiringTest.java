package com.softropic.skillars.infrastructure.email;

import com.softropic.skillars.infrastructure.email.log.LoggingEmailSender;
import com.softropic.skillars.infrastructure.ses.SesConfig;
import com.softropic.skillars.infrastructure.ses.SesEmailSender;
import com.softropic.skillars.infrastructure.ses.SesErrorClassifier;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.sesv2.SesV2Client;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story ses-1.1 Task 6 — {@code ApplicationContextRunner}-based, bean-wiring only: given
 * {@code app.email.transport} supplied directly as an already-resolved property on the runner (no
 * YAML, no {@code EnvironmentPostProcessor} involved), exactly one {@link OutboundEmailSender} bean
 * exists for {@code ses} and {@code log}, with {@link SesV2Client} present only for {@code ses}.
 *
 * <p>This mechanism is correct here because {@code @ConditionalOnProperty} bean gating doesn't need
 * {@code EnvironmentPostProcessor} support — but it must <strong>not</strong> be used to prove
 * anything about {@code EmailTransportPropertyValidator} itself (see {@code EmailTransportBootIT}
 * for that) or about {@code transport=smtp}: no bean implements {@link OutboundEmailSender} for it
 * in this phase, and asserting bean absence there would prove nothing this story's design didn't
 * already intend.
 */
class TransportWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(
            SesConfig.class, SesEmailSender.class, SesErrorClassifier.class, EmailAddressParser.class,
            LoggingEmailSender.class, EmailTransportProperties.class);

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
}
