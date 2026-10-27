package com.softropic.skillars.platform.notification.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.notification.contract.AlertFiredEvent;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Envelope;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * skillars-deferred-161 AC1: the EMAIL alert recipient comes from {@code platform.admin_alert_email}
 * (not a hardcoded, un-deliverable mailbox), a blank value sends nothing, and nothing the listener
 * does may escape into {@code AlertEvaluationService.evaluate()}'s rule loop.
 *
 * <p>skillars-deferred-164 AC12: the hardening tests below (throttling, shape validation, the
 * config-vs-transport error distinction) still describe a listener that is unreachable in
 * production today — {@code AlertEvaluationService.computeMetricValue} returns {@code -1.0}
 * unconditionally, so no {@link AlertFiredEvent} is ever published outside this test.
 */
class AlertNotificationListenerTest {

    private static final String KEY = "platform.admin_alert_email";
    private static final long DEFAULT_COOLDOWN_SECONDS = 300L;

    private final MailManager mailManager = mock(MailManager.class);
    private final ConfigService configService = mock(ConfigService.class);
    private final AlertNotificationListener listener = new AlertNotificationListener(
        mailManager, configService, Clock.systemUTC(), DEFAULT_COOLDOWN_SECONDS);

    private static AlertFiredEvent emailEvent() {
        return new AlertFiredEvent("FAILURE_RATE", 0.9, 0.5, "EMAIL");
    }

    @Test
    @DisplayName("EMAIL alert is addressed to the configured platform.admin_alert_email")
    void emailChannel_sendsToConfiguredRecipient() {
        when(configService.find(KEY)).thenReturn(Optional.of("ops@example.com"));

        listener.onAlertFired(emailEvent());

        ArgumentCaptor<Envelope> captor = ArgumentCaptor.forClass(Envelope.class);
        verify(mailManager).sendEmailSync(captor.capture());
        assertThat(captor.getValue().recipients()).singleElement()
            .satisfies(r -> assertThat(r.getEmail()).isEqualTo("ops@example.com"));
        assertThat(captor.getValue().emailTemplate()).isEqualTo(EmailTemplate.NONE);
        assertThat(captor.getValue().data())
            .containsEntry("subject", "Skillars Alert: FAILURE_RATE threshold breached")
            // same default-locale String.format as production, so the decimal separator matches either way
            .containsEntry("body", String.format("Metric FAILURE_RATE actual value %.4f exceeded threshold %.4f", 0.9, 0.5));
    }

    @Test
    @DisplayName("channel match is case-insensitive")
    void lowercaseEmailChannel_stillSends() {
        when(configService.find(KEY)).thenReturn(Optional.of("ops@example.com"));

        listener.onAlertFired(new AlertFiredEvent("FAILURE_RATE", 0.9, 0.5, "email"));

        verify(mailManager).sendEmailSync(any(Envelope.class));
    }

    @Test
    @DisplayName("blank platform.admin_alert_email sends nothing and does not throw")
    void emailChannel_blankRecipient_sendsNothing() {
        when(configService.find(KEY)).thenReturn(Optional.of("  "));

        assertThatCode(() -> listener.onAlertFired(emailEvent())).doesNotThrowAnyException();

        verifyNoInteractions(mailManager);
    }

    @Test
    @DisplayName("absent platform.admin_alert_email sends nothing and does not throw")
    void emailChannel_absentRecipient_sendsNothing() {
        when(configService.find(KEY)).thenReturn(Optional.empty());

        assertThatCode(() -> listener.onAlertFired(emailEvent())).doesNotThrowAnyException();

        verifyNoInteractions(mailManager);
    }

