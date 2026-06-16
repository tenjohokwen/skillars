package com.softropic.skillars.platform.notification.infrastructure.listener;

import com.softropic.skillars.platform.booking.contract.BookingConfirmedEvent;
import com.softropic.skillars.platform.booking.contract.BookingDeclinedEvent;
import com.softropic.skillars.platform.booking.contract.BookingExpiredEvent;
import com.softropic.skillars.platform.booking.contract.BookingReminderEvent;
import com.softropic.skillars.platform.booking.contract.BookingRequestedEvent;
import com.softropic.skillars.platform.booking.contract.QuickCompleteConfirmationRequiredEvent;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.security.contract.util.ShortCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
public class BookingEmailListener {

    private final ApplicationEventPublisher publisher;
    private final String appBaseUrl;

    public BookingEmailListener(ApplicationEventPublisher publisher,
                                @Value("${baseurl}") String baseUrl,
                                @Value("${server.port}") String serverPort) {
        this.publisher = publisher;
        this.appBaseUrl = baseUrl + ":" + serverPort;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBookingRequested(BookingRequestedEvent event) {
        Map<String, Object> data = new HashMap<>();
        data.put("coachDisplayName", event.getCoachDisplayName());
        data.put("requestedStartTime", event.getRequestedStartTime().toString());
        data.put("canonicalTimezone", event.getCanonicalTimezone());
        data.put("notes", event.getNotes() != null ? event.getNotes() : "");

        Recipient recipient = new Recipient();
        recipient.setEmail(event.getCoachEmail());
        recipient.setLangKey("en");

        publisher.publishEvent(new Envelope(
            List.of(recipient), EmailTemplate.BOOKING_REQUESTED,
            Instant.now().plus(Duration.ofDays(1)), data,
            ShortCode.shortenInt(UUID.randomUUID().hashCode())
        ));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBookingConfirmed(BookingConfirmedEvent event) {
        Map<String, Object> data = new HashMap<>();
        data.put("coachDisplayName", event.getCoachDisplayName());
        data.put("requestedStartTime", event.getRequestedStartTime().toString());
        data.put("canonicalTimezone", event.getCanonicalTimezone());

        Recipient recipient = new Recipient();
        recipient.setEmail(event.getParentEmail());
        recipient.setLangKey("en");

        publisher.publishEvent(new Envelope(
            List.of(recipient), EmailTemplate.BOOKING_CONFIRMED,
            Instant.now().plus(Duration.ofDays(1)), data,
            ShortCode.shortenInt(UUID.randomUUID().hashCode())
        ));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBookingDeclined(BookingDeclinedEvent event) {
        Map<String, Object> data = new HashMap<>();
        data.put("coachDisplayName", event.getCoachDisplayName());
        data.put("requestedStartTime", event.getRequestedStartTime().toString());
        data.put("canonicalTimezone", event.getCanonicalTimezone());

        Recipient recipient = new Recipient();
        recipient.setEmail(event.getParentEmail());
        recipient.setLangKey("en");

        publisher.publishEvent(new Envelope(
            List.of(recipient), EmailTemplate.BOOKING_DECLINED,
            Instant.now().plus(Duration.ofDays(1)), data,
            ShortCode.shortenInt(UUID.randomUUID().hashCode())
        ));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBookingExpired(BookingExpiredEvent event) {
        Map<String, Object> data = new HashMap<>();
        data.put("coachDisplayName", event.getCoachDisplayName());
        data.put("requestedStartTime", event.getRequestedStartTime().toString());
        data.put("canonicalTimezone", event.getCanonicalTimezone());

        Recipient recipient = new Recipient();
        recipient.setEmail(event.getParentEmail());
        recipient.setLangKey("en");

        publisher.publishEvent(new Envelope(
            List.of(recipient), EmailTemplate.BOOKING_EXPIRED,
            Instant.now().plus(Duration.ofDays(1)), data,
            ShortCode.shortenInt(UUID.randomUUID().hashCode())
        ));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onQuickCompleteConfirmationRequired(QuickCompleteConfirmationRequiredEvent event) {
        Map<String, Object> data = new HashMap<>();
        data.put("coachDisplayName", event.getCoachDisplayName());
        data.put("playerName", event.getPlayerName());
        data.put("requestedStartTime", event.getSessionStartTime().toString());
        data.put("canonicalTimezone", event.getCanonicalTimezone());
        data.put("bookingsUrl", appBaseUrl + "/bookings");

        Recipient recipient = new Recipient();
        recipient.setEmail(event.getParentEmail());
        recipient.setLangKey("en");

        publisher.publishEvent(new Envelope(
            List.of(recipient), EmailTemplate.BOOKING_QUICK_COMPLETE_CONFIRM,
            Instant.now().plus(Duration.ofDays(1)), data,
            ShortCode.shortenInt(UUID.randomUUID().hashCode())
        ));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBookingReminder(BookingReminderEvent event) {
        Map<String, Object> data = new HashMap<>();
        data.put("coachDisplayName", event.getCoachDisplayName());
        data.put("requestedStartTime", event.getRequestedStartTime().toString());
        data.put("canonicalTimezone", event.getCanonicalTimezone());
        data.put("reminderType", event.getReminderType());

        for (String email : List.of(event.getParentEmail(), event.getCoachEmail())) {
            Recipient recipient = new Recipient();
            recipient.setEmail(email);
            recipient.setLangKey("en");
            publisher.publishEvent(new Envelope(
                List.of(recipient), EmailTemplate.BOOKING_REMINDER,
                Instant.now().plus(Duration.ofDays(1)), data,
                ShortCode.shortenInt(UUID.randomUUID().hashCode())
            ));
        }
    }
}
