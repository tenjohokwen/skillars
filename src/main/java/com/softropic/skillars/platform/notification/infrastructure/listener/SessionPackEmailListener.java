package com.softropic.skillars.platform.notification.infrastructure.listener;

import com.softropic.skillars.platform.booking.contract.PackPausedEvent;
import com.softropic.skillars.platform.booking.contract.SessionPackExpiredEvent;
import com.softropic.skillars.platform.booking.contract.SessionPackExpiryWarningEvent;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.notification.contract.Recipient;

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
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class SessionPackEmailListener {

    private final ApplicationEventPublisher publisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onExpiryWarning(SessionPackExpiryWarningEvent event) {
        Map<String, Object> data = new HashMap<>();
        data.put("coachDisplayName", event.getCoachDisplayName());
        data.put("creditsRemaining", event.getCreditsRemaining());
        data.put("expiresAt", formatInstantInZone(event.getExpiresAt(), event.getCanonicalTimezone()));
        data.put("warningThreshold", event.getWarningThreshold());

        Recipient recipient = new Recipient();
        recipient.setEmail(event.getParentEmail());
        recipient.setLangKey("en");

        publisher.publishEvent(new Envelope(
            List.of(recipient), EmailTemplate.SESSION_PACK_EXPIRY_WARNING,
            Instant.now().plus(Duration.ofDays(1)), data,
            UUID.randomUUID().toString()
        ));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPackExpired(SessionPackExpiredEvent event) {
        Map<String, Object> data = new HashMap<>();
        data.put("coachDisplayName", event.getCoachDisplayName());
        data.put("creditsRemaining", event.getCreditsRemaining());

        Recipient recipient = new Recipient();
        recipient.setEmail(event.getParentEmail());
        recipient.setLangKey("en");

        publisher.publishEvent(new Envelope(
            List.of(recipient), EmailTemplate.SESSION_PACK_EXPIRED,
            Instant.now().plus(Duration.ofDays(1)), data,
            UUID.randomUUID().toString()
        ));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPackPaused(PackPausedEvent event) {
        Map<String, Object> data = new HashMap<>();
        data.put("coachDisplayName", event.getCoachDisplayName());
        data.put("newExpiresAt", formatInstantInZone(event.getNewExpiresAt(), event.getCanonicalTimezone()));
        List<String> formattedTimes = event.getCancelledBookingTimes().stream()
            .map(t -> formatInstantInZone(t, event.getCanonicalTimezone()))
            .collect(Collectors.toList());
        data.put("cancelledBookingTimes", formattedTimes);

        Recipient recipient = new Recipient();
        recipient.setEmail(event.getParentEmail());
        recipient.setLangKey("en");

        publisher.publishEvent(new Envelope(
            List.of(recipient), EmailTemplate.PACK_PAUSED,
            Instant.now().plus(Duration.ofDays(1)), data,
            UUID.randomUUID().toString()
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
}
