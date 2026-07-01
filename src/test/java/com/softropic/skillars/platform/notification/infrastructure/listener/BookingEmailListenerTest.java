package com.softropic.skillars.platform.notification.infrastructure.listener;

import com.softropic.skillars.platform.booking.contract.BookingConfirmedEvent;
import com.softropic.skillars.platform.booking.contract.BookingExpiredEvent;
import com.softropic.skillars.platform.booking.contract.BookingReminderEvent;
import com.softropic.skillars.platform.notification.contract.Envelope;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
        BookingExpiredEvent event = new BookingExpiredEvent(
                this, UUID.randomUUID(), 1L, "", "Coach", Instant.now(), "UTC"
        );

        listener.onBookingExpired(event);

        verify(publisher, never()).publishEvent(org.mockito.ArgumentMatchers.any(Envelope.class));
    }

    @Test
    void onBookingExpired_nullParentEmail_skipsEnvelope() {
        BookingExpiredEvent event = new BookingExpiredEvent(
                this, UUID.randomUUID(), 1L, null, "Coach", Instant.now(), "UTC"
        );

        listener.onBookingExpired(event);

        verify(publisher, never()).publishEvent(org.mockito.ArgumentMatchers.any(Envelope.class));
    }

    @Test
    void onBookingExpired_validParentEmail_publishesEnvelope() {
        BookingExpiredEvent event = new BookingExpiredEvent(
                this, UUID.randomUUID(), 1L, "parent@example.com", "Coach", Instant.now(), "UTC"
        );

        listener.onBookingExpired(event);

        verify(publisher).publishEvent(envelopeCaptor.capture());
        assertThat(envelopeCaptor.getValue().recipients().get(0).getEmail()).isEqualTo("parent@example.com");
    }

    @Test
    void onBookingReminder_bothEmailsBlank_publishesNoEnvelope() {
        BookingReminderEvent event = new BookingReminderEvent(
                this, UUID.randomUUID(), "", "", "Coach", Instant.now(), "UTC", "PRIMARY"
        );

        listener.onBookingReminder(event);

        verify(publisher, never()).publishEvent(org.mockito.ArgumentMatchers.any(Envelope.class));
    }

    @Test
    void onBookingReminder_onlyParentEmailPresent_publishesOneEnvelope() {
        BookingReminderEvent event = new BookingReminderEvent(
                this, UUID.randomUUID(), "parent@example.com", "", "Coach", Instant.now(), "UTC", "PRIMARY"
        );

        listener.onBookingReminder(event);

        verify(publisher, times(1)).publishEvent(envelopeCaptor.capture());
        assertThat(envelopeCaptor.getValue().recipients().get(0).getEmail()).isEqualTo("parent@example.com");
    }

    @Test
    void sendId_hasNoCollisionAcross10kEmails() {
        Set<String> sendIds = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            BookingConfirmedEvent event = new BookingConfirmedEvent(
                    this, UUID.randomUUID(), 1L, "parent@example.com", "Coach", Instant.now(), "UTC"
            );
            listener.onBookingConfirmed(event);
        }

        verify(publisher, times(10_000)).publishEvent(envelopeCaptor.capture());
        for (Envelope envelope : envelopeCaptor.getAllValues()) {
            sendIds.add(envelope.sendId());
        }
        assertThat(sendIds).hasSize(10_000);
    }
}
