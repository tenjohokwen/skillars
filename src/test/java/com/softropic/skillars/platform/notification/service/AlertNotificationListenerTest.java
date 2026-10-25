package com.softropic.skillars.platform.notification.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.notification.contract.AlertFiredEvent;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Envelope;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * skillars-deferred-161 AC1: the EMAIL alert recipient comes from {@code platform.admin_alert_email}
 * (not a hardcoded, un-deliverable mailbox), a blank value sends nothing, and nothing the listener
 * does may escape into {@code AlertEvaluationService.evaluate()}'s rule loop.
 */
class AlertNotificationListenerTest {

    private static final String KEY = "platform.admin_alert_email";

    private final MailManager mailManager = mock(MailManager.class);
    private final ConfigService configService = mock(ConfigService.class);
    private final AlertNotificationListener listener = new AlertNotificationListener(mailManager, configService);

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
    @DisplayName("a ConfigService failure (stale-cache refresh) does not escape into the evaluation loop")
    void emailChannel_configLookupThrows_isContained() {
        when(configService.find(KEY)).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> listener.onAlertFired(emailEvent())).doesNotThrowAnyException();

        verifyNoInteractions(mailManager);
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
}
