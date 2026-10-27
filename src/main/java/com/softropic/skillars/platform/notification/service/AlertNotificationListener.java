package com.softropic.skillars.platform.notification.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.notification.contract.AlertFiredEvent;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.notification.service.MailManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Handles {@link AlertFiredEvent} published by {@link AlertEvaluationService}.
 *
 * <p>Always logs a WARN for every alert. If the notification channel is EMAIL,
 * additionally attempts to send an email via {@link MailManager} to {@code platform.admin_alert_email}.
 * Mail failures are caught and logged as ERROR — they must not propagate and break the evaluation loop.
 *
 * <p><strong>skillars-deferred-164 AC12 — still unreachable in production today</strong>: {@code
 * AlertEvaluationService.computeMetricValue} returns {@code -1.0} unconditionally, so no {@link
 * AlertFiredEvent} is ever published outside a test. The hardening below closes three real gaps
 * found by code review so this listener is correct on the day {@code computeMetricValue} ships, not
 * a live bug fix.
 * <ul>
 *   <li>(a) the no-recipient ERROR log is throttled to at most once per {@link #noRecipientLogCooldown}
 *       (default 300s) — previously it logged ERROR on every single fired alert while the config key
 *       stayed blank/invalid, once per 30s evaluation tick per breaching rule.</li>
 *   <li>(b) {@code platform.admin_alert_email} is trimmed and given a basic shape check
 *       (contains {@code @}) before use — a surrounding-whitespace or malformed value previously
 *       reached {@code Recipient.setEmail} as-is.</li>
 *   <li>(c) a {@code configService.find(...)} lookup failure is logged under the distinct
 *       {@code EMAIL_CONFIG_ERROR} status, not conflated with a genuine
 *       {@code mailManager.sendEmailSync} transport failure ({@code EMAIL_ERROR}) — the two mean
 *       different things to an operator ("our config service is down" vs. "SES rejected the send").</li>
 * </ul>
 */
@Component
public class AlertNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(AlertNotificationListener.class);

    /** DB-backed config key shared with the video-moderation admin alerts; blank by default (V139). */
    private static final String ADMIN_ALERT_EMAIL_KEY = "platform.admin_alert_email";

    private final MailManager mailManager;
    private final ConfigService configService;
    private final Clock clock;
    private final Duration noRecipientLogCooldown;

    /**
     * Global, not per-metric: a blank/invalid {@code platform.admin_alert_email} is one
     * configuration problem affecting every rule identically, so one throttled ERROR stream for it
     * is the useful signal — a per-metric throttle would just multiply the same complaint by however
     * many rules are breaching concurrently.
     */
    private final AtomicReference<Instant> lastNoRecipientLogAt = new AtomicReference<>();

    public AlertNotificationListener(MailManager mailManager, ConfigService configService, Clock clock,
            @Value("${alert.notification.no-recipient-log-cooldown-seconds:300}") long noRecipientLogCooldownSeconds) {
        this.mailManager = mailManager;
        this.configService = configService;
        this.clock = clock;
        this.noRecipientLogCooldown = Duration.ofSeconds(noRecipientLogCooldownSeconds);
    }

    /**
     * React to an alert threshold breach.
     *
     * @param event the fired alert event
     */
    @EventListener(AlertFiredEvent.class)
    public void onAlertFired(AlertFiredEvent event) {
        log.warn("Alert fired",
                kv("operation", "alert_notification"),
                kv("metric", event.metricName()),
                kv("actual", event.actualValue()),
                kv("threshold", event.threshold()),
                kv("status", "FIRED"));

        if (!"EMAIL".equalsIgnoreCase(event.notificationChannel())) {
            return;
        }

        String adminEmail;
        try {
            // AC12(c): isolated from the send attempt below — a lookup failure here is a config
            // service problem, not a mail transport problem, and must be distinguishable as one.
            adminEmail = configService.find(ADMIN_ALERT_EMAIL_KEY).orElse("").strip();
        } catch (Exception e) {
            log.error("Failed to look up platform.admin_alert_email — alert email NOT sent",
                kv("operation", "alert_notification"),
                kv("metric", event.metricName()),
                kv("status", "EMAIL_CONFIG_ERROR"),
                e);
            return;
        }

        if (adminEmail.isBlank() || !looksLikeAnEmailAddress(adminEmail)) {
            logNoRecipientThrottled(event);
            return;
        }

        try {
            String subject = "Skillars Alert: " + event.metricName() + " threshold breached";
            String body = String.format(
                    "Metric %s actual value %.4f exceeded threshold %.4f",
                    event.metricName(), event.actualValue(), event.threshold());

            Recipient recipient = new Recipient();
            recipient.setEmail(adminEmail);
            recipient.setFirstname("Ops");
            recipient.setLastname("Team");
            recipient.setLangKey("en");

            Envelope envelope = new Envelope(
                    List.of(recipient),
                    EmailTemplate.NONE,
                    Instant.now().plusSeconds(300),
                    Map.of("subject", subject, "body", body),
                    UUID.randomUUID().toString());

            mailManager.sendEmailSync(envelope);
        } catch (Exception e) {
            // Mail failure must not break the scheduling loop — log and continue
            log.error("Failed to send alert email",
                kv("operation", "alert_notification"),
                kv("metric", event.metricName()),
                kv("status", "EMAIL_ERROR"),
                e);
        }
    }

    /** AC12(b): no new validation library — a basic shape check is all this key needs. */
    private static boolean looksLikeAnEmailAddress(String value) {
        return value.contains("@");
    }

    /** AC12(a): see {@link #lastNoRecipientLogAt}'s own Javadoc for why this is global, not per-metric. */
    private void logNoRecipientThrottled(AlertFiredEvent event) {
        Instant now = clock.instant();
        Instant last = lastNoRecipientLogAt.get();
        if (last != null && now.isBefore(last.plus(noRecipientLogCooldown))) {
            return;
        }
        lastNoRecipientLogAt.set(now);
        log.error("platform.admin_alert_email is blank or not a plausible email address — alert email NOT sent",
            kv("operation", "alert_notification"),
            kv("metric", event.metricName()),
            kv("status", "EMAIL_NO_RECIPIENT"));
    }
}
