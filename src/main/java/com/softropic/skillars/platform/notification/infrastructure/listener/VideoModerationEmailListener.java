package com.softropic.skillars.platform.notification.infrastructure.listener;

import com.softropic.skillars.infrastructure.feature.AppFeature;
import com.softropic.skillars.infrastructure.feature.FeatureToggleService;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.security.contract.util.ShortCode;
import com.softropic.skillars.platform.video.contract.event.VideoModerationAdminAlertEvent;
import com.softropic.skillars.platform.video.contract.event.VideoModerationOwnerNotificationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PostConstruct;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Uses @EventListener (NOT @TransactionalEventListener) because alertAdmin() and notifyOwner()
// are called OUTSIDE any active transaction. @TransactionalEventListener with fallbackExecution=false
// (the default) silently discards events published outside a transaction — every admin alert and
// owner notification would be lost. @EventListener fires regardless of TX context.
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoModerationEmailListener {

    private final ApplicationEventPublisher publisher;
    private final ConfigService configService;
    private final FeatureToggleService featureToggleService;

    @PostConstruct
    void checkAdminAlertConfig() {
        String adminEmail = configService.find("platform.admin_alert_email").orElse("");
        if (adminEmail.isBlank()) {
            if (featureToggleService.isEnabled(AppFeature.ARACHNID_ENABLED)) {
                throw new IllegalStateException(
                    "STARTUP ABORTED: ARACHNID_ENABLED is true but platform.admin_alert_email is blank. " +
                    "CSAM admin alerts would be silently dropped. Configure platform.admin_alert_email before enabling Arachnid.");
            }
            log.error("STARTUP: platform.admin_alert_email config is blank — CSAM and moderation admin alerts will be silently dropped if ARACHNID_ENABLED is turned on");
        }
    }

    @EventListener
    public void onAdminAlert(VideoModerationAdminAlertEvent event) {
        String adminEmail = configService.find("platform.admin_alert_email").orElse("");
        if (adminEmail.isBlank()) {
            log.error("platform.admin_alert_email config key is blank — admin alert NOT sent: {}", event.subject());
            return;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("subject", event.subject());
        data.put("body", event.body());
        data.put("urgent", event.urgent());
        data.put("videoId", event.videoId() != null ? event.videoId().toString() : "N/A");
        data.put("ownerId", event.ownerId() != null ? event.ownerId() : "N/A");

        Recipient recipient = new Recipient();
        recipient.setEmail(adminEmail);
        recipient.setLangKey("en");

        publisher.publishEvent(new Envelope(
            List.of(recipient),
            EmailTemplate.VIDEO_MODERATION_ADMIN_ALERT,
            Instant.now().plus(Duration.ofHours(1)),
            data,
            ShortCode.shortenInt(UUID.randomUUID().hashCode())
        ));
    }

    @EventListener
    public void onOwnerNotification(VideoModerationOwnerNotificationEvent event) {
        // ownerId is the user's login/email in this project
        if (event.ownerId() == null || event.ownerId().isBlank()) {
            log.warn("VideoModerationOwnerNotificationEvent has null ownerId — notification not sent");
            return;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("message", event.message());
        data.put("videoId", event.videoId() != null ? event.videoId().toString() : "N/A");

        Recipient recipient = new Recipient();
        recipient.setEmail(event.ownerId());
        recipient.setLangKey("en");

        publisher.publishEvent(new Envelope(
            List.of(recipient),
            EmailTemplate.VIDEO_MODERATION_OWNER_FLAGGED,
            Instant.now().plus(Duration.ofDays(1)),
            data,
            ShortCode.shortenInt(UUID.randomUUID().hashCode())
        ));
    }
}
