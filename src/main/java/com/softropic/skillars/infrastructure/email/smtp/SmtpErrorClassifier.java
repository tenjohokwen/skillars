package com.softropic.skillars.infrastructure.email.smtp;

import com.softropic.skillars.infrastructure.email.EmailTransportException;
import com.softropic.skillars.infrastructure.email.EmailTransportPermanentException;
import com.softropic.skillars.infrastructure.email.EmailTransportTransientException;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.ParseException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Story ses-1.2 AC2: preserves {@code MailManager.NON_REPAIRABLE_ERRORS}'s exact classification —
 * {@link MailParseException}, {@link MailPreparationException}, {@link AddressException},
 * {@link ParseException} → {@link EmailTransportPermanentException}; every other SMTP failure →
 * {@link EmailTransportTransientException} — relocated to where the SMTP failure actually
 * originates. This is a pure relocation: SMTP retry semantics for dev/uat must not change.
 */
@Component
public class SmtpErrorClassifier {

    private static final List<Class<? extends Exception>> NON_REPAIRABLE_ERRORS = List.of(
        MailParseException.class, MailPreparationException.class, AddressException.class, ParseException.class);

    /**
     * Classifies any exception raised while building/sending the SMTP message. Walks the same two
     * levels of cause {@code MailManager.isRetryable} used to walk (direct, cause, cause-of-cause) —
     * the known wrapping depths a JavaMail/Spring-mail failure can arrive at (e.g. a checked {@code
     * MessagingException} carrying an {@link AddressException} as its cause).
     */
    public EmailTransportException classify(Exception ex) {
        Throwable direct = ex;
        Throwable cause = ex.getCause();
        Throwable causeOfCause = cause != null ? cause.getCause() : null;

        boolean permanent = Stream.of(direct, cause, causeOfCause)
            .filter(Objects::nonNull)
            .anyMatch(t -> NON_REPAIRABLE_ERRORS.stream().anyMatch(c -> c.isInstance(t)));

        return permanent
            ? new EmailTransportPermanentException("SMTP send failed permanently: " + ex.getMessage(), ex)
            : new EmailTransportTransientException("SMTP send failed transiently: " + ex.getMessage(), ex);
    }
}
