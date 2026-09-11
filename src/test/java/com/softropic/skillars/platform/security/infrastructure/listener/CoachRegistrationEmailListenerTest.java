package com.softropic.skillars.platform.security.infrastructure.listener;

import com.softropic.skillars.infrastructure.email.EmailTransportTransientException;
import com.softropic.skillars.infrastructure.email.OutboundEmailRequest;
import com.softropic.skillars.infrastructure.email.OutboundEmailSender;
import com.softropic.skillars.platform.security.contract.event.CoachOtpEmailEvent;
import com.softropic.skillars.platform.security.contract.event.CoachVerificationEmailEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story ses-1.1 AC11 — the port replaces the field, construction happens inside the try, and the
 * widened catch ({@code EmailTransportException | IllegalArgumentException}) absorbs a blank
 * field without propagating out of the {@code AFTER_COMMIT} listener method.
 */
class CoachRegistrationEmailListenerTest {

    private static final Pattern UUID_PATTERN = Pattern.compile(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private OutboundEmailSender outboundEmailSender;
    private SpringTemplateEngine templateEngine;
    private MessageSource messageSource;
    private CoachRegistrationEmailListener listener;

    @BeforeEach
    void setUp() {
        outboundEmailSender = mock(OutboundEmailSender.class);
        templateEngine = mock(SpringTemplateEngine.class);
        messageSource = mock(MessageSource.class);
        listener = new CoachRegistrationEmailListener(outboundEmailSender, templateEngine, messageSource);

        when(templateEngine.process(anyString(), any())).thenReturn("<html>rendered</html>");
        when(messageSource.getMessage(anyString(), any(), any())).thenReturn("Subject line");
    }

    @Test
    void onVerificationEmail_happyPath_rendersAndSends() {
        CoachVerificationEmailEvent event = new CoachVerificationEmailEvent(
            "coach@example.com", "https://verify", "en", "Ada");

        listener.onVerificationEmail(event);

        var captor = forClass(OutboundEmailRequest.class);
        verify(outboundEmailSender).send(captor.capture());
        OutboundEmailRequest sent = captor.getValue();
        assertThat(sent.toAddress()).isEqualTo("coach@example.com");
        assertThat(sent.subject()).isEqualTo("Subject line");
        assertThat(sent.htmlBody()).isEqualTo("<html>rendered</html>");
        assertThat(sent.textBody()).isNull();
        assertThat(UUID_PATTERN.matcher(sent.correlationId()).matches()).isTrue();
    }

    @Test
    void onOtpEmail_happyPath_rendersAndSends() {
        CoachOtpEmailEvent event = new CoachOtpEmailEvent("coach@example.com", "123456", "en", "Ada");

        listener.onOtpEmail(event);

        verify(outboundEmailSender).send(any(OutboundEmailRequest.class));
    }

    @Test
    void onVerificationEmail_blankToAddress_caughtByWidenedCatch_doesNotPropagate() {
        CoachVerificationEmailEvent event = new CoachVerificationEmailEvent(
            "", "https://verify", "en", "Ada");

        assertThatCode(() -> listener.onVerificationEmail(event)).doesNotThrowAnyException();

        verifyNoInteractions(outboundEmailSender);
    }

    @Test
    void onOtpEmail_blankToAddress_caughtByWidenedCatch_doesNotPropagate() {
        CoachOtpEmailEvent event = new CoachOtpEmailEvent("", "123456", "en", "Ada");

        assertThatCode(() -> listener.onOtpEmail(event)).doesNotThrowAnyException();

        verifyNoInteractions(outboundEmailSender);
    }

    @Test
    void onVerificationEmail_transportException_caughtByWidenedCatch_doesNotPropagate() {
        when(outboundEmailSender.send(any(OutboundEmailRequest.class)))
            .thenThrow(new EmailTransportTransientException("boom"));
        CoachVerificationEmailEvent event = new CoachVerificationEmailEvent(
            "coach@example.com", "https://verify", "en", "Ada");

        assertThatCode(() -> listener.onVerificationEmail(event)).doesNotThrowAnyException();
    }
}
