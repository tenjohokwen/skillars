package com.softropic.skillars.infrastructure.email.smtp;

import com.softropic.skillars.infrastructure.exception.AppSetupException;

import java.util.List;

/**
 * skillars-deferred-110 AC3, code review 2026-09-14 (patch): the shared validation logic for
 * {@code app.email.smtp.provider-configs}, extracted so {@link MailSenderProvider}'s constructor
 * (the load-bearing check — see its own javadoc for why validator/bean-creation ordering can't be
 * relied on) and {@link SmtpPropertiesValidator} (the redundant, discoverable sibling to {@code
 * SesPropertiesValidator}) no longer duplicate the same checks byte-for-byte, which had already
 * drifted once: the original pair caught a blank {@code username} but not a blank {@code password}
 * (a genuinely broken authenticated config — {@code username} set, {@code password} empty — passed
 * validation unremarked), and neither rejected a syntactically-numeric but out-of-range port
 * ({@code -1}, {@code 0}, or {@code > 65535}).
 *
 * <p>Package-private, stateless, not a Spring bean — both callers own their own gating
 * ({@code @ConditionalOnProperty}) and error-reporting context; this class only owns the shared
 * predicate.
 */
final class ProviderConfigsValidator {

    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;

    private ProviderConfigsValidator() {
    }

    static void validate(List<ProviderConfig> providerConfigs) {
        if (providerConfigs == null || providerConfigs.isEmpty()) {
            throw new AppSetupException(
                "app.email.smtp.provider-configs must not be empty when app.email.transport=smtp");
        }
        for (int i = 0; i < providerConfigs.size(); i++) {
            validateOne(providerConfigs.get(i), i);
        }
    }

    private static void validateOne(ProviderConfig providerConfig, int index) {
        String prefix = "app.email.smtp.provider-configs[" + index + "]";

        if (isBlank(providerConfig.getHost())) {
            throw new AppSetupException(prefix + ".host must not be blank when app.email.transport=smtp");
        }
        if (isBlank(providerConfig.getUsername())) {
            throw new AppSetupException(prefix + ".username must not be blank when app.email.transport=smtp");
        }
        // skillars-deferred-110 code review 2026-09-14 (owner decision D-2): a blank password is a
        // genuinely broken authenticated config (every send fails) and must fail fast the same way
        // a blank username does — this codebase has no MailHog/MailPit/smtp4dev no-auth-relay
        // service in any of its four compose files, so "local no-auth relay" is not a legitimate
        // exemption. `username` is also the From address itself (SmtpEmailSender.java,
        // `helper.setFrom(javaMailSender.getUsername())`), so it was already unconditionally
        // required regardless of AUTH.
        if (isBlank(providerConfig.getPassword())) {
            throw new AppSetupException(prefix + ".password must not be blank when app.email.transport=smtp");
        }
        if (isBlank(providerConfig.getPort())) {
            throw new AppSetupException(prefix + ".port must not be blank when app.email.transport=smtp");
        }

        int port;
        try {
            port = Integer.parseInt(providerConfig.getPort());
        } catch (NumberFormatException ex) {
            throw new AppSetupException(
                prefix + ".port is not a valid port number: '" + providerConfig.getPort() + "'");
        }
        // skillars-deferred-110 code review 2026-09-14 (patch): parseInt alone accepts -1, 0, and
        // anything above 65535 — all syntactically numeric, none a usable TCP port.
        if (port < MIN_PORT || port > MAX_PORT) {
            throw new AppSetupException(
                prefix + ".port must be between " + MIN_PORT + " and " + MAX_PORT + " (got: " + port + ")");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
