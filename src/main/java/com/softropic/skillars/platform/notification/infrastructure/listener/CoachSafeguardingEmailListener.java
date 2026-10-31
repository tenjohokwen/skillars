package com.softropic.skillars.platform.notification.infrastructure.listener;

import com.softropic.skillars.infrastructure.web.FrontendLinkBuilder;
import com.softropic.skillars.platform.marketplace.contract.CoachSafeguardingExpiringEvent;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.notification.service.NotificationOutboxSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * S-09: the coach's "your safeguarding check expires soon" reminder. Same shape as the other email listeners:
 * {@code BEFORE_COMMIT}, so the outbox row commits atomically with the reminder job's {@code reminder_sent_at}
 * stamp. The template is transactional-category REMINDER (opt-out-able, see {@code NotificationCatalog}).
 * Data is string-typed (the date is formatted here), per {@code EmailDataRoundTripContractTest}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoachSafeguardingEmailListener {

    private final NotificationOutboxSupport notificationOutboxSupport;
    private final FrontendLinkBuilder frontendLinks;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onSafeguardingExpiring(CoachSafeguardingExpiringEvent event) {
        try {
            if (event.email() == null || event.email().isBlank()) {
                log.warn("Safeguarding expiry email skipped: no reachable recipient", kv("coachId", event.coachId()));
                return;
            }
            Map<String, Object> data = new HashMap<>();
            data.put("coachDisplayName", event.coachDisplayName() == null ? "" : event.coachDisplayName());
            data.put("expiresAt", formatDate(event));
            data.put("safeguardingUrl", frontendLinks.route("coach/safeguarding"));

            Recipient recipient = new Recipient();
            recipient.setEmail(event.email());
            recipient.setLangKey(event.langKey() == null ? "en" : event.langKey());
            notificationOutboxSupport.enqueueEmail(
                EmailTemplate.COACH_SAFEGUARDING_EXPIRING, recipient, data, UUID.randomUUID().toString());
        } catch (Exception e) {
            // enqueueEmail is MANDATORY: a failure there has already marked the job's transaction rollback-only,
            // so the stamp rolls back too and tomorrow's run retries. This catch only logs.
            log.error("Failed to prepare/publish notification", kv("template", EmailTemplate.COACH_SAFEGUARDING_EXPIRING),
                kv("coachId", event.coachId()), e);
        }
    }

    /** ISO date in the coach's own zone (falls back to UTC for an unknown zone). */
    private static String formatDate(CoachSafeguardingExpiringEvent event) {
        ZoneId zone;
        try {
            zone = event.timezone() == null ? ZoneId.of("UTC") : ZoneId.of(event.timezone());
        } catch (RuntimeException e) {
            zone = ZoneId.of("UTC");
        }
        return event.expiresAt().atZone(zone).toLocalDate().toString();
    }
}
