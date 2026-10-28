package com.softropic.skillars.platform.notification.infrastructure.listener;

import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.notification.service.NotificationOutboxSupport;
import com.softropic.skillars.platform.video.contract.event.VideoModerationOwnerNotificationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * User-facing video notice: the owner's "your video was flagged" notice. It goes through the durable
 * notification outbox ({@link NotificationOutboxSupport#enqueueEmail}), which also writes the in-app
 * inbox row. {@code MODERATION} category — transactional, never opt-out-able. (The three
 * VIDEO_APPROVAL_* notices went with the parental-approval flow — ADR "Minors never upload",
 * 2026-10-28.)
 *
 * <p>Recipients are resolved by the video module and carried on the event: a video's
 * {@code ownerId} is a coach-profile UUID or PlayerProfile id, never an address, and producing
 * listeners hold no repositories (see {@link NotificationOutboxSupport}'s "Failure semantics").
 * A null address means nobody is reachable; that is logged and skipped, never sent to a non-address.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoOwnerEmailListener {

    private final NotificationOutboxSupport notificationOutboxSupport;
    private final TransactionTemplate transactionTemplate;

    /**
     * Owner "your video was flagged" notice. The moderation pipeline publishes this from an
     * {@code @Async} thread with no transaction, where a published {@code Envelope} reaches neither
     * {@code MailManager} nor the inbox listener (both {@code fallbackExecution = false}) — the notice
     * used to be silently dropped (and addressed to the ownerId, which is not an email). It is now
     * enqueued on the outbox in its own short transaction, or the caller's if one is active.
     */
    @EventListener
    public void onModerationOwnerNotice(VideoModerationOwnerNotificationEvent event) {
        if (isBlank(event.recipientEmail())) {
            log.warn("[VIDEO_MODERATION_OWNER_NOTICE_NO_RECIPIENT] owner not reachable — notice not sent",
                kv("videoId", event.videoId()), kv("ownerId", event.ownerId()));
            return;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("message", event.message() != null ? event.message() : "");
        data.put("videoId", event.videoId() != null ? event.videoId().toString() : "N/A");
        Recipient recipient = recipient(event.recipientEmail(), event.recipientLangKey());
        try {
            transactionTemplate.executeWithoutResult(status -> notificationOutboxSupport.enqueueEmail(
                EmailTemplate.VIDEO_MODERATION_OWNER_FLAGGED, recipient, data, UUID.randomUUID().toString()));
        } catch (RuntimeException e) {
            // Never fail the moderation pipeline over the notice — the video is already LOCKED.
            log.error("Failed to prepare/publish notification", kv("template", EmailTemplate.VIDEO_MODERATION_OWNER_FLAGGED),
                kv("videoId", event.videoId()), e);
        }
    }

    private static Recipient recipient(String email, String langKey) {
        Recipient recipient = new Recipient();
        recipient.setEmail(email);
        recipient.setLangKey(isBlank(langKey) ? "en" : langKey);
        return recipient;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
