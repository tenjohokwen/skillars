package com.softropic.skillars.infrastructure.email;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Replaces {@code SesEnabledPropertyValidator} (skillars-deferred-88 AC9): fail fast, with a clear
 * message, on an {@code app.email.transport} value this codebase cannot wire.
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
 *   <li>A <strong>present</strong> value that is none of {@code ses}, {@code smtp}, {@code log}
 *       (case-insensitive, matching {@code @ConditionalOnProperty} semantics — no surrounding-
 *       whitespace tolerance) aborts with a one-line message naming the property and the value.
 * </ul>
 *
 * <p><strong>Story ses-1.2 AC6:</strong> {@code smtp} is now a fully working transport — a bean now
 * implements {@link OutboundEmailSender} for it, wired exactly like the {@code ses}/{@code log}
 * senders already were — so the Phase-1-only special-case rejection of {@code smtp} (with its own
 * "not implemented until Phase 2" message) is removed. All three {@link EmailTransport} values pass
 * this gate the same way; nothing else in this class changes.
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
        if (!raw.equalsIgnoreCase("ses") && !raw.equalsIgnoreCase("smtp") && !raw.equalsIgnoreCase("log")) {
            throw new IllegalStateException(
                PROPERTY + " must be one of 'ses', 'smtp', 'log' (got: '" + raw + "')");
        }
    }
}
