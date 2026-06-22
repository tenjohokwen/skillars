package com.softropic.skillars.platform.video.api;

import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.infrastructure.video.WebhookEvent;
import com.softropic.skillars.platform.video.contract.VideoWebhookStatus;
import com.softropic.skillars.platform.video.contract.exception.VideoProviderException;
import com.softropic.skillars.platform.video.repo.VideoWebhookEvent;
import com.softropic.skillars.platform.video.repo.VideoWebhookEventRepository;
import com.softropic.skillars.platform.video.service.WebhookEventOutboxService;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/video")
@RequiredArgsConstructor
public class VideoWebhookResource {

    private final VideoProviderAdapter videoProviderAdapter;
    private final VideoWebhookEventRepository webhookEventRepository;
    private final WebhookEventOutboxService webhookEventOutboxService;

    // intentional — HMAC signature verification is the authentication mechanism
    @PreAuthorize("permitAll()")
    @Observed(name = "video.webhook.receive")
    @PostMapping("/webhooks/bunny")
    public ResponseEntity<Void> receiveBunnyWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "X-BunnyStream-Signature", required = false, defaultValue = "") String signature) {

        WebhookEvent event;
        try {
            event = videoProviderAdapter.verifyWebhook(payload, signature);
        } catch (VideoProviderException e) {
            log.warn("Webhook verification failed — rejecting payload");
            return ResponseEntity.badRequest().build();
        }

        // videoLibraryId + providerAssetId + eventType is stable across re-deliveries; using timestamp
        // breaks idempotency because Bunny sends no timestamp (Instant.now() varies per delivery).
        // Including videoLibraryId scopes dedup per library, preventing cross-library guid collisions.
        String eventId = event.videoLibraryId() + ":" + event.providerAssetId() + ":" + event.eventType();

        if (webhookEventRepository.existsByEventId(eventId)) {
            return ResponseEntity.ok().build();
        }

        VideoWebhookEvent outboxEvent = new VideoWebhookEvent();
        outboxEvent.setEventId(eventId);
        outboxEvent.setEventType(event.eventType());
        outboxEvent.setProviderAssetId(event.providerAssetId());
        outboxEvent.setRawPayload(payload); // stored for auditability — never logged
        outboxEvent.setStatus(VideoWebhookStatus.PENDING);

        // tryInsert uses REQUIRES_NEW + explicit flush() so the unique-constraint violation
        // on event_id is thrown and caught within the nested TX, not at the caller's commit
        if (!webhookEventOutboxService.tryInsert(outboxEvent)) {
            log.debug("Duplicate webhook delivery absorbed for eventId={}", eventId);
        }
        return ResponseEntity.ok().build();
    }
}
