package com.softropic.skillars.platform.security.infrastructure.listener;

import com.softropic.skillars.infrastructure.email.EmailTransportTransientException;
import com.softropic.skillars.infrastructure.email.OutboundEmailRequest;
import com.softropic.skillars.infrastructure.email.OutboundEmailSender;
import com.softropic.skillars.platform.security.contract.event.ParentOtpEmailEvent;
import com.softropic.skillars.platform.security.contract.event.ParentVerificationEmailEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Story ses-1.1 AC11 — see {@link CoachRegistrationEmailListenerTest} for the shared rationale. */
class ParentRegistrationEmailListenerTest {

    private static final Pattern UUID_PATTERN = Pattern.compile(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private OutboundEmailSender outboundEmailSender;
    private SpringTemplateEngine templateEngine;
    private MessageSource messageSource;
    private ParentRegistrationEmailListener listener;

    @BeforeEach
    void setUp() {
        outboundEmailSender = mock(OutboundEmailSender.class);
        templateEngine = mock(SpringTemplateEngine.class);
        messageSource = mock(MessageSource.class);
        listener = new ParentRegistrationEmailListener(outboundEmailSender, templateEngine, messageSource);

        when(templateEngine.process(anyString(), any())).thenReturn("<html>rendered</html>");
        when(messageSource.getMessage(anyString(), any(), any())).thenReturn("Subject line");
    }

    @Test
    void onVerificationEmail_happyPath_rendersAndSends() {
        ParentVerificationEmailEvent event = new ParentVerificationEmailEvent(
            "parent@example.com", "https://verify", "en", "Grace");

        listener.onVerificationEmail(event);

        var captor = forClass(OutboundEmailRequest.class);
        verify(outboundEmailSender).send(captor.capture());
        OutboundEmailRequest sent = captor.getValue();
        assertThat(sent.toAddress()).isEqualTo("parent@example.com");
        assertThat(sent.htmlBody()).isEqualTo("<html>rendered</html>");
        assertThat(UUID_PATTERN.matcher(sent.correlationId()).matches()).isTrue();
    }

    @Test
    void onOtpEmail_happyPath_rendersAndSends() {
        ParentOtpEmailEvent event = new ParentOtpEmailEvent("parent@example.com", "123456", "en", "Grace");

        listener.onOtpEmail(event);

        verify(outboundEmailSender).send(any(OutboundEmailRequest.class));
    }

    @Test
    void onVerificationEmail_blankToAddress_caughtByWidenedCatch_doesNotPropagate() {
        ParentVerificationEmailEvent event = new ParentVerificationEmailEvent(
            "  ", "https://verify", "en", "Grace");

        assertThatCode(() -> listener.onVerificationEmail(event)).doesNotThrowAnyException();

        verifyNoInteractions(outboundEmailSender);
    }

    @Test
    void onOtpEmail_blankToAddress_caughtByWidenedCatch_doesNotPropagate() {
        ParentOtpEmailEvent event = new ParentOtpEmailEvent(null, "123456", "en", "Grace");

        assertThatCode(() -> listener.onOtpEmail(event)).doesNotThrowAnyException();

        verifyNoInteractions(outboundEmailSender);
    }

    @Test
    void onOtpEmail_transportException_caughtByWidenedCatch_doesNotPropagate() {
        when(outboundEmailSender.send(any(OutboundEmailRequest.class)))
            .thenThrow(new EmailTransportTransientException("boom"));
        ParentOtpEmailEvent event = new ParentOtpEmailEvent("parent@example.com", "123456", "en", "Grace");

        assertThatCode(() -> listener.onOtpEmail(event)).doesNotThrowAnyException();
    }
}
