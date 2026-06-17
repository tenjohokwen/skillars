package com.softropic.skillars.platform.notification.infrastructure.listener;

import com.softropic.skillars.platform.booking.contract.BatchBookingAcceptedEvent;
import com.softropic.skillars.platform.booking.contract.BatchBookingRequestedEvent;
import com.softropic.skillars.platform.booking.contract.BookingConfirmedEvent;
import com.softropic.skillars.platform.booking.contract.BookingDeclinedEvent;
import com.softropic.skillars.platform.booking.contract.BookingExpiredEvent;
import com.softropic.skillars.platform.booking.contract.BookingReminderEvent;
import com.softropic.skillars.platform.booking.contract.BookingRequestedEvent;
import com.softropic.skillars.platform.booking.contract.DuplicateBookingProposedEvent;
import com.softropic.skillars.platform.booking.contract.QuickCompleteConfirmationRequiredEvent;
import com.softropic.skillars.platform.booking.contract.RescheduleAcceptedEvent;
import com.softropic.skillars.platform.booking.contract.RescheduleDeclinedEvent;
import com.softropic.skillars.platform.booking.contract.RescheduleRequestedEvent;
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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
    public void onRescheduleRequested(RescheduleRequestedEvent event) {
        Map<String, Object> data = new HashMap<>();
        data.put("parentName", event.getParentName());
        data.put("originalStartTime", formatInstantInZone(event.getOriginalStartTime(), event.getCanonicalTimezone()));
        data.put("proposedStartTime", formatInstantInZone(event.getProposedStartTime(), event.getCanonicalTimezone()));
        data.put("canonicalTimezone", event.getCanonicalTimezone());

        Recipient recipient = new Recipient();
        recipient.setEmail(event.getCoachEmail());
        recipient.setLangKey("en");

        publisher.publishEvent(new Envelope(
            List.of(recipient), EmailTemplate.BOOKING_RESCHEDULE_REQUESTED,
            Instant.now().plus(Duration.ofDays(1)), data,
            ShortCode.shortenInt(UUID.randomUUID().hashCode())
        ));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRescheduleAccepted(RescheduleAcceptedEvent event) {
        Map<String, Object> data = new HashMap<>();
        data.put("coachDisplayName", event.getCoachDisplayName());
        data.put("newStartTime", formatInstantInZone(event.getNewStartTime(), event.getCanonicalTimezone()));
        data.put("canonicalTimezone", event.getCanonicalTimezone());

        for (String email : List.of(event.getParentEmail(), event.getCoachEmail()).stream()
                .filter(e -> e != null && !e.isBlank()).toList()) {
            Recipient recipient = new Recipient();
            recipient.setEmail(email);
            recipient.setLangKey("en");
            publisher.publishEvent(new Envelope(
                List.of(recipient), EmailTemplate.BOOKING_RESCHEDULE_ACCEPTED,
                Instant.now().plus(Duration.ofDays(1)), data,
                ShortCode.shortenInt(UUID.randomUUID().hashCode())
            ));
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRescheduleDeclined(RescheduleDeclinedEvent event) {
        Map<String, Object> data = new HashMap<>();
        data.put("coachDisplayName", event.getCoachDisplayName());
        data.put("originalStartTime", formatInstantInZone(event.getOriginalStartTime(), event.getCanonicalTimezone()));
        data.put("canonicalTimezone", event.getCanonicalTimezone());

        Recipient recipient = new Recipient();
        recipient.setEmail(event.getParentEmail());
        recipient.setLangKey("en");

        publisher.publishEvent(new Envelope(
            List.of(recipient), EmailTemplate.BOOKING_RESCHEDULE_DECLINED,
            Instant.now().plus(Duration.ofDays(1)), data,
            ShortCode.shortenInt(UUID.randomUUID().hashCode())
        ));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBatchBookingRequested(BatchBookingRequestedEvent event) {
        if (event.getCoachEmail() == null || event.getCoachEmail().isBlank()) {
            log.warn("Cannot send batch booking requested email: coach email is blank, batchId={}", event.getBatchId());
            return;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("parentName", event.getParentName());
        data.put("requestedCount", event.getRequestedCount());
        data.put("canonicalTimezone", event.getCanonicalTimezone());
        List<String> formattedDates = event.getSessionDates().stream()
            .map(d -> formatInstantInZone(d, event.getCanonicalTimezone()))
            .toList();
        data.put("sessionDates", formattedDates);

        Recipient recipient = new Recipient();
        recipient.setEmail(event.getCoachEmail());
        recipient.setLangKey("en");

        publisher.publishEvent(new Envelope(
            List.of(recipient), EmailTemplate.BOOKING_BATCH_REQUESTED,
            Instant.now().plus(Duration.ofDays(1)), data,
            ShortCode.shortenInt(UUID.randomUUID().hashCode())
        ));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBatchBookingAccepted(BatchBookingAcceptedEvent event) {
        if (event.getParentEmail() == null || event.getParentEmail().isBlank()) {
            log.warn("Cannot send batch booking accepted email: parent email is blank, batchId={}", event.getBatchId());
            return;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("coachDisplayName", event.getCoachDisplayName());
        data.put("acceptedCount", event.getAcceptedCount());
        data.put("batchId", event.getBatchId().toString());

        Recipient recipient = new Recipient();
        recipient.setEmail(event.getParentEmail());
        recipient.setLangKey("en");

        publisher.publishEvent(new Envelope(
            List.of(recipient), EmailTemplate.BOOKING_BATCH_ACCEPTED,
            Instant.now().plus(Duration.ofDays(1)), data,
            ShortCode.shortenInt(UUID.randomUUID().hashCode())
        ));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDuplicateBookingProposed(DuplicateBookingProposedEvent event) {
        Map<String, Object> data = new HashMap<>();
        data.put("coachDisplayName", event.getCoachDisplayName());
        data.put("proposedStartTime", formatInstantInZone(event.getProposedStartTime(), event.getCanonicalTimezone()));
        data.put("canonicalTimezone", event.getCanonicalTimezone());

        Recipient recipient = new Recipient();
        recipient.setEmail(event.getParentEmail());
        recipient.setLangKey("en");

        publisher.publishEvent(new Envelope(
            List.of(recipient), EmailTemplate.BOOKING_DUPLICATE_PROPOSED,
            Instant.now().plus(Duration.ofDays(1)), data,
            ShortCode.shortenInt(UUID.randomUUID().hashCode())
        ));
    }

    private String formatInstantInZone(Instant instant, String timezone) {
        try {
            return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .withLocale(Locale.ENGLISH)
                .withZone(ZoneId.of(timezone))
                .format(instant);
        } catch (Exception e) {
            return instant.toString();
        }
    }

    public void onBookingReminder(BookingReminderEvent event) {
        Map<String, Object> data = new HashMap<>();
        data.put("coachDisplayName", event.getCoachDisplayName());
        data.put("requestedStartTime", event.getRequestedStartTime().toString());
        data.put("canonicalTimezone", event.getCanonicalTimezone());
        data.put("reminderType", event.getReminderType());

        for (String email : List.of(event.getParentEmail(), event.getCoachEmail()).stream()
                .filter(e -> e != null && !e.isBlank()).toList()) {
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
