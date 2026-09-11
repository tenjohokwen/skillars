package com.softropic.skillars.infrastructure.email.smtp;

import com.softropic.skillars.infrastructure.email.EmailTransportException;
import com.softropic.skillars.infrastructure.email.EmailTransportTransientException;
import com.softropic.skillars.infrastructure.email.OutboundEmailRequest;
import com.softropic.skillars.infrastructure.email.OutboundEmailResult;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Story ses-1.2 AC2 — {@link SmtpEmailSender} folds in {@code MailService.sendEmail}'s old
 * {@code MimeMessage}/{@code MimeMessageHelper} construction and the round-robin provider pick,
 * behind the {@link com.softropic.skillars.infrastructure.email.OutboundEmailSender} port.
 */
@DisplayName("SmtpEmailSender")
class SmtpEmailSenderTest {

    @Mock
    private MailSenderProvider mailSenderProvider;

    @Mock
    private SmtpErrorClassifier errorClassifier;

    private CapturingJavaMailSender sender;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        sender = new CapturingJavaMailSender();
        sender.setUsername("noreply@example.com");
        when(mailSenderProvider.nextSender()).thenReturn(sender);
    }

    /** Stands in for a real SMTP provider: captures the MimeMessage instead of connecting out. */
    private static class CapturingJavaMailSender extends JavaMailSenderImpl {
        private MimeMessage captured;
        private RuntimeException sendFailure;

        @Override
        public void send(MimeMessage mimeMessage) {
            if (sendFailure != null) {
                throw sendFailure;
            }
            try {
                // The real JavaMailSenderImpl.doSend calls saveChanges() before Transport.send() —
                // without it, MimeMessage's Content-Type header is never finalized from the
                // DataHandler MimeMessageHelper.setText populated, and getContentType()/getContent()
                // below would read stale pre-setText state.
                mimeMessage.saveChanges();
            } catch (MessagingException ex) {
                throw new RuntimeException(ex);
            }
            this.captured = mimeMessage;
        }
    }

    private SmtpEmailSender sender() {
        return new SmtpEmailSender(mailSenderProvider, errorClassifier);
    }

    @Test
    @DisplayName("an html-body request sends isHtml=true with htmlBody as the content")
    void htmlBodyRequest_sendsAsHtml() throws Exception {
        OutboundEmailRequest request = new OutboundEmailRequest(
            "player@example.com", "Subject", "<p>html</p>", null, "corr-1");

        OutboundEmailResult result = sender().send(request);

        assertThat(result.messageId()).isEqualTo("smtp:corr-1");
        MimeMessage captured = sender.captured;
        assertThat(captured).isNotNull();
        assertThat(captured.getSubject()).isEqualTo("Subject");
        assertThat(captured.getContentType()).contains("text/html");
        assertThat((String) captured.getContent()).isEqualTo("<p>html</p>");
        assertThat(captured.getFrom()[0].toString()).contains("noreply@example.com");
    }

    @Test
    @DisplayName("a text-only request (htmlBody absent) sends isHtml=false with textBody as the content")
    void textOnlyRequest_sendsAsPlainText() throws Exception {
        OutboundEmailRequest request = new OutboundEmailRequest(
            "ops@example.com", "Alert", null, "plain text body", "corr-2");

        sender().send(request);

        MimeMessage captured = sender.captured;
        assertThat(captured.getContentType()).contains("text/plain");
        assertThat((String) captured.getContent()).isEqualTo("plain text body");
    }

    @Test
    @DisplayName("htmlBody wins when both htmlBody and textBody are present, matching pre-story behaviour")
    void bothBodiesPresent_htmlWins() throws Exception {
        OutboundEmailRequest request = new OutboundEmailRequest(
            "player@example.com", "Subject", "<p>html</p>", "plain fallback", "corr-3");

        sender().send(request);

        MimeMessage captured = sender.captured;
        assertThat(captured.getContentType()).contains("text/html");
        assertThat((String) captured.getContent()).isEqualTo("<p>html</p>");
    }

    @Test
    @DisplayName("a send failure is routed through SmtpErrorClassifier, and its result is thrown")
    void sendFailure_isClassifiedAndThrown() {
        sender.sendFailure = new org.springframework.mail.MailSendException("smtp auth failed");
        EmailTransportException classified = new EmailTransportTransientException("classified", sender.sendFailure);
        when(errorClassifier.classify(sender.sendFailure)).thenReturn(classified);

        OutboundEmailRequest request = new OutboundEmailRequest(
            "player@example.com", "Subject", "<p>html</p>", null, "corr-4");

        assertThatThrownBy(() -> sender().send(request)).isSameAs(classified);
    }

    @Test
    @DisplayName("a MimeMessageHelper failure (e.g. malformed address) is routed through SmtpErrorClassifier")
    void addressFailure_isClassifiedAndThrown() {
        EmailTransportException classified = new EmailTransportTransientException("classified", new IOException());
        // An empty toAddress is rejected by OutboundEmailRequest itself, so use a value MimeMessageHelper
        // rejects instead — a raw newline is invalid in an RFC822 address header.
        OutboundEmailRequest request = new OutboundEmailRequest(
            "bad\naddress@example.com", "Subject", "<p>html</p>", null, "corr-5");
        when(errorClassifier.classify(org.mockito.ArgumentMatchers.any())).thenReturn(classified);

        assertThatThrownBy(() -> sender().send(request)).isSameAs(classified);
    }
}
