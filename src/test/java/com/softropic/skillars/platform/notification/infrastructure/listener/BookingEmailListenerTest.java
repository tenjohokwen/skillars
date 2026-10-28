package com.softropic.skillars.platform.notification.infrastructure.listener;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.softropic.skillars.platform.booking.contract.BookingConfirmedEvent;
import com.softropic.skillars.platform.booking.contract.BookingExpiredEvent;
import com.softropic.skillars.platform.booking.contract.BookingReminderEvent;
import com.softropic.skillars.platform.booking.contract.BookingRequestedEvent;
import com.softropic.skillars.platform.booking.contract.QuickCompleteConfirmationRequiredEvent;
import com.softropic.skillars.platform.booking.contract.RescheduleDeclinedByParentEvent;
import com.softropic.skillars.platform.booking.contract.RescheduleRequestedByCoachEvent;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.notification.service.NotificationOutboxSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BookingEmailListenerTest {

    @Mock
    private NotificationOutboxSupport notificationOutboxSupport;

    @Captor
    private ArgumentCaptor<Recipient> recipientCaptor;

    @Captor
    private ArgumentCaptor<String> sendIdCaptor;

    private BookingEmailListener listener;

    @BeforeEach
    void setUp() {
        listener = new BookingEmailListener(notificationOutboxSupport, new com.softropic.skillars.infrastructure.web.FrontendLinkBuilder("http://localhost:9000"));
    }

    private void verifyNoEmailEnqueued() {
        verify(notificationOutboxSupport, never()).enqueueEmail(any(), any(), anyMap(), anyString());
    }

    @Captor
    private ArgumentCaptor<Map<String, Object>> dataCaptor;

    @Test
    void onQuickCompleteConfirmationRequired_bookingsUrl_isFrontendHashRoute() {
        // Was baseurl:server.port + "/bookings": the backend origin and a path the hash router
        // does not have. Must be the frontend origin + "/#/" + an existing route.
        listener.onQuickCompleteConfirmationRequired(new QuickCompleteConfirmationRequiredEvent(
            this, UUID.randomUUID(), 1L, "parent@example.com", "Coach", Instant.now(), "UTC", "Player"));

        verify(notificationOutboxSupport).enqueueEmail(
            eq(EmailTemplate.BOOKING_QUICK_COMPLETE_CONFIRM), any(), dataCaptor.capture(), anyString());
        assertThat(dataCaptor.getValue()).containsEntry("bookingsUrl", "http://localhost:9000/#/parent/bookings");
    }

    @Test
    void onBookingExpired_blankParentEmail_skipsEnvelope() {
        BookingExpiredEvent event = BookingExpiredEvent.builder()
                .source(this).bookingId(UUID.randomUUID()).parentId(1L).parentEmail("")
                .coachDisplayName("Coach").requestedStartTime(Instant.now()).canonicalTimezone("UTC")
                .build();

        listener.onBookingExpired(event);

        verifyNoEmailEnqueued();
    }

    @Test
    void onBookingExpired_nullParentEmail_skipsEnvelope() {
        BookingExpiredEvent event = BookingExpiredEvent.builder()
                .source(this).bookingId(UUID.randomUUID()).parentId(1L).parentEmail(null)
                .coachDisplayName("Coach").requestedStartTime(Instant.now()).canonicalTimezone("UTC")
                .build();

        listener.onBookingExpired(event);

        verifyNoEmailEnqueued();
    }

    @Test
    void onBookingExpired_validParentEmail_publishesEnvelope() {
        BookingExpiredEvent event = BookingExpiredEvent.builder()
                .source(this).bookingId(UUID.randomUUID()).parentId(1L).parentEmail("parent@example.com")
                .coachDisplayName("Coach").requestedStartTime(Instant.now()).canonicalTimezone("UTC")
                .build();

        listener.onBookingExpired(event);

        verify(notificationOutboxSupport).enqueueEmail(
            any(EmailTemplate.class), recipientCaptor.capture(), anyMap(), anyString());
        assertThat(recipientCaptor.getValue().getEmail()).isEqualTo("parent@example.com");
    }

    @Test
    void onBookingReminder_bothEmailsBlank_publishesNoEnvelope() {
        BookingReminderEvent event = BookingReminderEvent.builder()
                .source(this).bookingId(UUID.randomUUID()).parentEmail("").coachEmail("")
                .coachDisplayName("Coach").requestedStartTime(Instant.now()).canonicalTimezone("UTC")
                .reminderType("PRIMARY")
                .build();

        listener.onBookingReminder(event);

        verifyNoEmailEnqueued();
    }

    @Test
    void onBookingReminder_onlyParentEmailPresent_publishesOneEnvelope() {
        BookingReminderEvent event = BookingReminderEvent.builder()
                .source(this).bookingId(UUID.randomUUID()).parentEmail("parent@example.com").coachEmail("")
                .coachDisplayName("Coach").requestedStartTime(Instant.now()).canonicalTimezone("UTC")
                .reminderType("PRIMARY")
                .build();

        listener.onBookingReminder(event);

        verify(notificationOutboxSupport, times(1)).enqueueEmail(
            any(EmailTemplate.class), recipientCaptor.capture(), anyMap(), anyString());
        assertThat(recipientCaptor.getValue().getEmail()).isEqualTo("parent@example.com");
    }

    /**
     * B2 regression: a NULL coach email (not just a blank one) used to throw inside List.of, and the
     * catch swallowed it — so the parent's reminder was silently lost too.
     */
    @Test
    void onBookingReminder_nullCoachEmail_stillSendsParentReminder() {
        BookingReminderEvent event = BookingReminderEvent.builder()
                .source(this).bookingId(UUID.randomUUID()).parentEmail("parent@example.com").coachEmail(null)
                .coachDisplayName("Coach").requestedStartTime(Instant.now()).canonicalTimezone("UTC")
                .reminderType("PRIMARY")
                .build();

        listener.onBookingReminder(event);

        verify(notificationOutboxSupport, times(1)).enqueueEmail(
            any(EmailTemplate.class), recipientCaptor.capture(), anyMap(), anyString());
        assertThat(recipientCaptor.getValue().getEmail()).isEqualTo("parent@example.com");
    }

    /** B2: the same List.of null trap existed in onRescheduleAccepted. */
    @Test
    void onRescheduleAccepted_nullCoachEmail_stillSendsParentEmail() {
        listener.onRescheduleAccepted(new com.softropic.skillars.platform.booking.contract.RescheduleAcceptedEvent(
            this, UUID.randomUUID(), "parent@example.com", null, "Coach", Instant.now(), "UTC"));

        verify(notificationOutboxSupport, times(1)).enqueueEmail(
            any(EmailTemplate.class), recipientCaptor.capture(), anyMap(), anyString());
        assertThat(recipientCaptor.getValue().getEmail()).isEqualTo("parent@example.com");
    }

    @Test
    void onRescheduleRequestedByCoach_blankParentEmail_skipsEnvelope() {
        RescheduleRequestedByCoachEvent event = new RescheduleRequestedByCoachEvent(
            this, UUID.randomUUID(), "", "Coach", Instant.now(), Instant.now(), "UTC");

        listener.onRescheduleRequestedByCoach(event);

        verifyNoEmailEnqueued();
    }

    @Test
    void onRescheduleRequestedByCoach_validParentEmail_publishesEnvelope() {
        RescheduleRequestedByCoachEvent event = new RescheduleRequestedByCoachEvent(
            this, UUID.randomUUID(), "parent@example.com", "Coach", Instant.now(), Instant.now(), "UTC");

        listener.onRescheduleRequestedByCoach(event);

        verify(notificationOutboxSupport).enqueueEmail(
            any(EmailTemplate.class), recipientCaptor.capture(), anyMap(), anyString());
        assertThat(recipientCaptor.getValue().getEmail()).isEqualTo("parent@example.com");
    }

    @Test
    void onRescheduleDeclinedByParent_blankCoachEmail_skipsEnvelope() {
        RescheduleDeclinedByParentEvent event = new RescheduleDeclinedByParentEvent(
            this, UUID.randomUUID(), "", "Parent", Instant.now(), "UTC");

        listener.onRescheduleDeclinedByParent(event);

        verifyNoEmailEnqueued();
    }

    @Test
    void onRescheduleDeclinedByParent_validCoachEmail_publishesEnvelope() {
        RescheduleDeclinedByParentEvent event = new RescheduleDeclinedByParentEvent(
            this, UUID.randomUUID(), "coach@example.com", "Parent", Instant.now(), "UTC");

        listener.onRescheduleDeclinedByParent(event);

        verify(notificationOutboxSupport).enqueueEmail(
            any(EmailTemplate.class), recipientCaptor.capture(), anyMap(), anyString());
        assertThat(recipientCaptor.getValue().getEmail()).isEqualTo("coach@example.com");
    }

    /**
     * Deferred-78 AC7: onBookingRequested's data-prep (event.getRequestedStartTime().toString())
     * NPEs when requestedStartTime is null — a malformed event. Proves the method now returns
     * normally, logs the failure, and never reaches the outbox enqueue.
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

        verifyNoEmailEnqueued();
        assertThat(logCapture.list)
            .as("the NPE must be caught and logged, not propagated out of the AFTER_COMMIT listener")
            .anySatisfy(loggingEvent -> assertThat(loggingEvent.getFormattedMessage())
                .contains("Failed to prepare/publish notification"));
    }

    @Test
    void sendId_hasNoCollisionAcross10kEmails() {
        for (int i = 0; i < 10_000; i++) {
            BookingConfirmedEvent event = BookingConfirmedEvent.builder()
                    .source(this).bookingId(UUID.randomUUID()).parentId(1L).parentEmail("parent@example.com")
                    .coachDisplayName("Coach").requestedStartTime(Instant.now()).canonicalTimezone("UTC")
                    .build();
            listener.onBookingConfirmed(event);
        }

        verify(notificationOutboxSupport, times(10_000)).enqueueEmail(
            any(EmailTemplate.class), any(Recipient.class), anyMap(), sendIdCaptor.capture());
        Set<String> sendIds = new HashSet<>(sendIdCaptor.getAllValues());
        assertThat(sendIds).hasSize(10_000);
    }
}
