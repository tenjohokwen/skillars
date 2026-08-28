package com.softropic.skillars.platform.notification.infrastructure.listener;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.softropic.skillars.platform.booking.contract.BookingConfirmedEvent;
import com.softropic.skillars.platform.booking.contract.BookingExpiredEvent;
import com.softropic.skillars.platform.booking.contract.BookingReminderEvent;
import com.softropic.skillars.platform.booking.contract.BookingRequestedEvent;
import com.softropic.skillars.platform.booking.contract.RescheduleDeclinedByParentEvent;
import com.softropic.skillars.platform.booking.contract.RescheduleRequestedByCoachEvent;
import com.softropic.skillars.platform.notification.contract.Envelope;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BookingEmailListenerTest {

    @Mock
    private ApplicationEventPublisher publisher;

    @Captor
    private ArgumentCaptor<Envelope> envelopeCaptor;

    private BookingEmailListener listener;

    @BeforeEach
    void setUp() {
        listener = new BookingEmailListener(publisher, "http://localhost", "8080");
    }

    @Test
    void onBookingExpired_blankParentEmail_skipsEnvelope() {
        BookingExpiredEvent event = BookingExpiredEvent.builder()
                .source(this).bookingId(UUID.randomUUID()).parentId(1L).parentEmail("")
                .coachDisplayName("Coach").requestedStartTime(Instant.now()).canonicalTimezone("UTC")
                .build();

        listener.onBookingExpired(event);

        verify(publisher, never()).publishEvent(org.mockito.ArgumentMatchers.any(Envelope.class));
    }

    @Test
    void onBookingExpired_nullParentEmail_skipsEnvelope() {
        BookingExpiredEvent event = BookingExpiredEvent.builder()
                .source(this).bookingId(UUID.randomUUID()).parentId(1L).parentEmail(null)
                .coachDisplayName("Coach").requestedStartTime(Instant.now()).canonicalTimezone("UTC")
                .build();

        listener.onBookingExpired(event);

        verify(publisher, never()).publishEvent(org.mockito.ArgumentMatchers.any(Envelope.class));
    }

    @Test
    void onBookingExpired_validParentEmail_publishesEnvelope() {
        BookingExpiredEvent event = BookingExpiredEvent.builder()
                .source(this).bookingId(UUID.randomUUID()).parentId(1L).parentEmail("parent@example.com")
                .coachDisplayName("Coach").requestedStartTime(Instant.now()).canonicalTimezone("UTC")
                .build();

        listener.onBookingExpired(event);

        verify(publisher).publishEvent(envelopeCaptor.capture());
        assertThat(envelopeCaptor.getValue().recipients().get(0).getEmail()).isEqualTo("parent@example.com");
    }

    @Test
    void onBookingReminder_bothEmailsBlank_publishesNoEnvelope() {
        BookingReminderEvent event = BookingReminderEvent.builder()
                .source(this).bookingId(UUID.randomUUID()).parentEmail("").coachEmail("")
                .coachDisplayName("Coach").requestedStartTime(Instant.now()).canonicalTimezone("UTC")
                .reminderType("PRIMARY")
                .build();

        listener.onBookingReminder(event);

        verify(publisher, never()).publishEvent(org.mockito.ArgumentMatchers.any(Envelope.class));
    }

    @Test
    void onBookingReminder_onlyParentEmailPresent_publishesOneEnvelope() {
        BookingReminderEvent event = BookingReminderEvent.builder()
                .source(this).bookingId(UUID.randomUUID()).parentEmail("parent@example.com").coachEmail("")
                .coachDisplayName("Coach").requestedStartTime(Instant.now()).canonicalTimezone("UTC")
                .reminderType("PRIMARY")
                .build();

        listener.onBookingReminder(event);

        verify(publisher, times(1)).publishEvent(envelopeCaptor.capture());
        assertThat(envelopeCaptor.getValue().recipients().get(0).getEmail()).isEqualTo("parent@example.com");
    }

    @Test
    void onRescheduleRequestedByCoach_blankParentEmail_skipsEnvelope() {
        RescheduleRequestedByCoachEvent event = new RescheduleRequestedByCoachEvent(
            this, UUID.randomUUID(), "", "Coach", Instant.now(), Instant.now(), "UTC");

        listener.onRescheduleRequestedByCoach(event);

        verify(publisher, never()).publishEvent(org.mockito.ArgumentMatchers.any(Envelope.class));
    }

    @Test
    void onRescheduleRequestedByCoach_validParentEmail_publishesEnvelope() {
        RescheduleRequestedByCoachEvent event = new RescheduleRequestedByCoachEvent(
            this, UUID.randomUUID(), "parent@example.com", "Coach", Instant.now(), Instant.now(), "UTC");

        listener.onRescheduleRequestedByCoach(event);

        verify(publisher).publishEvent(envelopeCaptor.capture());
        assertThat(envelopeCaptor.getValue().recipients().get(0).getEmail()).isEqualTo("parent@example.com");
    }

    @Test
    void onRescheduleDeclinedByParent_blankCoachEmail_skipsEnvelope() {
        RescheduleDeclinedByParentEvent event = new RescheduleDeclinedByParentEvent(
            this, UUID.randomUUID(), "", "Parent", Instant.now(), "UTC");

        listener.onRescheduleDeclinedByParent(event);

        verify(publisher, never()).publishEvent(org.mockito.ArgumentMatchers.any(Envelope.class));
    }

    @Test
    void onRescheduleDeclinedByParent_validCoachEmail_publishesEnvelope() {
        RescheduleDeclinedByParentEvent event = new RescheduleDeclinedByParentEvent(
            this, UUID.randomUUID(), "coach@example.com", "Parent", Instant.now(), "UTC");

        listener.onRescheduleDeclinedByParent(event);

        verify(publisher).publishEvent(envelopeCaptor.capture());
        assertThat(envelopeCaptor.getValue().recipients().get(0).getEmail()).isEqualTo("coach@example.com");
    }

    /**
     * Deferred-78 AC7: onBookingRequested's data-prep (event.getRequestedStartTime().toString())
     * NPEs when requestedStartTime is null — a malformed event, since MailManager's own outbox
     * only ever protects a listener body AFTER publisher.publishEvent runs. Before this AC, that
     * exception propagated out of the AFTER_COMMIT synchronization uncaught. Proves the method now
     * returns normally, logs the failure, and never reaches publishEvent.
     */
    @Test
    void onBookingRequested_nullRequestedStartTime_catchesFailureInsteadOfPropagating() {
        Logger listenerLogger = (Logger) LoggerFactory.getLogger(BookingEmailListener.class);
        ListAppender<ILoggingEvent> logCapture = new ListAppender<>();
        logCapture.start();

        BookingRequestedEvent event = new BookingRequestedEvent(
            this, UUID.randomUUID(), 1L, 2L, UUID.randomUUID(), "Coach", "coach@example.com",
            "notes", null, "UTC");

        try {
            listenerLogger.addAppender(logCapture);
            listener.onBookingRequested(event);
        } finally {
            listenerLogger.detachAppender(logCapture);
        }

        verify(publisher, never()).publishEvent(org.mockito.ArgumentMatchers.any(Envelope.class));
        assertThat(logCapture.list)
            .as("the NPE must be caught and logged, not propagated out of the AFTER_COMMIT listener")
            .anySatisfy(loggingEvent -> assertThat(loggingEvent.getFormattedMessage())
                .contains("Failed to prepare/publish notification"));
    }

    @Test
    void sendId_hasNoCollisionAcross10kEmails() {
        Set<String> sendIds = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            BookingConfirmedEvent event = BookingConfirmedEvent.builder()
                    .source(this).bookingId(UUID.randomUUID()).parentId(1L).parentEmail("parent@example.com")
                    .coachDisplayName("Coach").requestedStartTime(Instant.now()).canonicalTimezone("UTC")
                    .build();
            listener.onBookingConfirmed(event);
        }

        verify(publisher, times(10_000)).publishEvent(envelopeCaptor.capture());
        for (Envelope envelope : envelopeCaptor.getAllValues()) {
            sendIds.add(envelope.sendId());
        }
        assertThat(sendIds).hasSize(10_000);
    }
}
