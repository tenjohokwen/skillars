package com.softropic.skillars.infrastructure.email.smtp;

import com.softropic.skillars.infrastructure.exception.AppSetupException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * skillars-deferred-110 AC3 — {@link MailSenderProvider}'s own constructor validates
 * {@code app.email.smtp.provider-configs} directly (not dependent on {@link
 * SmtpPropertiesValidator}'s ordering), and {@link MailSenderProvider#nextSender()} never
 * divides by zero or indexes negative. The class-level {@link
 * org.springframework.boot.autoconfigure.condition.ConditionalOnProperty} gate is also verified
 * here via {@link ApplicationContextRunner}, mirroring {@code SesPropertiesValidationTest}'s
 * convention for this kind of conditional-bean assertion.
 */
class MailSenderProviderTest {

    private static ProviderConfig validConfig() {
        ProviderConfig config = new ProviderConfig();
        config.setHost("smtp.example.com");
        config.setPort("587");
        config.setUsername("user");
        config.setPassword("secret");
        return config;
    }

    private static SmtpProperties propsWith(List<ProviderConfig> configs) {
        SmtpProperties props = new SmtpProperties();
        props.setProviderConfigs(configs);
        return props;
    }

    @Test
    @DisplayName("a valid single-provider config constructs successfully")
    void validConfig_constructsSuccessfully() {
        MailSenderProvider provider = new MailSenderProvider(propsWith(List.of(validConfig())));

        assertThat(provider.nextSender()).isNotNull();
    }

    @Test
    @DisplayName("an empty provider-configs list throws AppSetupException naming the property")
    void emptyProviderConfigs_throwsAppSetupException() {
        assertThatThrownBy(() -> new MailSenderProvider(propsWith(List.of())))
            .isInstanceOf(AppSetupException.class)
            .hasMessageContaining("app.email.smtp.provider-configs");
    }

    @Test
    @DisplayName("a blank host throws AppSetupException naming the property")
    void blankHost_throwsAppSetupException() {
        ProviderConfig config = validConfig();
        config.setHost(" ");

        assertThatThrownBy(() -> new MailSenderProvider(propsWith(List.of(config))))
            .isInstanceOf(AppSetupException.class)
            .hasMessageContaining("app.email.smtp.provider-configs[0].host");
    }

    @Test
    @DisplayName("a blank username throws AppSetupException naming the property")
    void blankUsername_throwsAppSetupException() {
        ProviderConfig config = validConfig();
        config.setUsername("");

        assertThatThrownBy(() -> new MailSenderProvider(propsWith(List.of(config))))
            .isInstanceOf(AppSetupException.class)
            .hasMessageContaining("app.email.smtp.provider-configs[0].username");
    }

    @Test
    @DisplayName("a non-numeric port throws AppSetupException naming the property, not a raw NumberFormatException")
    void nonNumericPort_throwsAppSetupException() {
        ProviderConfig config = validConfig();
        config.setPort("not-a-port");

        assertThatThrownBy(() -> new MailSenderProvider(propsWith(List.of(config))))
            .isInstanceOf(AppSetupException.class)
            .hasMessageContaining("app.email.smtp.provider-configs[0].port");
    }

    @Test
    @DisplayName("a blank port throws AppSetupException naming the property")
    void blankPort_throwsAppSetupException() {
        ProviderConfig config = validConfig();
        config.setPort("");

        assertThatThrownBy(() -> new MailSenderProvider(propsWith(List.of(config))))
            .isInstanceOf(AppSetupException.class)
            .hasMessageContaining("app.email.smtp.provider-configs[0].port");
    }

    /** skillars-deferred-110 code review 2026-09-14, owner decision D-2. */
    @Test
    @DisplayName("a blank password throws AppSetupException naming the property")
    void blankPassword_throwsAppSetupException() {
        ProviderConfig config = validConfig();
        config.setPassword("");

        assertThatThrownBy(() -> new MailSenderProvider(propsWith(List.of(config))))
            .isInstanceOf(AppSetupException.class)
            .hasMessageContaining("app.email.smtp.provider-configs[0].password");
    }

    /** skillars-deferred-110 code review 2026-09-14 (patch): parseInt alone accepts out-of-range values. */
    @Test
    @DisplayName("an out-of-range port (>65535) throws AppSetupException naming the property")
    void outOfRangePort_throwsAppSetupException() {
        ProviderConfig config = validConfig();
        config.setPort("70000");

        assertThatThrownBy(() -> new MailSenderProvider(propsWith(List.of(config))))
            .isInstanceOf(AppSetupException.class)
            .hasMessageContaining("app.email.smtp.provider-configs[0].port");
    }

    @Test
    @DisplayName("a zero or negative port throws AppSetupException naming the property")
    void nonPositivePort_throwsAppSetupException() {
        ProviderConfig config = validConfig();
        config.setPort("0");

        assertThatThrownBy(() -> new MailSenderProvider(propsWith(List.of(config))))
            .isInstanceOf(AppSetupException.class)
            .hasMessageContaining("app.email.smtp.provider-configs[0].port");
    }

    /**
     * skillars-deferred-110 code review 2026-09-14 (patch): the original version of this test used
     * three identical providers and asserted only non-null, so round-robin ordering was
     * unobservable — a mutation that always returned {@code providers.get(0)} would have stayed
     * green. Three distinguishable providers plus asserting the actual host sequence closes that.
     */
    @Test
    @DisplayName("nextSender() round-robins across distinguishable providers and never returns a negative "
        + "index across an AtomicInteger rollover")
    void nextSender_roundRobinsAndNeverNegativeAcrossRollover() throws Exception {
        ProviderConfig first = validConfig();
        first.setHost("smtp-a.example.com");
        ProviderConfig second = validConfig();
        second.setHost("smtp-b.example.com");
        ProviderConfig third = validConfig();
        third.setHost("smtp-c.example.com");

        MailSenderProvider provider = new MailSenderProvider(propsWith(List.of(first, second, third)));

        assertThat(provider.nextSender().getHost()).isEqualTo("smtp-a.example.com");
        assertThat(provider.nextSender().getHost()).isEqualTo("smtp-b.example.com");
        assertThat(provider.nextSender().getHost()).isEqualTo("smtp-c.example.com");
        assertThat(provider.nextSender().getHost())
            .as("wraps back to the first provider")
            .isEqualTo("smtp-a.example.com");

        setCounter(provider, Integer.MAX_VALUE - 1);
        for (int i = 0; i < 5; i++) {
            assertThat(provider.nextSender()).isNotNull();
        }
    }

    private static void setCounter(MailSenderProvider provider, int value) throws Exception {
        Field field = MailSenderProvider.class.getDeclaredField("counter");
        field.setAccessible(true);
        ((AtomicInteger) field.get(provider)).set(value);
    }

    @Configuration
    @EnableConfigurationProperties(SmtpProperties.class)
    static class Config {
    }

    @Test
    @DisplayName("the bean does not construct at all under transport=ses, even with a malformed "
        + "provider-configs present — prod boot must never be exposed to this bean's validation")
    void transportSes_beanAbsent_evenWithMalformedProviderConfigs() {
        new ApplicationContextRunner()
            .withUserConfiguration(Config.class, MailSenderProvider.class)
            .withPropertyValues(
                "app.email.transport=ses",
                "app.email.smtp.provider-configs[0].host=",
                "app.email.smtp.provider-configs[0].port=not-a-number")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx).doesNotHaveBean(MailSenderProvider.class);
            });
    }

    @Test
    @DisplayName("under transport=smtp, a malformed provider-configs fails startup naming the property")
    void transportSmtp_malformedProviderConfigs_failsStartup() {
        new ApplicationContextRunner()
            .withUserConfiguration(Config.class, MailSenderProvider.class)
            .withPropertyValues(
                "app.email.transport=smtp",
                "app.email.smtp.provider-configs[0].host=smtp.example.com",
                "app.email.smtp.provider-configs[0].username=user",
                "app.email.smtp.provider-configs[0].password=secret",
                "app.email.smtp.provider-configs[0].port=not-a-number")
            .run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(fullCauseChainMessage(ctx.getStartupFailure())).contains("provider-configs[0].port");
            });
    }

    /** Same helper convention as {@code SesPropertiesValidationTest}: the offending property is
     * often several levels down the cause chain, not on the top-level {@code BeanCreationException}. */
    private static String fullCauseChainMessage(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = ex; t != null; t = t.getCause()) {
            sb.append(t.getMessage()).append(" | ");
        }
        return sb.toString();
    }
}
