package com.softropic.skillars.platform.security.infrastructure.listener;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.softropic.skillars.infrastructure.web.FrontendLinkBuilder;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.notification.service.NotificationOutboxSupport;
import com.softropic.skillars.platform.security.contract.event.RegistrationDuplicateAttemptEvent;
import com.softropic.skillars.platform.security.contract.event.RegistrationPhoneInUseEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Mirrors {@link ParentRegistrationEmailListenerTest} for the duplicate-registration notice. */
@ExtendWith(MockitoExtension.class)
class RegistrationDuplicateAttemptListenerTest {

    private static final Pattern UUID_PATTERN = Pattern.compile(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    @Mock
    private NotificationOutboxSupport notificationOutboxSupport;

    @Captor
    private ArgumentCaptor<Recipient> recipientCaptor;

    @Captor
    private ArgumentCaptor<Map<String, Object>> dataCaptor;

    @Captor
    private ArgumentCaptor<String> sendIdCaptor;

    private RegistrationDuplicateAttemptListener listener;

    @BeforeEach
    void setUp() {
        // Trailing slash on purpose: baseUrl must be FrontendLinkBuilder.base(), which strips it,
        // exactly what EmailRegistrationStrategy puts in the same template's baseUrl.
        listener = new RegistrationDuplicateAttemptListener(
            notificationOutboxSupport, new FrontendLinkBuilder("https://app.skillars.test/"));
    }

    @Test
    void onDuplicateAttempt_happyPath_enqueuesCreationDupToTheExistingAccount() {
        listener.onDuplicateAttempt(
            new RegistrationDuplicateAttemptEvent(7L, "owner@example.com", "Grace", "Hopper", "de", false));

        verify(notificationOutboxSupport).enqueueEmail(eq(EmailTemplate.CREATION_DUP), recipientCaptor.capture(),
            dataCaptor.capture(), sendIdCaptor.capture());
        Recipient recipient = recipientCaptor.getValue();
        assertThat(recipient.getEmail()).isEqualTo("owner@example.com");
        assertThat(recipient.getFirstname()).isEqualTo("Grace");
        assertThat(recipient.getLastname()).isEqualTo("Hopper");
        assertThat(recipient.getLangKey()).isEqualTo("de");

        assertThat(dataCaptor.getValue()).containsOnlyKeys("baseUrl", "helpCode", "phoneMatch");
        assertThat(dataCaptor.getValue()).containsEntry("phoneMatch", false);
        assertThat(dataCaptor.getValue()).containsEntry("baseUrl", "https://app.skillars.test");
        assertThat(dataCaptor.getValue().get("helpCode")).isInstanceOf(String.class);
        assertThat((String) dataCaptor.getValue().get("helpCode")).isNotBlank();
        assertThat(sendIdCaptor.getValue()).matches(UUID_PATTERN);
    }

    @Test
    void onDuplicateAttempt_phoneMatch_isPassedToTheTemplate() {
        listener.onDuplicateAttempt(
            new RegistrationDuplicateAttemptEvent(7L, "owner@example.com", "Grace", "Hopper", "de", true));

        verify(notificationOutboxSupport).enqueueEmail(eq(EmailTemplate.CREATION_DUP), recipientCaptor.capture(),
            dataCaptor.capture(), sendIdCaptor.capture());
        assertThat(dataCaptor.getValue()).containsEntry("phoneMatch", true);
    }

    @Test
    void onDuplicateAttempt_blankAddress_earlyReturns_doesNotEnqueue() {
        assertThatCode(() -> listener.onDuplicateAttempt(
            new RegistrationDuplicateAttemptEvent(7L, " ", "Grace", "Hopper", "de", false))).doesNotThrowAnyException();
        assertThatCode(() -> listener.onDuplicateAttempt(
            new RegistrationDuplicateAttemptEvent(7L, null, "Grace", "Hopper", "de", false))).doesNotThrowAnyException();

        verifyNoInteractions(notificationOutboxSupport);
    }

    @Test
    void onDuplicateAttempt_enqueueThrows_caughtAndLogged_doesNotPropagate() {
        doThrow(new IllegalStateException("boom")).when(notificationOutboxSupport)
            .enqueueEmail(any(), any(), anyMap(), anyString());

        Logger listenerLogger = (Logger) LoggerFactory.getLogger(RegistrationDuplicateAttemptListener.class);
        ListAppender<ILoggingEvent> logCapture = new ListAppender<>();
        logCapture.start();
        try {
            listenerLogger.addAppender(logCapture);
            assertThatCode(() -> listener.onDuplicateAttempt(
                new RegistrationDuplicateAttemptEvent(7L, "owner@example.com", "Grace", "Hopper", "de", false)))
                .doesNotThrowAnyException();
        } finally {
            listenerLogger.detachAppender(logCapture);
        }

        assertThat(logCapture.list)
            .anySatisfy(e -> assertThat(e.getMessage()).contains("Failed to prepare/publish notification"));
    }

    @Test
    void onPhoneInUse_happyPath_enqueuesPhoneInUseToTheTypedAddress_withoutAName() {
        listener.onPhoneInUse(new RegistrationPhoneInUseEvent("newcomer@example.com", "fr", 8L));

        verify(notificationOutboxSupport).enqueueEmail(eq(EmailTemplate.REGISTRATION_PHONE_IN_USE),
            recipientCaptor.capture(), dataCaptor.capture(), sendIdCaptor.capture());
        Recipient recipient = recipientCaptor.getValue();
        assertThat(recipient.getEmail()).isEqualTo("newcomer@example.com");
        assertThat(recipient.getLangKey()).isEqualTo("fr");
        assertThat(recipient.getFirstname()).isNull();
        assertThat(recipient.getLastname()).isNull();

        assertThat(dataCaptor.getValue()).containsOnlyKeys("baseUrl", "helpCode");
        assertThat(dataCaptor.getValue()).containsEntry("baseUrl", "https://app.skillars.test");
        assertThat((String) dataCaptor.getValue().get("helpCode")).isNotBlank();
        assertThat(sendIdCaptor.getValue()).matches(UUID_PATTERN);
    }

    @Test
    void onPhoneInUse_blankAddress_earlyReturns_doesNotEnqueue() {
        assertThatCode(() -> listener.onPhoneInUse(new RegistrationPhoneInUseEvent(" ", "en", 8L)))
            .doesNotThrowAnyException();
        assertThatCode(() -> listener.onPhoneInUse(new RegistrationPhoneInUseEvent(null, "en", 8L)))
            .doesNotThrowAnyException();

        verifyNoInteractions(notificationOutboxSupport);
    }

    @Test
    void onPhoneInUse_enqueueThrows_caughtAndLogged_doesNotPropagate() {
        doThrow(new IllegalStateException("boom")).when(notificationOutboxSupport)
            .enqueueEmail(any(), any(), anyMap(), anyString());

        Logger listenerLogger = (Logger) LoggerFactory.getLogger(RegistrationDuplicateAttemptListener.class);
        ListAppender<ILoggingEvent> logCapture = new ListAppender<>();
        logCapture.start();
        try {
            listenerLogger.addAppender(logCapture);
            assertThatCode(() -> listener.onPhoneInUse(new RegistrationPhoneInUseEvent("newcomer@example.com", "en", 8L)))
                .doesNotThrowAnyException();
        } finally {
            listenerLogger.detachAppender(logCapture);
        }

        assertThat(logCapture.list)
            .anySatisfy(e -> assertThat(e.getMessage()).contains("Failed to prepare/publish notification"));
    }
}
