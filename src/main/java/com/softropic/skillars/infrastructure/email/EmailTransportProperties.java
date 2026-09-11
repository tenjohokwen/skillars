package com.softropic.skillars.infrastructure.email;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds {@code app.email.*}. {@code transport} selects which {@link OutboundEmailSender}
 * implementation is wired (see {@code EmailTransportPropertyValidator} for the fail-fast rules
 * around it). {@code log.outbox-dir} is consumed only by {@code LoggingEmailSender}, gated on
 * {@code transport=log}, but is bound here — under {@code app.email.log} — rather than left to
 * Spring's default "ignore unknown fields" behaviour.
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
        private String outboxDir;

        public String getOutboxDir() {
            return outboxDir;
        }

        public void setOutboxDir(String outboxDir) {
            this.outboxDir = outboxDir;
        }
    }
}
