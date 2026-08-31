package com.softropic.skillars.infrastructure.ses;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Story skillars-deferred-88 AC9: fail fast, with a clear message, on an {@code app.ses.enabled}
 * value that is neither {@code true} nor {@code false}.
 *
 * <p>{@code SesConfig} ({@code SesV2Client}), {@code SesEmailServiceImpl} and
 * {@code NoOpSesEmailService} are gated on {@code app.ses.enabled} via {@code @ConditionalOnProperty}.
 * The three real gates match only when the raw value is (case-insensitively) {@code true}; the no-op
 * gate's {@code matchIfMissing = true} applies only when the property is <em>absent</em>. So an
 * unrecognised, present value ({@code yes}, {@code "True "}, an empty string — typically from an env
 * var or a {@code .properties} file, since YAML coerces {@code yes}/{@code on} to a boolean first)
 * leaves {@code SesEmailService} with <em>zero</em> implementations, and the app aborts startup deep
 * in a {@code NoSuchBeanDefinitionException} bean-creation stack trace with no hint at the cause.
 *
 * <p>This {@link EnvironmentPostProcessor} runs before any bean is created and turns that into a
 * one-line {@link IllegalStateException} naming the offending value. It deliberately does
 * <strong>not</strong> touch any {@code @ConditionalOnProperty} gate or {@code SesProperties}: they
 * are already a mutually consistent set and changing one in isolation would re-introduce an
 * inconsistency. The accepted set here mirrors {@code @ConditionalOnProperty}'s own semantics
 * exactly (case-insensitive, no surrounding-whitespace tolerance), so anything this validator
 * accepts also wires the beans, and anything it rejects would have left them unwired.
 */
public class SesEnabledPropertyValidator implements EnvironmentPostProcessor {

    static final String PROPERTY = "app.ses.enabled";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String raw = environment.getProperty(PROPERTY);
        if (raw == null) {
            // Unset is allowed — NoOpSesEmailService's matchIfMissing = true covers it.
            return;
        }
        if (!raw.equalsIgnoreCase("true") && !raw.equalsIgnoreCase("false")) {
            throw new IllegalStateException(
                PROPERTY + " must be 'true' or 'false' (got: '" + raw + "')");
        }
    }
}
