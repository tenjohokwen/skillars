package com.softropic.skillars.infrastructure.email;

/**
 * The set of transports {@code app.email.transport} can select. Each value has its own
 * {@link OutboundEmailSender}, chosen by {@code @ConditionalOnProperty}:
 *
 * <ul>
 *   <li>{@link #SES} — {@code infrastructure.ses.SesEmailSender} (the {@code prod} profile);</li>
 *   <li>{@link #SMTP} — the SMTP sender in {@code infrastructure.email.smtp} (the {@code uat}
 *       profile; story ses-1.2 made it a working transport);</li>
 *   <li>{@link #LOG} — {@code infrastructure.email.log.LoggingEmailSender} (the base default and
 *       {@code dev}).</li>
 * </ul>
 *
 * <p>{@code EmailTransportPropertyValidator} accepts all three and fails startup on anything else.
 */
public enum EmailTransport {
    SES,
    SMTP,
    LOG
}
