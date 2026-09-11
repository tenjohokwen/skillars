package com.softropic.skillars.platform.security.infrastructure.listener;

import com.softropic.skillars.infrastructure.email.EmailTransportTransientException;
import com.softropic.skillars.infrastructure.email.OutboundEmailRequest;
import com.softropic.skillars.infrastructure.email.OutboundEmailSender;
import com.softropic.skillars.platform.security.contract.event.PlayerOtpEmailEvent;
import com.softropic.skillars.platform.security.contract.event.PlayerVerificationEmailEvent;

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

/**
 * Story ses-1.1 AC11 — see {@link CoachRegistrationEmailListenerTest} for the shared rationale.
 * {@code onVerificationEmail}'s extra structured-log block (first name, lang key) is preserved
 * verbatim in place — this test doesn't assert on the log line itself, only that the send still
 * happens exactly once around it.
 */
class PlayerRegistrationEmailListenerTest {

    private static final Pattern UUID_PATTERN = Pattern.compile(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private OutboundEmailSender outboundEmailSender;
    private SpringTemplateEngine templateEngine;
    private MessageSource messageSource;
    private PlayerRegistrationEmailListener listener;

    @BeforeEach
    void setUp() {
        outboundEmailSender = mock(OutboundEmailSender.class);
        templateEngine = mock(SpringTemplateEngine.class);
        messageSource = mock(MessageSource.class);
        listener = new PlayerRegistrationEmailListener(outboundEmailSender, templateEngine, messageSource);

        when(templateEngine.process(anyString(), any())).thenReturn("<html>rendered</html>");
        when(messageSource.getMessage(anyString(), any(), any())).thenReturn("Subject line");
    }

    @Test
    void onVerificationEmail_happyPath_rendersAndSendsExactlyOnce() {
        PlayerVerificationEmailEvent event = new PlayerVerificationEmailEvent(
            "player@example.com", "https://verify", "en", "Sam");

        listener.onVerificationEmail(event);

        var captor = forClass(OutboundEmailRequest.class);
        verify(outboundEmailSender).send(captor.capture());
        OutboundEmailRequest sent = captor.getValue();
        assertThat(sent.toAddress()).isEqualTo("player@example.com");
        assertThat(UUID_PATTERN.matcher(sent.correlationId()).matches()).isTrue();
    }

    @Test
    void onOtpEmail_happyPath_rendersAndSends() {
        PlayerOtpEmailEvent event = new PlayerOtpEmailEvent("player@example.com", "123456", "en", "Sam");

        listener.onOtpEmail(event);

        verify(outboundEmailSender).send(any(OutboundEmailRequest.class));
    }

    @Test
    void onVerificationEmail_blankSubject_caughtByWidenedCatch_doesNotPropagate() {
        when(messageSource.getMessage(anyString(), any(), any())).thenReturn("");
        PlayerVerificationEmailEvent event = new PlayerVerificationEmailEvent(
            "player@example.com", "https://verify", "en", "Sam");

        assertThatCode(() -> listener.onVerificationEmail(event)).doesNotThrowAnyException();

        verifyNoInteractions(outboundEmailSender);
    }

    @Test
    void onOtpEmail_blankToAddress_caughtByWidenedCatch_doesNotPropagate() {
        PlayerOtpEmailEvent event = new PlayerOtpEmailEvent("", "123456", "en", "Sam");

        assertThatCode(() -> listener.onOtpEmail(event)).doesNotThrowAnyException();

        verifyNoInteractions(outboundEmailSender);
    }

    @Test
    void onVerificationEmail_transportException_caughtByWidenedCatch_doesNotPropagate() {
        when(outboundEmailSender.send(any(OutboundEmailRequest.class)))
            .thenThrow(new EmailTransportTransientException("boom"));
        PlayerVerificationEmailEvent event = new PlayerVerificationEmailEvent(
            "player@example.com", "https://verify", "en", "Sam");

        assertThatCode(() -> listener.onVerificationEmail(event)).doesNotThrowAnyException();
    }
}
