package com.softropic.skillars.infrastructure.email;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Replaces {@code SesEnabledPropertyValidator} (skillars-deferred-88 AC9): fail fast, with a clear
 * message, on an {@code app.email.transport} value this phase cannot wire.
 *
 * <p>Mirrors that validator's structure and reasoning exactly, adapted to the new property:
 *
 * <ul>
 *   <li>An <strong>absent</strong> value is allowed — it falls through to {@code application.yaml}'s
 *       base default ({@code log}). A design that aborted on "unset" would break every Spring test
 *       context that loads no profile ({@code AdminLoginResourceTest}, {@code RateLimitingAspectIT},
 *       {@code PropertiesFeatureToggleServiceIT}) and a bare {@code mvn spring-boot:run}, since
 *       {@link EnvironmentPostProcessor}s run for every {@code SpringApplication}, test or
 *       production — see story ses-1.1's AC3/Dev Notes.
 *   <li>A <strong>present</strong> value that is neither {@code ses}, {@code smtp} nor {@code log}
 *       (case-insensitive, matching {@code @ConditionalOnProperty} semantics — no surrounding-
 *       whitespace tolerance) aborts with a one-line message naming the property and the value.
 *   <li>{@code smtp} is syntactically part of the {@link EmailTransport} enum but is <strong>deliberately
 *       rejected here in this phase</strong>, with a distinct message. {@code SmtpEmailSender} has
 *       no bean until Phase 2, so accepting {@code smtp} now would pass this fail-fast gate and then
 *       die in a {@code NoSuchBeanDefinitionException} when the registration listeners fail to
 *       inject {@link OutboundEmailSender} — precisely the ambiguous failure mode this class exists
 *       to eliminate. It is also the single most likely value a developer reaches for once
 *       {@code DevSesEmailService} is deleted, since "I want a real dev email" looks like {@code
 *       smtp} to someone who hasn't read the story.
 * </ul>
 *
 * <p><strong>Ordering.</strong> This class implements no {@link org.springframework.core.Ordered},
 * so it sorts to {@code Ordered.LOWEST_PRECEDENCE}. That is required, not incidental: it must run
 * <em>after</em> {@code ConfigDataEnvironmentPostProcessor} ({@code HIGHEST_PRECEDENCE + 10}) has
 * loaded the profile YAMLs, or it would run before {@code application.yaml}'s base default is even
 * loaded and reject every boot with no profile active. Do not add {@code @Order(HIGHEST_PRECEDENCE)}
 * — mirror {@code SesEnabledPropertyValidator}'s existing (correct) behaviour.
 */
public class EmailTransportPropertyValidator implements EnvironmentPostProcessor {

    static final String PROPERTY = "app.email.transport";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String raw = environment.getProperty(PROPERTY);
        if (raw == null) {
            // Unset is allowed — application.yaml's base default (log) covers it.
            return;
        }
        if (raw.equalsIgnoreCase("smtp")) {
            throw new IllegalStateException(
                PROPERTY + "=smtp is not implemented until Phase 2 — use 'log' or 'ses'");
        }
        if (!raw.equalsIgnoreCase("ses") && !raw.equalsIgnoreCase("log")) {
            throw new IllegalStateException(
                PROPERTY + " must be one of 'ses', 'log' (got: '" + raw + "')");
        }
    }
}
