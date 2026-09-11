package com.softropic.skillars.infrastructure.email;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story ses-1.1 Task 6 — everything that depends on {@link EmailTransportPropertyValidator}
 * actually running as a registered {@code EnvironmentPostProcessor}, via a real
 * {@link SpringApplicationBuilder}, not {@code ApplicationContextRunner}.
 *
 * <p>{@code ApplicationContextRunner} cannot prove this class is registered via
 * {@code spring.factories} — it never runs {@code EnvironmentPostProcessor}s, because it isn't a
 * {@code SpringApplication}. Only a real boot reads the actual
 * {@code META-INF/spring.factories} on the classpath.
 *
 * <p>Assertions check the <strong>message text</strong>, not just "the context failed to start":
 * {@code EmailTransportProperties.transport} is a typed {@link EmailTransport} enum, so an
 * unmappable value (e.g. {@code bogus}) also fails Spring's own relaxed-binding conversion — with a
 * generic, unrelated message — regardless of whether this validator is registered at all. A test
 * that only asserts failure would stay green even if the {@code spring.factories} registration
 * silently broke.
 */
class EmailTransportBootIT {

    @Configuration
    static class EmptyConfig {
    }

    /**
     * Passes overrides as command-line args ({@code --key=value}), not
     * {@link SpringApplicationBuilder#properties(String...)} — that method adds a
     * lowest-precedence "defaultProperties" source, which {@code application.yaml}'s base default
     * would shadow, silently defeating every override this test tries to make.
     */
    private static ConfigurableApplicationContext boot(String... keyValuePairs) {
        String[] args = new String[keyValuePairs.length];
        for (int i = 0; i < keyValuePairs.length; i++) {
            args[i] = "--" + keyValuePairs[i];
        }
        return new SpringApplicationBuilder(EmptyConfig.class)
            .web(WebApplicationType.NONE)
            .run(args);
    }

    private static String fullCauseChainMessage(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = ex; t != null; t = t.getCause()) {
            sb.append(t.getMessage()).append(" | ");
        }
        return sb.toString();
    }

    /**
     * Runs with <strong>no profile active and no property manually supplied</strong> — the same
     * shape as {@code AdminLoginResourceTest}/{@code RateLimitingAspectIT} — so this is the
     * assertion that actually proves the {@code EnvironmentPostProcessor} ordering assumption in
     * {@link EmailTransportPropertyValidator}'s javadoc: it must run <em>after</em>
     * {@code ConfigDataEnvironmentPostProcessor} has loaded {@code application.yaml}'s base default.
     */
    @Test
    void absentValue_doesNotAbort_resolvesToLogBaseDefault() {
        try (ConfigurableApplicationContext ctx = boot()) {
            assertThat(ctx.getEnvironment().getProperty("app.email.transport")).isEqualTo("log");
        }
    }

    @Test
    void unrecognisedValue_abortsWithThisValidatorsMessage() {
        // Assert the validator's own distinctive sentence, not just the property name and the
        // offending value — both of those also appear in Spring's generic relaxed-binding failure
        // ("Failed to bind properties under 'app.email.transport' ... Value: \"bogus\""), so the
        // previous assertion could not tell this validator's abort apart from a binder error.
        assertThatThrownBy(() -> boot("app.email.transport=bogus"))
            .satisfies(ex -> assertThat(fullCauseChainMessage(ex))
                .contains("app.email.transport must be one of 'ses', 'smtp', 'log'")
                .contains("bogus"));
    }

    /**
     * Story ses-1.2 AC6 — {@code smtp} is no longer the Phase-1-only special-cased rejection; it now
     * boots exactly like {@code ses}/{@code log}.
     *
     * <p><strong>Correction (code review 2026-09-11):</strong> this test on its own does
     * <em>not</em> prove {@link EmailTransportPropertyValidator} is still registered via {@code
     * META-INF/spring.factories} — if that registration were deleted entirely, this test would stay
     * exactly as green, since {@code smtp} is also a syntactically valid {@link EmailTransport} enum
     * value that Spring's own relaxed binding accepts with no validator involved at all. Registration
     * is what {@link #unrecognisedValue_abortsWithThisValidatorsMessage()} proves, by asserting this
     * validator's own distinctive message text (not Spring's generic binder-failure text) — a message
     * that can only appear if the validator actually ran. What this test adds on top of that is
     * narrower: that a valid, in-range value ({@code smtp}) is not itself rejected by validator logic
     * that does run.
     */
    @Test
    void presentSmtpValue_bootsWithoutAborting() {
        try (ConfigurableApplicationContext ctx = boot("app.email.transport=smtp")) {
            assertThat(ctx.getEnvironment().getProperty("app.email.transport")).isEqualTo("smtp");
        }
    }
}
