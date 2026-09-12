package com.softropic.skillars.infrastructure.email.smtp;

import com.softropic.skillars.infrastructure.email.EmailTransportException;
import com.softropic.skillars.infrastructure.email.EmailTransportPermanentException;
import com.softropic.skillars.infrastructure.email.EmailTransportTransientException;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.ParseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story ses-1.2 AC2 — {@link SmtpErrorClassifier} must preserve {@code
 * MailManager.NON_REPAIRABLE_ERRORS}'s exact pre-story classification: {@link MailParseException},
 * {@link MailPreparationException}, {@link AddressException}, {@link ParseException} → permanent;
 * everything else → transient. This is a pure relocation, so these cases mirror the ones {@code
 * MailManagerResilienceTest} used to pin directly.
 */
@DisplayName("SmtpErrorClassifier")
class SmtpErrorClassifierTest {

    private final SmtpErrorClassifier classifier = new SmtpErrorClassifier();

    @Test
    @DisplayName("a direct MailParseException classifies as permanent")
    void directMailParseException_isPermanent() {
        EmailTransportException result = classifier.classify(new MailParseException("bad template"));

        assertThat(result).isInstanceOf(EmailTransportPermanentException.class);
    }

    @Test
    @DisplayName("a direct MailPreparationException classifies as permanent")
    void directMailPreparationException_isPermanent() {
        EmailTransportException result = classifier.classify(new MailPreparationException("bad prep"));

        assertThat(result).isInstanceOf(EmailTransportPermanentException.class);
    }

    @Test
    @DisplayName("a MessagingException wrapping an AddressException classifies as permanent")
    void wrappedAddressException_isPermanent() {
        MessagingException wrapped = new MessagingException("bad recipient", new AddressException("not-an-email"));

        EmailTransportException result = classifier.classify(wrapped);

        assertThat(result).isInstanceOf(EmailTransportPermanentException.class);
    }

    @Test
    @DisplayName("a MessagingException wrapping a jakarta.mail.internet.ParseException classifies as permanent")
    void wrappedJakartaParseException_isPermanent() {
        MessagingException wrapped = new MessagingException("malformed header", new ParseException("bad header"));

        EmailTransportException result = classifier.classify(wrapped);

        assertThat(result).isInstanceOf(EmailTransportPermanentException.class);
    }

    @Test
    @DisplayName("a bare MessagingException (e.g. connection timeout) classifies as transient")
    void bareMessagingException_isTransient() {
        EmailTransportException result = classifier.classify(new MessagingException("Connection timed out"));

        assertThat(result).isInstanceOf(EmailTransportTransientException.class);
    }

    @Test
    @DisplayName("the classified exception carries the original as its cause")
    void classifiedException_carriesOriginalAsCause() {
        MessagingException original = new MessagingException("Connection timed out");

        EmailTransportException result = classifier.classify(original);

        assertThat(result.getCause()).isSameAs(original);
    }
}
