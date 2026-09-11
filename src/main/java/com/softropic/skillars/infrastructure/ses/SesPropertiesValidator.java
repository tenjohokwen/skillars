package com.softropic.skillars.infrastructure.ses;

import com.softropic.skillars.infrastructure.email.EmailAddressParser;
import com.softropic.skillars.infrastructure.exception.AppSetupException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;

import java.util.regex.Pattern;

/**
 * Validates {@link SesProperties} at startup, but only while {@code app.email.transport=ses}.
 *
 * <p><strong>Why this is a separate {@code @Component} rather than a {@code @PostConstruct} method
 * on {@link SesProperties} itself:</strong> {@code SesProperties} is registered via
 * {@code @EnableConfigurationProperties(SesProperties.class)} ({@code SesConfig.java}), and
 * {@code @ConditionalOnProperty} is silently <strong>ignored</strong> on a
 * {@code @ConfigurationProperties} class registered that way — the bean is created unconditionally
 * regardless of any annotation placed on the class. A {@code @PostConstruct} there would therefore
 * fire under {@code transport=log} too, breaking a dev/uat box with a blank {@code fromAddress}. A
 * dedicated {@code @Component} constructor-injecting {@link SesProperties}, gated by
 * {@link ConditionalOnProperty} on its own class, is conditional by construction — and makes "the
 * checks don't fire under {@code transport=log}" assertable as <strong>bean absence</strong> via
 * {@code ApplicationContextRunner} rather than as behaviour requiring a full context.
 *
 * <p><strong>What this validator does and doesn't cover:</strong> it validates the
 * <em>semantics</em> of already-bound values (blank, unparseable, non-positive), and — since code
 * review 2026-09-11 (D2) — forces the AWS credential chain to resolve, which is the one SES input
 * whose absence otherwise produces a green boot and silent total mail loss rather than an error. A structurally
 * malformed value of the wrong type — e.g. {@code max-send-rate-per-second: not-a-number} against a
 * numeric field — never reaches this validator at all; Spring's own {@code @ConfigurationProperties}
 * binder rejects it first, at context-refresh time, with its own message naming the offending
 * property and the conversion failure. That binder-level failure is standard Spring Boot behaviour
 * for every {@code @ConfigurationProperties} class in this codebase, not something introduced or
 * fixable by this class, and not this validator's job to duplicate or intercept.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.email.transport", havingValue = "ses")
public class SesPropertiesValidator {

    private static final Pattern CONFIGURATION_SET_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    /**
     * §6.5: the default (10) assumes a production (non-sandboxed) SES account. Nothing in this
     * story can detect from the app's side whether an account is sandboxed — that's
     * {@code SesHealthIndicator}'s {@code GetAccount} check, Phase 3 — so a WARN, not a startup
     * failure, is the right level here.
     */
    private static final int SANDBOX_RATE_WARN_THRESHOLD = 10;

    private final SesProperties props;
    private final EmailAddressParser addressParser;
    private final AwsCredentialsProvider sesCredentialsProvider;

    @PostConstruct
    void validate() {
        // Checked before from-address so a box with neither misconfiguration reports the one an
        // operator can actually act on first.
        if (props.getRegion() == null || props.getRegion().isBlank()) {
            // Region.of("") throws inside sesV2Client's @Bean method — the opaque bean-creation
            // stack trace EmailTransportPropertyValidator exists to prevent, reproduced for a
            // sibling property. Catch it here, with a message that names the property.
            throw new AppSetupException("app.ses.region must not be blank when app.email.transport=ses");
        }

        if (props.getFromAddress() == null || props.getFromAddress().isBlank()) {
            throw new AppSetupException("app.ses.from-address must not be blank when app.email.transport=ses");
        }
        try {
            addressParser.validateSingle(props.getFromAddress());
        } catch (IllegalArgumentException ex) {
            throw new AppSetupException(
                "app.ses.from-address is not a valid email address: " + props.getFromAddress());
        }

        String replyTo = props.getReplyToAddress();
        if (replyTo != null && !replyTo.isBlank()) {
            try {
                addressParser.validateSingle(replyTo);
            } catch (IllegalArgumentException ex) {
                throw new AppSetupException(
                    "app.ses.reply-to-address is not a valid email address: " + replyTo);
            }
        }

        String configurationSet = props.getConfigurationSet();
        if (configurationSet != null && !configurationSet.isBlank()
            && !CONFIGURATION_SET_PATTERN.matcher(configurationSet).matches()) {
            throw new AppSetupException(
                "app.ses.configuration-set must match " + CONFIGURATION_SET_PATTERN.pattern()
                    + " (got: '" + configurationSet + "')");
        }

        if (props.getMaxSendRatePerSecond() <= 0) {
            throw new AppSetupException(
                "app.ses.max-send-rate-per-second must be positive (got: "
                    + props.getMaxSendRatePerSecond() + ")");
        }

        // DefaultCredentialsProvider resolves lazily and SesV2Client.builder().build() never
        // forces it, so without this the app boots green with no resolvable credentials and every
        // send fails as SdkClientException -> *transient* -> logged and dropped: 100% mail loss
        // behind a healthy-looking application (code review 2026-09-11, D2). Prod already refuses
        // to start on a blank from-address; credentials are the same class of misconfiguration.
        try {
            sesCredentialsProvider.resolveCredentials();
        } catch (SdkException ex) {
            throw new AppSetupException(
                "AWS credentials could not be resolved for app.email.transport=ses — set "
                    + "app.ses.access-key/secret-key or provide an instance profile: " + ex.getMessage());
        }

        if (props.getMaxSendRatePerSecond() >= SANDBOX_RATE_WARN_THRESHOLD) {
            log.warn("app.ses.max-send-rate-per-second={} assumes a production (non-sandboxed) SES "
                    + "account; a sandboxed account is capped at 1/s and will throttle on the first "
                    + "burst — see requirements/ses-email-consolidation.md#6.5.",
                props.getMaxSendRatePerSecond());
        }
    }
}
