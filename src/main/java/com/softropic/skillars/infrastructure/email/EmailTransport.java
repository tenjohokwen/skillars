package com.softropic.skillars.infrastructure.email;

/**
 * The set of transports {@code app.email.transport} can select.
 *
 * <p>All three values exist from Phase 1 so that Phase 2 only has to flip
 * {@code EmailTransportPropertyValidator}'s accepted set and add a bean for {@link #SMTP} — it does
 * not need to touch this selector type. In this phase, {@code SMTP} is syntactically valid but is
 * deliberately rejected by the validator (no bean implements {@link OutboundEmailSender} for it
 * yet) — see that class's javadoc.
 */
public enum EmailTransport {
    SES,
    SMTP,
    LOG
}
