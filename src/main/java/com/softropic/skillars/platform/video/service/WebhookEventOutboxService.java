package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.video.repo.VideoWebhookEvent;
import com.softropic.skillars.platform.video.repo.VideoWebhookEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Handles idempotent webhook event insertion using a PostgreSQL ON CONFLICT DO NOTHING insert.
 *
 * <p>The native INSERT is atomic — concurrent duplicates are silently discarded at the DB level
 * without raising an exception or corrupting the JPA session state. This replaces the prior
 * REQUIRES_NEW + flush() + DataIntegrityViolationException approach, which caused Hibernate to
 * mark the EntityManager rollback-only, triggering an UnexpectedRollbackException at commit.
 */
@Service
@RequiredArgsConstructor
public class WebhookEventOutboxService {

    private final VideoWebhookEventRepository webhookEventRepository;

    public boolean tryInsert(VideoWebhookEvent event) {
        return webhookEventRepository.insertIfAbsent(
            event.getEventId(),
            event.getEventType(),
            event.getProviderAssetId(),
            event.getRawPayload(),
            event.getStatus().name()
        ) > 0;
    }
}
