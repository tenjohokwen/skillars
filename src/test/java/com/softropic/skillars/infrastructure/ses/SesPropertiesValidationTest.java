package com.softropic.skillars.infrastructure.ses;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.softropic.skillars.infrastructure.email.EmailAddressParser;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story ses-1.1 AC7 — {@link SesPropertiesValidator} exists (and enforces) only while
 * {@code app.email.transport=ses}, and its individual checks. Uses {@link ApplicationContextRunner}
 * (no {@code @SpringBootTest} context), per the existing {@code infrastructure.ses} test convention.
 */
class SesPropertiesValidationTest {

    @Configuration
    @EnableConfigurationProperties(SesProperties.class)
    static class Config {
    }

    /** Resolves without touching the network, so the credential check passes in every other test. */
    private static final AwsCredentialsProvider VALID_CREDENTIALS =
        StaticCredentialsProvider.create(AwsBasicCredentials.create("ak", "sk"));

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(Config.class, EmailAddressParser.class, SesPropertiesValidator.class)
        .withBean(AwsCredentialsProvider.class, () -> VALID_CREDENTIALS);

    private ListAppender<ILoggingEvent> logAppender;
    private Logger validatorLogger;

    @BeforeEach
    void setUp() {
        validatorLogger = (Logger) LoggerFactory.getLogger(SesPropertiesValidator.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        validatorLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        validatorLogger.detachAppender(logAppender);
    }

    @Test
    void transportSes_validBaseline_validatorBeanPresent() {
        runner.withPropertyValues(
                "app.email.transport=ses",
                "app.ses.from-address=noreply@example.com")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx).hasSingleBean(SesPropertiesValidator.class);
            });
    }

    @Test
    void transportLog_validatorBeanAbsent_checksDoNotFire() {
        // No app.ses.from-address at all — would fail under transport=ses, but transport=log must
        // still boot with a blank fromAddress.
        runner.withPropertyValues("app.email.transport=log")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx).doesNotHaveBean(SesPropertiesValidator.class);
            });
    }

    @Test
    void blankFromAddress_underTransportSes_failsStartup() {
        runner.withPropertyValues("app.email.transport=ses")
            .run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(fullCauseChainMessage(ctx.getStartupFailure())).contains("app.ses.from-address");
            });
    }

    @Test
    void unparseableFromAddress_failsStartup() {
        runner.withPropertyValues(
                "app.email.transport=ses",
                "app.ses.from-address=not-an-address")
            .run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(fullCauseChainMessage(ctx.getStartupFailure())).contains("app.ses.from-address");
            });
    }

    @Test
    void unparseableReplyToAddress_failsStartup() {
        runner.withPropertyValues(
                "app.email.transport=ses",
                "app.ses.from-address=noreply@example.com",
                "app.ses.reply-to-address=not-an-address")
            .run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(fullCauseChainMessage(ctx.getStartupFailure())).contains("app.ses.reply-to-address");
            });
    }

    @Test
    void malformedConfigurationSet_failsStartup() {
        runner.withPropertyValues(
                "app.email.transport=ses",
                "app.ses.from-address=noreply@example.com",
                "app.ses.configuration-set=has a space")
            .run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(fullCauseChainMessage(ctx.getStartupFailure())).contains("app.ses.configuration-set");
            });
    }

    @Test
    void validConfigurationSet_boots() {
        runner.withPropertyValues(
                "app.email.transport=ses",
                "app.ses.from-address=noreply@example.com",
                "app.ses.configuration-set=my-config-set-1")
            .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void nonPositiveMaxSendRate_failsStartup() {
        runner.withPropertyValues(
                "app.email.transport=ses",
                "app.ses.from-address=noreply@example.com",
                "app.ses.max-send-rate-per-second=0")
            .run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(fullCauseChainMessage(ctx.getStartupFailure())).contains("app.ses.max-send-rate-per-second");
            });
    }

    @Test
    void maxSendRateAtSandboxThreshold_warnsButDoesNotFailStartup() {
        runner.withPropertyValues(
                "app.email.transport=ses",
                "app.ses.from-address=noreply@example.com",
                "app.ses.max-send-rate-per-second=10")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(logAppender.list)
                    .anySatisfy(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.WARN);
                        assertThat(event.getFormattedMessage()).contains("max-send-rate-per-second");
                    });
            });
    }

    @Test
    void maxSendRateBelowSandboxThreshold_noWarn() {
        runner.withPropertyValues(
                "app.email.transport=ses",
                "app.ses.from-address=noreply@example.com",
                "app.ses.max-send-rate-per-second=1")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(logAppender.list).noneSatisfy(event ->
                    assertThat(event.getLevel()).isEqualTo(Level.WARN));
            });
    }

    /**
     * Documents the binder/validator boundary from this class's own javadoc: a structurally
     * malformed numeric value fails at Spring's own {@code @ConfigurationProperties} binding stage,
     * before {@link SesPropertiesValidator} ever runs. This is standard Spring Boot behaviour, not
     * something this validator is responsible for.
     */
    @Test
    void structurallyMalformedNumericField_failsAtBindingNotValidator() {
        runner.withPropertyValues(
                "app.email.transport=ses",
                "app.ses.from-address=noreply@example.com",
                "app.ses.max-send-rate-per-second=not-a-number")
            .run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(fullCauseChainMessage(ctx.getStartupFailure()))
                    .containsIgnoringCase("max-send-rate-per-second");
            });
    }

    @Test
    void blankRegion_failsStartupNamingTheProperty() {
        // Region.of("") throws inside sesV2Client's @Bean method — an opaque bean-creation stack
        // trace for a sibling property. The validator must catch it first, with a usable message.
        runner.withPropertyValues(
                "app.email.transport=ses",
                "app.ses.from-address=noreply@example.com",
                "app.ses.region=")
            .run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(fullCauseChainMessage(ctx.getStartupFailure())).contains("app.ses.region");
            });
    }

    @Test
    void unresolvableCredentials_underTransportSes_failStartup() {
        // DefaultCredentialsProvider resolves lazily and building the client never forces it, so
        // without this check the app boots green with no credentials and every send fails as
        // SdkClientException -> *transient* -> logged and dropped: 100% mail loss behind a healthy
        // application (code review 2026-09-11, D2).
        AwsCredentialsProvider unresolvable = () -> {
            throw SdkClientException.create("Unable to load credentials from any of the providers");
        };
        new ApplicationContextRunner()
            .withUserConfiguration(Config.class, EmailAddressParser.class, SesPropertiesValidator.class)
            .withBean(AwsCredentialsProvider.class, () -> unresolvable)
            .withPropertyValues(
                "app.email.transport=ses",
                "app.ses.from-address=noreply@example.com")
            .run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(fullCauseChainMessage(ctx.getStartupFailure()))
                    .contains("AWS credentials could not be resolved");
            });
    }

    @Test
    void unresolvableCredentials_underTransportLog_areNeverResolved() {
        // The whole point of the conditional bean: a dev/uat box with no AWS anything must boot.
        AwsCredentialsProvider unresolvable = () -> {
            throw SdkClientException.create("Unable to load credentials from any of the providers");
        };
        new ApplicationContextRunner()
            .withUserConfiguration(Config.class, EmailAddressParser.class, SesPropertiesValidator.class)
            .withBean(AwsCredentialsProvider.class, () -> unresolvable)
            .withPropertyValues("app.email.transport=log")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx).doesNotHaveBean(SesPropertiesValidator.class);
            });
    }

    /** Spring's binder names the property using the Java field name, not the kebab-case YAML key —
     * and the offending property is often several levels down the cause chain, not on the
     * top-level {@code BeanCreationException}. */
    private static String fullCauseChainMessage(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = ex; t != null; t = t.getCause()) {
            sb.append(t.getMessage()).append(" | ");
        }
        return sb.toString();
    }
}
