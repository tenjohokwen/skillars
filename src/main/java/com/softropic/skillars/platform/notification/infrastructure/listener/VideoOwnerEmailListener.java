package com.softropic.skillars.platform.notification.infrastructure.listener;

import com.softropic.skillars.infrastructure.web.FrontendLinkBuilder;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.notification.service.NotificationOutboxSupport;
import com.softropic.skillars.platform.video.contract.event.VideoApprovalOwnerNotificationEvent;
import com.softropic.skillars.platform.video.contract.event.VideoApprovalParentNotificationEvent;
import com.softropic.skillars.platform.video.contract.event.VideoModerationOwnerNotificationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * User-facing video notices: the minor-safety approval gate (parent asked to approve; player told the
 * outcome) and the owner's "your video was flagged" notice. All three go through the durable
 * notification outbox ({@link NotificationOutboxSupport#enqueueEmail}), which also writes the in-app
 * inbox row. All are {@code MODERATION} category — transactional, never opt-out-able.
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

    static final String PARENT_APPROVALS_ROUTE = "parent/approvals";
    static final String PLAYER_VIDEOS_ROUTE = "player/videos";

    private final NotificationOutboxSupport notificationOutboxSupport;
    private final FrontendLinkBuilder frontendLinkBuilder;
    private final TransactionTemplate transactionTemplate;

    /**
     * Published by {@code VideoApprovalService.createApprovalRequest} inside the transaction that
     * creates the PENDING approval row, so the email/inbox row commit atomically with it.
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onApprovalRequested(VideoApprovalParentNotificationEvent event) {
        if (isBlank(event.parentEmail())) {
            log.warn("[VIDEO_APPROVAL_NOTICE_NO_RECIPIENT] parent has no email — approval request not emailed",
                kv("videoId", event.videoId()), kv("playerId", event.playerId()), kv("parentId", event.parentId()));
            return;
        }
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("playerName", event.playerName() != null ? event.playerName() : "");
            data.put("videoType", event.videoType() != null ? event.videoType() : "");
            data.put("approvalsUrl", frontendLinkBuilder.route(PARENT_APPROVALS_ROUTE));
            notificationOutboxSupport.enqueueEmail(EmailTemplate.VIDEO_APPROVAL_REQUESTED,
                recipient(event.parentEmail(), event.parentLangKey()), data, UUID.randomUUID().toString());
        } catch (Exception e) {
            log.error("Failed to prepare/publish notification", kv("template", EmailTemplate.VIDEO_APPROVAL_REQUESTED),
                kv("videoId", event.videoId()), e);
        }
    }

    /**
     * Published by {@code VideoApprovalService.approveVideo}/{@code rejectVideo} inside the decision's
     * transaction (approve used to publish after its transaction had committed, where a
     * BEFORE_COMMIT listener never fires).
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onApprovalDecided(VideoApprovalOwnerNotificationEvent event) {
        EmailTemplate template = "approved".equals(event.decision())
            ? EmailTemplate.VIDEO_APPROVAL_APPROVED
            : "rejected".equals(event.decision()) ? EmailTemplate.VIDEO_APPROVAL_REJECTED : null;
        if (template == null) {
            log.error("[VIDEO_APPROVAL_NOTICE_UNKNOWN_DECISION] not emailed",
                kv("videoId", event.videoId()), kv("decision", event.decision()));
            return;
        }
        if (isBlank(event.recipientEmail())) {
            log.info("[VIDEO_APPROVAL_NOTICE_NO_RECIPIENT] player account has no email — decision not emailed",
                kv("videoId", event.videoId()), kv("playerId", event.playerId()), kv("decision", event.decision()));
            return;
        }
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("videosUrl", frontendLinkBuilder.route(PLAYER_VIDEOS_ROUTE));
            notificationOutboxSupport.enqueueEmail(template,
                recipient(event.recipientEmail(), event.recipientLangKey()), data, UUID.randomUUID().toString());
        } catch (Exception e) {
            log.error("Failed to prepare/publish notification", kv("template", template),
                kv("videoId", event.videoId()), e);
        }
    }

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
