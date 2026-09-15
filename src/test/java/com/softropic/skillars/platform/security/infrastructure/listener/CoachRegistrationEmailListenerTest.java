package com.softropic.skillars.platform.security.infrastructure.listener;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.notification.service.NotificationOutboxSupport;
import com.softropic.skillars.platform.security.contract.event.CoachOtpEmailEvent;
import com.softropic.skillars.platform.security.contract.event.CoachVerificationEmailEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.Map;

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

/**
 * Story ses-1.4 AC1/AC6 — rewritten from the pre-this-story direct-port-call assertions
 * ({@code OutboundEmailSender.send(...)}) to the outbox-enqueue behavior this story replaces it
 * with, mirroring {@code BookingEmailListenerTest}'s mocking shape per AC6's own instruction.
 */
@ExtendWith(MockitoExtension.class)
class CoachRegistrationEmailListenerTest {

    private static final java.util.regex.Pattern UUID_PATTERN = java.util.regex.Pattern.compile(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    @Mock
    private NotificationOutboxSupport notificationOutboxSupport;

    @Captor
    private ArgumentCaptor<Recipient> recipientCaptor;

    @Captor
    private ArgumentCaptor<String> sendIdCaptor;

    private CoachRegistrationEmailListener listener;

    @BeforeEach
    void setUp() {
        listener = new CoachRegistrationEmailListener(notificationOutboxSupport);
    }

    @Test
    void onVerificationEmail_happyPath_enqueuesWithRecipientAndVerifyUrl() {
        CoachVerificationEmailEvent event = new CoachVerificationEmailEvent(
            "coach@example.com", "https://verify", "en", "Ada");

        listener.onVerificationEmail(event);

        verify(notificationOutboxSupport).enqueueEmail(
            eq(EmailTemplate.COACH_EMAIL_VERIFY), recipientCaptor.capture(), anyMap(), anyString());
        Recipient recipient = recipientCaptor.getValue();
        assertThat(recipient.getEmail()).isEqualTo("coach@example.com");
        assertThat(recipient.getLangKey()).isEqualTo("en");
        assertThat(recipient.getFirstname()).isEqualTo("Ada");

        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(notificationOutboxSupport).enqueueEmail(any(), any(), dataCaptor.capture(), anyString());
        assertThat(dataCaptor.getValue()).containsExactly(Map.entry("verifyUrl", "https://verify"));
    }

    @Test
    void onOtpEmail_happyPath_enqueuesWithRecipientAndOtpCode() {
        CoachOtpEmailEvent event = new CoachOtpEmailEvent("coach@example.com", "123456", "en", "Ada");

        listener.onOtpEmail(event);

        verify(notificationOutboxSupport).enqueueEmail(
            eq(EmailTemplate.COACH_OTP), recipientCaptor.capture(), anyMap(), anyString());
        Recipient recipient = recipientCaptor.getValue();
        assertThat(recipient.getEmail()).isEqualTo("coach@example.com");
        // Code review 2026-09-12: langKey is the one recipient field with i18n consequences
        // (EmailContentRenderer derives the whole Locale from it) — assert it on every happy path,
        // not just the verify-email one.
        assertThat(recipient.getLangKey()).isEqualTo("en");
        assertThat(recipient.getFirstname()).isEqualTo("Ada");

        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(notificationOutboxSupport).enqueueEmail(any(), any(), dataCaptor.capture(), anyString());
        assertThat(dataCaptor.getValue()).containsExactly(Map.entry("otpCode", "123456"));
    }

    /** AC4: each enqueue call gets its own fresh sendId — even across the two methods on this class. */
    @Test
    void verificationAndOtpEmails_useDistinctSendIds() {
        listener.onVerificationEmail(new CoachVerificationEmailEvent("coach@example.com", "https://verify", "en", "Ada"));
        listener.onOtpEmail(new CoachOtpEmailEvent("coach@example.com", "123456", "en", "Ada"));

        verify(notificationOutboxSupport, times(2)).enqueueEmail(any(), any(), anyMap(), sendIdCaptor.capture());
        assertThat(sendIdCaptor.getAllValues()).hasSize(2).doesNotHaveDuplicates();
    }

    /**
     * AC1's retained guard — mirrors {@code BookingEmailListener}'s blank-address early return.
     * Do not drop this and rely on the notification module's own undeliverable-address handling: it
     * would cost a full outbox row + EnvelopeEntity row + a delivery round trip before surfacing with
     * no registration context, where this early return rejects it inline with one.
     */
    @Test
    void onVerificationEmail_blankToAddress_earlyReturns_doesNotEnqueue() {
        CoachVerificationEmailEvent event = new CoachVerificationEmailEvent("", "https://verify", "en", "Ada");

        assertThatCode(() -> listener.onVerificationEmail(event)).doesNotThrowAnyException();

        verifyNoInteractions(notificationOutboxSupport);
    }

    @Test
    void onOtpEmail_blankToAddress_earlyReturns_doesNotEnqueue() {
        CoachOtpEmailEvent event = new CoachOtpEmailEvent("", "123456", "en", "Ada");

        assertThatCode(() -> listener.onOtpEmail(event)).doesNotThrowAnyException();

        verifyNoInteractions(notificationOutboxSupport);
    }

    @Test
    void onVerificationEmail_nullToAddress_earlyReturns_doesNotEnqueue() {
        CoachVerificationEmailEvent event = new CoachVerificationEmailEvent(null, "https://verify", "en", "Ada");

        assertThatCode(() -> listener.onVerificationEmail(event)).doesNotThrowAnyException();

        verifyNoInteractions(notificationOutboxSupport);
    }

    /**
     * AC1: {@code enqueueEmail}'s widened {@code catch (Exception e)} — a serialization/DB failure
     * must be caught and logged rather than left to propagate out of this listener uncaught.
     *
     * <p><strong>What this does NOT prove (code review 2026-09-15, H1):</strong> this test mocks
     * {@code NotificationOutboxSupport} directly, so {@code doThrow(...)} never goes through the real
     * {@code Propagation.MANDATORY} transactional proxy. In production, that proxy already marks the
     * shared registration transaction rollback-only as the exception unwinds from {@code
     * enqueueEmail} — before this listener's catch ever runs — so catching it here does not prevent
     * the registration commit from failing; see {@code NotificationOutboxSupport}'s "Failure
     * semantics" javadoc. This test only proves the exception itself doesn't propagate further and
     * gets logged with a recoverable sendId.
     *
     * <p>Code review 2026-09-12: the method name promised "logged" but nothing verified it — only
     * {@code doesNotThrowAnyException()} was asserted. This now captures the real log output and
     * checks the sendId (hoisted to a local specifically so the catch could name it) is present.
     */
    @Test
    void onVerificationEmail_enqueueThrows_caughtAndLogged_doesNotPropagate() {
        doThrow(new IllegalStateException("boom")).when(notificationOutboxSupport)
            .enqueueEmail(any(), any(), anyMap(), anyString());
        CoachVerificationEmailEvent event = new CoachVerificationEmailEvent(
            "coach@example.com", "https://verify", "en", "Ada");

        Logger listenerLogger = (Logger) LoggerFactory.getLogger(CoachRegistrationEmailListener.class);
        ListAppender<ILoggingEvent> logCapture = new ListAppender<>();
        logCapture.start();
        try {
            listenerLogger.addAppender(logCapture);
            assertThatCode(() -> listener.onVerificationEmail(event)).doesNotThrowAnyException();
        } finally {
            listenerLogger.detachAppender(logCapture);
        }

        assertThat(logCapture.list)
            .as("the enqueue failure must actually be logged, with a recoverable sendId, not just swallowed")
            .anySatisfy(e -> {
                assertThat(e.getMessage()).contains("Failed to prepare/publish notification");
                assertThat(java.util.Arrays.stream(e.getArgumentArray()).map(String::valueOf))
                    .as("a UUID-shaped sendId must be among the logged structured arguments")
                    .anyMatch(arg -> arg.startsWith("sendId=") && UUID_PATTERN.matcher(arg.substring("sendId=".length())).matches());
            });
    }

    /**
     * skillars-deferred-111 AC6: the {@code HashMap} literal {@code onVerificationEmail} builds
     * {@code data} into (unlike the {@code Map.of(...)} it replaced for ses-1.4) accepts a null value
     * silently — {@code Objects.requireNonNull} restores the fail-fast behaviour. Placed OUTSIDE the
     * try/catch, so it must propagate, not be swallowed like an {@code enqueueEmail} failure.
     * {@code // Mutation:} moving the {@code requireNonNull} call inside the try block turns this red
     * (the NPE would be caught by {@code catch (Exception e)} and only logged).
     */
    @Test
    void onVerificationEmail_nullVerifyUrl_throwsNpe_doesNotEnqueue() {
        CoachVerificationEmailEvent event = new CoachVerificationEmailEvent(
            "coach@example.com", null, "en", "Ada");

        assertThatThrownBy(() -> listener.onVerificationEmail(event))
            .isInstanceOf(NullPointerException.class);

        verifyNoInteractions(notificationOutboxSupport);
    }

    /** skillars-deferred-111 AC6 — the OTP-side counterpart of the guard above. */
    @Test
    void onOtpEmail_nullOtp_throwsNpe_doesNotEnqueue() {
        CoachOtpEmailEvent event = new CoachOtpEmailEvent("coach@example.com", null, "en", "Ada");

        assertThatThrownBy(() -> listener.onOtpEmail(event))
            .isInstanceOf(NullPointerException.class);

        verifyNoInteractions(notificationOutboxSupport);
    }

    @Test
    void onOtpEmail_enqueueThrows_caughtAndLogged_doesNotPropagate() {
        doThrow(new IllegalStateException("boom")).when(notificationOutboxSupport)
            .enqueueEmail(any(), any(), anyMap(), anyString());
        CoachOtpEmailEvent event = new CoachOtpEmailEvent("coach@example.com", "123456", "en", "Ada");

        Logger listenerLogger = (Logger) LoggerFactory.getLogger(CoachRegistrationEmailListener.class);
        ListAppender<ILoggingEvent> logCapture = new ListAppender<>();
        logCapture.start();
        try {
            listenerLogger.addAppender(logCapture);
            assertThatCode(() -> listener.onOtpEmail(event)).doesNotThrowAnyException();
        } finally {
            listenerLogger.detachAppender(logCapture);
        }

        assertThat(logCapture.list)
            .anySatisfy(e -> {
                assertThat(e.getMessage()).contains("Failed to prepare/publish notification");
                assertThat(java.util.Arrays.stream(e.getArgumentArray()).map(String::valueOf))
                    .as("a UUID-shaped sendId must be among the logged structured arguments")
                    .anyMatch(arg -> arg.startsWith("sendId=") && UUID_PATTERN.matcher(arg.substring("sendId=".length())).matches());
            });
    }
}
