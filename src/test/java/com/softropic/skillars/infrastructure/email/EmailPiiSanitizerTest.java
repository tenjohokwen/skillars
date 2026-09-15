package com.softropic.skillars.infrastructure.email;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-111 AC11 (owner decision: full sanitizer, applied to both logs and the
 * persisted record).
 */
@DisplayName("EmailPiiSanitizer")
class EmailPiiSanitizerTest {

    @Test
    @DisplayName("masks a single embedded address, keeping the domain and surrounding text intact")
    void masksSingleEmbeddedAddress() {
        String result = EmailPiiSanitizer.sanitize("550 5.1.1 <jane.doe@example.com> No such user");

        assertThat(result)
            .contains("j***@example.com")
            .doesNotContain("jane.doe@example.com")
            .startsWith("550 5.1.1 <")
            .endsWith("> No such user");
    }

    @Test
    @DisplayName("masks multiple distinct addresses independently within the same text")
    void masksMultipleAddressesIndependently() {
        String result = EmailPiiSanitizer.sanitize(
            "Invalid Addresses: valid=alice@example.com invalid=bob@other.org");

        assertThat(result)
            .contains("a***@example.com")
            .contains("b***@other.org")
            .doesNotContain("alice@example.com")
            .doesNotContain("bob@other.org");
    }

    @Test
    @DisplayName("a multi-line stack-trace-shaped string has every embedded address masked, line by line")
    void masksAcrossMultipleLines() {
        String stackTrace = "org.springframework.mail.MailSendException: Failed messages: "
            + "jakarta.mail.SendFailedException: no-such-user@example.com\n"
            + "\tat org.eclipse.angus.mail.smtp.SMTPTransport.rcptTo(SMTPTransport.java:2097)\n"
            + "Caused by: jakarta.mail.SendFailedException: no-such-user@example.com\n";

        String result = EmailPiiSanitizer.sanitize(stackTrace);

        assertThat(result)
            .doesNotContain("no-such-user@example.com")
            .contains("n***@example.com")
            .contains("SMTPTransport.rcptTo(SMTPTransport.java:2097)");
    }

    @Test
    @DisplayName("text with no address is returned unchanged")
    void noAddress_returnsTextUnchanged() {
        assertThat(EmailPiiSanitizer.sanitize("connection reset")).isEqualTo("connection reset");
    }

    @Test
    @DisplayName("null and blank input pass through unchanged")
    void nullAndBlank_passThroughUnchanged() {
        assertThat(EmailPiiSanitizer.sanitize(null)).isNull();
        assertThat(EmailPiiSanitizer.sanitize("")).isEqualTo("");
    }

    @Test
    @DisplayName("maskAddress matches LoggingEmailSender's own masking shape: first char + *** + @domain")
    void maskAddress_matchesEstablishedShape() {
        assertThat(EmailPiiSanitizer.maskAddress("jane.doe@example.com")).isEqualTo("j***@example.com");
        assertThat(EmailPiiSanitizer.maskAddress("a@b.com")).isEqualTo("a***@b.com");
        assertThat(EmailPiiSanitizer.maskAddress("@b.com")).isEqualTo("***");
        assertThat(EmailPiiSanitizer.maskAddress("no-at-sign")).isEqualTo("***");
    }
}