    @Test
    @DisplayName("skillars-deferred-164 AC12(b): a value with surrounding whitespace is trimmed before use")
    void emailChannel_recipientWithSurroundingWhitespace_isTrimmedBeforeSend() {
        when(configService.find(KEY)).thenReturn(Optional.of("  ops@example.com  "));

        listener.onAlertFired(emailEvent());

        ArgumentCaptor<Envelope> captor = ArgumentCaptor.forClass(Envelope.class);
        verify(mailManager).sendEmailSync(captor.capture());
        assertThat(captor.getValue().recipients()).singleElement()
            .satisfies(r -> assertThat(r.getEmail()).isEqualTo("ops@example.com"));
    }

    @Test
    @DisplayName("skillars-deferred-164 AC12(b): a value with no '@' is treated the same as blank — no send")
    void emailChannel_invalidEmailShape_sendsNothing() {
        when(configService.find(KEY)).thenReturn(Optional.of("not-an-email"));

        ListAppender<ILoggingEvent> logCapture = captureListenerLogs();
        try {
            assertThatCode(() -> listener.onAlertFired(emailEvent())).doesNotThrowAnyException();
        } finally {
            listenerLogger().detachAppender(logCapture);
        }

        verifyNoInteractions(mailManager);
        // AC12(b): invalid shape takes the same ERROR-logged no-send path as blank.
        assertThat(logCapture.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(structuredArgs(event)).contains("status=EMAIL_NO_RECIPIENT");
        });
    }

    @Test
    @DisplayName("a ConfigService failure (stale-cache refresh) does not escape into the evaluation loop")
    void emailChannel_configLookupThrows_isContained() {
        when(configService.find(KEY)).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> listener.onAlertFired(emailEvent())).doesNotThrowAnyException();

        verifyNoInteractions(mailManager);
    }

    @Test
    @DisplayName("skillars-deferred-164 AC12(c): a config lookup failure logs EMAIL_CONFIG_ERROR, not EMAIL_ERROR")
    void emailChannel_configLookupThrows_logsDistinctConfigErrorStatus() {
        when(configService.find(KEY)).thenThrow(new IllegalStateException("db down"));

        Logger listenerLogger = (Logger) LoggerFactory.getLogger(AlertNotificationListener.class);
        ListAppender<ILoggingEvent> logCapture = new ListAppender<>();
        logCapture.start();
        listenerLogger.addAppender(logCapture);
        try {
            listener.onAlertFired(emailEvent());
        } finally {
            listenerLogger.detachAppender(logCapture);
        }

        assertThat(logCapture.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).contains("Failed to look up platform.admin_alert_email");
            // The status is a kv(...) structured argument, never part of the formatted message.
            assertThat(structuredArgs(event)).contains("status=EMAIL_CONFIG_ERROR");
        });
        // The config-error path must never be logged under the mail-transport-failure status.
        assertThat(logCapture.list).noneSatisfy(event ->
            assertThat(structuredArgs(event)).contains("status=EMAIL_ERROR"));
        assertThat(logCapture.list).noneSatisfy(event ->
            assertThat(event.getFormattedMessage()).contains("Failed to send alert email"));
    }

    private static Logger listenerLogger() {
        return (Logger) LoggerFactory.getLogger(AlertNotificationListener.class);
    }

    private static ListAppender<ILoggingEvent> captureListenerLogs() {
        ListAppender<ILoggingEvent> logCapture = new ListAppender<>();
        logCapture.start();
        listenerLogger().addAppender(logCapture);
        return logCapture;
    }

    private static List<String> structuredArgs(ILoggingEvent event) {
        Object[] args = event.getArgumentArray();
        return args == null ? List.of() : Arrays.stream(args).map(String::valueOf).toList();
    }

    @Test
    @DisplayName("a MailManager failure does not escape into the evaluation loop")
    void emailChannel_mailManagerThrows_isContained() {
        when(configService.find(KEY)).thenReturn(Optional.of("ops@example.com"));
        doThrow(new IllegalStateException("smtp down")).when(mailManager).sendEmailSync(any(Envelope.class));

        assertThatCode(() -> listener.onAlertFired(emailEvent())).doesNotThrowAnyException();

        // the send was actually attempted: an early return would also "not throw"
        verify(mailManager).sendEmailSync(any(Envelope.class));
    }

    @Test
    @DisplayName("LOG channel never touches ConfigService or MailManager")
    void logChannel_doesNothingBeyondLogging() {
        listener.onAlertFired(new AlertFiredEvent("FAILURE_RATE", 0.9, 0.5, "LOG"));

        verifyNoInteractions(mailManager, configService);
    }

    // ---- skillars-deferred-164 AC12(a): no-recipient ERROR log throttling ----

    @Test
    @DisplayName("a second blank-recipient alert within the cooldown window logs ERROR only once")
    void emailChannel_repeatedBlankRecipientWithinCooldown_logsOnlyOnce() {
        when(configService.find(KEY)).thenReturn(Optional.of(""));
        Clock clock = mock(Clock.class);
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        // Fires twice, 10s apart — well inside the default 300s cooldown.
        when(clock.instant()).thenReturn(t0, t0.plusSeconds(10));
        AlertNotificationListener throttledListener =
            new AlertNotificationListener(mailManager, configService, clock, DEFAULT_COOLDOWN_SECONDS);

        Logger listenerLogger = (Logger) LoggerFactory.getLogger(AlertNotificationListener.class);
        ListAppender<ILoggingEvent> logCapture = new ListAppender<>();
        logCapture.start();
        listenerLogger.addAppender(logCapture);
        try {
            throttledListener.onAlertFired(emailEvent());
            throttledListener.onAlertFired(emailEvent());
        } finally {
            listenerLogger.detachAppender(logCapture);
        }

        long noRecipientErrorCount = logCapture.list.stream()
            .filter(e -> e.getLevel() == Level.ERROR)
            .filter(e -> e.getFormattedMessage().contains("alert email NOT sent"))
            .filter(e -> e.getFormattedMessage().contains("blank or not a plausible email address"))
            .count();
        assertThat(noRecipientErrorCount).as("the second alert within the cooldown must not log again").isEqualTo(1);
    }

    @Test
    @DisplayName("a second blank-recipient alert AFTER the cooldown window logs ERROR again")
    void emailChannel_repeatedBlankRecipientAfterCooldown_logsAgain() {
        when(configService.find(KEY)).thenReturn(Optional.of(""));
        Clock clock = mock(Clock.class);
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        // Fires twice, 301s apart — just past the default 300s cooldown.
        when(clock.instant()).thenReturn(t0, t0.plusSeconds(301));
        AlertNotificationListener throttledListener =
            new AlertNotificationListener(mailManager, configService, clock, DEFAULT_COOLDOWN_SECONDS);

        Logger listenerLogger = (Logger) LoggerFactory.getLogger(AlertNotificationListener.class);
        ListAppender<ILoggingEvent> logCapture = new ListAppender<>();
        logCapture.start();
        listenerLogger.addAppender(logCapture);
        try {
            throttledListener.onAlertFired(emailEvent());
            throttledListener.onAlertFired(emailEvent());
        } finally {
            listenerLogger.detachAppender(logCapture);
        }

        long noRecipientErrorCount = logCapture.list.stream()
            .filter(e -> e.getLevel() == Level.ERROR)
            .filter(e -> e.getFormattedMessage().contains("alert email NOT sent"))
            .filter(e -> e.getFormattedMessage().contains("blank or not a plausible email address"))
            .count();
        assertThat(noRecipientErrorCount).as("a repeat past the cooldown must log again").isEqualTo(2);
    }

    // ---- skillars-deferred-165 AC11: EMAIL_CONFIG_ERROR log throttling, mirroring AC12(a) above ----

    @Test
    @DisplayName("a second config-lookup failure within the cooldown window logs EMAIL_CONFIG_ERROR only once")
    void emailChannel_repeatedConfigLookupFailureWithinCooldown_logsOnlyOnce() {
        when(configService.find(KEY)).thenThrow(new IllegalStateException("db down"));
        Clock clock = mock(Clock.class);
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        // Fires twice, 10s apart — well inside the default 300s cooldown.
        when(clock.instant()).thenReturn(t0, t0.plusSeconds(10));
        AlertNotificationListener throttledListener =
            new AlertNotificationListener(mailManager, configService, clock, DEFAULT_COOLDOWN_SECONDS);

        ListAppender<ILoggingEvent> logCapture = captureListenerLogs();
        try {
            throttledListener.onAlertFired(emailEvent());
            throttledListener.onAlertFired(emailEvent());
        } finally {
            listenerLogger().detachAppender(logCapture);
        }

        long configErrorCount = logCapture.list.stream()
            .filter(e -> e.getLevel() == Level.ERROR)
            .filter(e -> structuredArgs(e).contains("status=EMAIL_CONFIG_ERROR"))
            .count();
        assertThat(configErrorCount).as("the second failure within the cooldown must not log again").isEqualTo(1);
        verifyNoInteractions(mailManager);
    }

    @Test
    @DisplayName("a second config-lookup failure AFTER the cooldown window logs EMAIL_CONFIG_ERROR again")
    void emailChannel_repeatedConfigLookupFailureAfterCooldown_logsAgain() {
        when(configService.find(KEY)).thenThrow(new IllegalStateException("db down"));
        Clock clock = mock(Clock.class);
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        // Fires twice, 301s apart — just past the default 300s cooldown.
        when(clock.instant()).thenReturn(t0, t0.plusSeconds(301));
        AlertNotificationListener throttledListener =
            new AlertNotificationListener(mailManager, configService, clock, DEFAULT_COOLDOWN_SECONDS);

        ListAppender<ILoggingEvent> logCapture = captureListenerLogs();
        try {
            throttledListener.onAlertFired(emailEvent());
            throttledListener.onAlertFired(emailEvent());
        } finally {
            listenerLogger().detachAppender(logCapture);
        }

        long configErrorCount = logCapture.list.stream()
            .filter(e -> e.getLevel() == Level.ERROR)
            .filter(e -> structuredArgs(e).contains("status=EMAIL_CONFIG_ERROR"))
            .count();
        assertThat(configErrorCount).as("a repeat past the cooldown must log again").isEqualTo(2);
    }

    @Test
    @DisplayName("the EMAIL_CONFIG_ERROR and EMAIL_NO_RECIPIENT throttles are independent")
    void emailConfigErrorThrottle_doesNotShareCooldownWithNoRecipientThrottle() {
        Clock clock = mock(Clock.class);
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        when(clock.instant()).thenReturn(t0, t0.plusSeconds(10));
        AlertNotificationListener throttledListener =
            new AlertNotificationListener(mailManager, configService, clock, DEFAULT_COOLDOWN_SECONDS);

        // doThrow/doReturn (not when(...).thenX()): re-stubbing with when(mock.find(KEY)) would call
        // the mock a second time to register the new stub, and since the FIRST stub already throws
        // for that exact call, that second registration call would itself throw before Mockito could
        // install the override.
        ListAppender<ILoggingEvent> logCapture = captureListenerLogs();
        try {
            // First alert: config lookup fails outright.
            doThrow(new IllegalStateException("db down")).when(configService).find(KEY);
            throttledListener.onAlertFired(emailEvent());
            // Second alert, 10s later (still within either cooldown): config lookup now succeeds
            // but returns blank — a DIFFERENT failure mode, so it must still log its own ERROR.
            doReturn(Optional.of("")).when(configService).find(KEY);
            throttledListener.onAlertFired(emailEvent());
        } finally {
            listenerLogger().detachAppender(logCapture);
        }

        assertThat(logCapture.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(structuredArgs(event)).contains("status=EMAIL_CONFIG_ERROR");
        });
        assertThat(logCapture.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(structuredArgs(event)).contains("status=EMAIL_NO_RECIPIENT");
        });
    }
}
