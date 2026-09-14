package com.softropic.skillars.infrastructure.email;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds {@code app.email.*}. {@code transport} selects which {@link OutboundEmailSender}
 * implementation is wired (see {@code EmailTransportPropertyValidator} for the fail-fast rules
 * around it). {@code log.dump-dir} is consumed only by {@code LoggingEmailSender}, gated on
 * {@code transport=log}, but is declared here as a real, named field — under {@code app.email.log}
 * — so it actually binds to something and is discoverable via config metadata/IDE completion,
 * rather than merely typing a YAML key that happens to work by convention.
 *
 * <p><strong>Not a validation net.</strong> {@code @ConfigurationProperties}'
 * {@code ignoreUnknownFields} defaults to {@code true} in this codebase (unchanged from Spring
 * Boot's own default) — an unrecognised key under {@code app.email.*} is silently accepted and
 * simply never bound to anything, not rejected. Declaring this field does not, by itself, catch a
 * stray old property name (e.g. a leftover {@code outbox-dir} missed by a rename); that
 * completeness gate is a repo-wide grep (see {@code skillars-deferred-110} AC10's own Dev Agent
 * Record), not this binder.
 *
 * <p>skillars-deferred-110 AC10: renamed from {@code outbox-dir}/{@code outboxDir} — the old name
 * collided with {@code platform.outbox}'s real transactional outbox table, giving anyone grepping
 * "outbox" while investigating a stuck transactional-outbox row a false lead into this unrelated,
 * dev-only-transport setting.
 */
@Component
@ConfigurationProperties(prefix = "app.email")
public class EmailTransportProperties {

    private EmailTransport transport;
    private final Log log = new Log();

    public EmailTransport getTransport() {
        return transport;
    }

    public void setTransport(EmailTransport transport) {
        this.transport = transport;
    }

    public Log getLog() {
        return log;
    }

    public static class Log {
        private String dumpDir;

        public String getDumpDir() {
            return dumpDir;
        }

        public void setDumpDir(String dumpDir) {
            this.dumpDir = dumpDir;
        }
    }
}
