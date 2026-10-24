package com.softropic.skillars.platform.notification.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.notification.contract.AlertFiredEvent;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.notification.service.MailManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Handles {@link AlertFiredEvent} published by {@link AlertEvaluationService}.
 *
 * <p>Always logs a WARN for every alert. If the notification channel is EMAIL,
 * additionally attempts to send an email via {@link MailManager} to {@code platform.admin_alert_email}
 * (nothing is sent, and an ERROR is logged, while that key is blank). Mail failures are
 * caught and logged as ERROR — they must not propagate and break the evaluation loop.
 */
@Component
public class AlertNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(AlertNotificationListener.class);

    /** DB-backed config key shared with the video-moderation admin alerts; blank by default (V139). */
    private static final String ADMIN_ALERT_EMAIL_KEY = "platform.admin_alert_email";

    private final MailManager mailManager;
    private final ConfigService configService;

    public AlertNotificationListener(MailManager mailManager, ConfigService configService) {
        this.mailManager = mailManager;
        this.configService = configService;
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

        if ("EMAIL".equalsIgnoreCase(event.notificationChannel())) {
            try {
                // Inside the try: a stale-cache refresh in find() can throw, and an escape here would
                // abort the remaining rules of the evaluate() tick.
                String adminEmail = configService.find(ADMIN_ALERT_EMAIL_KEY).orElse("");
                if (adminEmail.isBlank()) {
                    log.error("platform.admin_alert_email config key is blank — alert email NOT sent",
                        kv("operation", "alert_notification"),
                        kv("metric", event.metricName()),
                        kv("status", "EMAIL_NO_RECIPIENT"));
                    return;
                }
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
    }
}
