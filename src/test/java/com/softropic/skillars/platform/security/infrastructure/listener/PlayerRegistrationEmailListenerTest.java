package com.softropic.skillars.platform.security.infrastructure.listener;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.notification.service.NotificationOutboxSupport;
import com.softropic.skillars.platform.security.contract.event.PlayerOtpEmailEvent;
import com.softropic.skillars.platform.security.contract.event.PlayerVerificationEmailEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Story ses-1.4 AC1/AC6 — see {@link CoachRegistrationEmailListenerTest} for the shared rationale. */
@ExtendWith(MockitoExtension.class)
class PlayerRegistrationEmailListenerTest {

    private static final Pattern UUID_PATTERN = Pattern.compile(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    @Mock
    private NotificationOutboxSupport notificationOutboxSupport;

    @Captor
    private ArgumentCaptor<Recipient> recipientCaptor;

    @Captor
    private ArgumentCaptor<String> sendIdCaptor;

    private PlayerRegistrationEmailListener listener;

    @BeforeEach
    void setUp() {
        listener = new PlayerRegistrationEmailListener(notificationOutboxSupport);
    }

    @Test
    void onVerificationEmail_happyPath_enqueuesWithRecipientAndVerifyUrl() {
        PlayerVerificationEmailEvent event = new PlayerVerificationEmailEvent(
            "player@example.com", "https://verify", "en", "Sam");

        listener.onVerificationEmail(event);

        verify(notificationOutboxSupport).enqueueEmail(
            eq(EmailTemplate.PLAYER_EMAIL_VERIFY), recipientCaptor.capture(), anyMap(), anyString());
        Recipient recipient = recipientCaptor.getValue();
        assertThat(recipient.getEmail()).isEqualTo("player@example.com");
        assertThat(recipient.getLangKey()).isEqualTo("en");
        assertThat(recipient.getFirstname()).isEqualTo("Sam");

        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(notificationOutboxSupport).enqueueEmail(any(), any(), dataCaptor.capture(), anyString());
        assertThat(dataCaptor.getValue()).containsExactly(Map.entry("verifyUrl", "https://verify"));
    }

    @Test
    void onOtpEmail_happyPath_enqueuesWithRecipientAndOtpCode() {
        PlayerOtpEmailEvent event = new PlayerOtpEmailEvent("player@example.com", "123456", "en", "Sam");

        listener.onOtpEmail(event);

        verify(notificationOutboxSupport).enqueueEmail(
            eq(EmailTemplate.PLAYER_OTP), recipientCaptor.capture(), anyMap(), anyString());
        Recipient recipient = recipientCaptor.getValue();
        assertThat(recipient.getLangKey()).isEqualTo("en");
        assertThat(recipient.getFirstname()).isEqualTo("Sam");

        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(notificationOutboxSupport).enqueueEmail(any(), any(), dataCaptor.capture(), anyString());
        assertThat(dataCaptor.getValue()).containsExactly(Map.entry("otpCode", "123456"));
    }

    @Test
    void verificationAndOtpEmails_useDistinctSendIds() {
        listener.onVerificationEmail(new PlayerVerificationEmailEvent("player@example.com", "https://verify", "en", "Sam"));
        listener.onOtpEmail(new PlayerOtpEmailEvent("player@example.com", "123456", "en", "Sam"));

        verify(notificationOutboxSupport, times(2)).enqueueEmail(any(), any(), anyMap(), sendIdCaptor.capture());
        assertThat(sendIdCaptor.getAllValues()).hasSize(2).doesNotHaveDuplicates();
    }

    @Test
    void onVerificationEmail_blankToAddress_earlyReturns_doesNotEnqueue() {
        PlayerVerificationEmailEvent event = new PlayerVerificationEmailEvent(
            "", "https://verify", "en", "Sam");

        assertThatCode(() -> listener.onVerificationEmail(event)).doesNotThrowAnyException();

        verifyNoInteractions(notificationOutboxSupport);
    }

    @Test
    void onOtpEmail_blankToAddress_earlyReturns_doesNotEnqueue() {
        PlayerOtpEmailEvent event = new PlayerOtpEmailEvent("", "123456", "en", "Sam");

        assertThatCode(() -> listener.onOtpEmail(event)).doesNotThrowAnyException();

        verifyNoInteractions(notificationOutboxSupport);
    }

    /**
     * skillars-deferred-111 AC6 — see {@code CoachRegistrationEmailListenerTest} for the full
     * rationale.
     */
    @Test
    void onVerificationEmail_nullVerifyUrl_throwsNpe_doesNotEnqueue() {
        PlayerVerificationEmailEvent event = new PlayerVerificationEmailEvent(
            "player@example.com", null, "en", "Sam");

        assertThatThrownBy(() -> listener.onVerificationEmail(event))
            .isInstanceOf(NullPointerException.class);

        verifyNoInteractions(notificationOutboxSupport);
    }

    @Test
    void onOtpEmail_nullOtp_throwsNpe_doesNotEnqueue() {
        PlayerOtpEmailEvent event = new PlayerOtpEmailEvent("player@example.com", null, "en", "Sam");

        assertThatThrownBy(() -> listener.onOtpEmail(event))
            .isInstanceOf(NullPointerException.class);

        verifyNoInteractions(notificationOutboxSupport);
    }

    @Test
    void onVerificationEmail_enqueueThrows_caughtAndLogged_doesNotPropagate() {
        doThrow(new IllegalStateException("boom")).when(notificationOutboxSupport)
            .enqueueEmail(any(), any(), anyMap(), anyString());
        PlayerVerificationEmailEvent event = new PlayerVerificationEmailEvent(
            "player@example.com", "https://verify", "en", "Sam");

        Logger listenerLogger = (Logger) LoggerFactory.getLogger(PlayerRegistrationEmailListener.class);
        ListAppender<ILoggingEvent> logCapture = new ListAppender<>();
        logCapture.start();
        try {
            listenerLogger.addAppender(logCapture);
            assertThatCode(() -> listener.onVerificationEmail(event)).doesNotThrowAnyException();
        } finally {
            listenerLogger.detachAppender(logCapture);
        }

        assertThat(logCapture.list).anySatisfy(e -> {
            assertThat(e.getMessage()).contains("Failed to prepare/publish notification");
            assertThat(Arrays.stream(e.getArgumentArray()).map(String::valueOf))
                .as("a UUID-shaped sendId must be among the logged structured arguments")
                .anyMatch(arg -> arg.startsWith("sendId=") && UUID_PATTERN.matcher(arg.substring("sendId=".length())).matches());
        });
    }

    @Test
    void onOtpEmail_enqueueThrows_caughtAndLogged_doesNotPropagate() {
        doThrow(new IllegalStateException("boom")).when(notificationOutboxSupport)
            .enqueueEmail(any(), any(), anyMap(), anyString());
        PlayerOtpEmailEvent event = new PlayerOtpEmailEvent("player@example.com", "123456", "en", "Sam");

        Logger listenerLogger = (Logger) LoggerFactory.getLogger(PlayerRegistrationEmailListener.class);
        ListAppender<ILoggingEvent> logCapture = new ListAppender<>();
        logCapture.start();
        try {
            listenerLogger.addAppender(logCapture);
            assertThatCode(() -> listener.onOtpEmail(event)).doesNotThrowAnyException();
        } finally {
            listenerLogger.detachAppender(logCapture);
        }

        assertThat(logCapture.list).anySatisfy(e -> {
            assertThat(e.getMessage()).contains("Failed to prepare/publish notification");
            assertThat(Arrays.stream(e.getArgumentArray()).map(String::valueOf))
                .anyMatch(arg -> arg.startsWith("sendId=") && UUID_PATTERN.matcher(arg.substring("sendId=".length())).matches());
        });
    }
}
