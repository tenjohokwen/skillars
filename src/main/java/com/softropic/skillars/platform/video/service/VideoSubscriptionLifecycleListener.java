package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.payment.contract.event.SubscriptionExpiredEvent;
import com.softropic.skillars.platform.video.contract.PlayerSubscriptionQueryPort;
import com.softropic.skillars.platform.video.repo.SubscriptionLifecycleOutbox;
import com.softropic.skillars.platform.video.repo.SubscriptionLifecycleOutboxRepository;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class VideoSubscriptionLifecycleListener {

    private final SubscriptionLifecycleOutboxRepository outboxRepository;
    private final PlayerSubscriptionQueryPort playerSubscriptionQueryPort;
    private final VideoRepository videoRepository;
    private final VideoLifecycleService videoLifecycleService;
    private final ConfigService configService;

    /** Self-reference so each entry's @Transactional(REQUIRES_NEW) is applied via the Spring proxy. */
    @Autowired @Lazy
    private VideoSubscriptionLifecycleListener self;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSubscriptionExpired(SubscriptionExpiredEvent event) {
        if (event.getSubscriberId() == null) {
            log.warn("[SUB_LIFECYCLE] null subscriberId — skipping");
            return;
        }
        String tier = event.getSubscriptionTier();
        if (tier == null) {
            log.warn("[SUB_LIFECYCLE] null subscriptionTier for subscriberId={} — treating as non-YEARLY (Path B)",
                event.getSubscriberId());
        }
        SubscriptionLifecycleOutbox entry = new SubscriptionLifecycleOutbox();
        entry.setSubscriberId(event.getSubscriberId());
        entry.setSubscriptionTier(tier != null ? tier : "MONTHLY");
        entry.setExpiredAt(event.getExpiredAt());
        entry.setStatus("PENDING");
        outboxRepository.save(entry);
    }

    @Scheduled(fixedDelay = 60_000)
    public void processOutbox() {
        int maxAttempts = (int) configService.getLong("platform.video.lifecycle.outbox_max_attempts");

        List<SubscriptionLifecycleOutbox> pending =
            outboxRepository.findTop100ByStatusAndAttemptsLessThanOrderByCreatedAtAsc("PENDING", maxAttempts);

        for (SubscriptionLifecycleOutbox entry : pending) {
            // Each entry commits independently — one failure never rolls back sibling entries
            self.processAndSaveEntry(entry, maxAttempts);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processAndSaveEntry(SubscriptionLifecycleOutbox entry, int maxAttempts) {
        int batchSize = (int) configService.getLong("platform.video.lifecycle.batch_size");
        entry.setAttempts(entry.getAttempts() + 1);
        try {
            processEntry(entry, batchSize);
            entry.setStatus("PROCESSED");
            entry.setProcessedAt(Instant.now());
        } catch (Exception e) {
            log.error("[SUB_LIFECYCLE_FAILED id={} attempt={}]", entry.getId(), entry.getAttempts(), e);
            if (entry.getAttempts() >= maxAttempts) {
                entry.setStatus("DEAD_LETTER");
                entry.setLastError(e.getMessage());
                log.error("[SUB_LIFECYCLE_DEAD_LETTER id={} subscriberId={}] Max attempts reached — manual remediation required",
                    entry.getId(), entry.getSubscriberId());
            }
        }
        outboxRepository.save(entry);
    }

    private void processEntry(SubscriptionLifecycleOutbox entry, int batchSize) {
        boolean isYearly = "YEARLY".equalsIgnoreCase(entry.getSubscriptionTier());
        // player video ownerId is stored as Long.toString() decimal string
        String ownerId = entry.getSubscriberId().toString();

        if (isYearly) {
            // Path A: bulk-reset lifecycle_locked_at on all BLOCKED videos so the ARCHIVED clock pauses.
            // findBlockedReadyByOwner filters only by access_state='BLOCKED' and does not filter on
            // lifecycle_locked_at, so a paginated loop here would never terminate. The bulk reset is
            // sufficient to achieve FR-PAY-008 (pause the ARCHIVED clock for all BLOCKED videos).
            videoRepository.resetLifecycleLockedAt(ownerId);
            log.info("[SUB_LIFECYCLE_PATH_A_RESET subscriberId={}]", entry.getSubscriberId());
        } else {
            // Path B: block ACTIVE/READY videos if no other active subscription covers the player
            if (!playerSubscriptionQueryPort.hasAnyActiveSubscription(entry.getSubscriberId())) {
                List<com.softropic.skillars.platform.video.repo.Video> active;
                do {
                    active = videoRepository.findActiveReadyByOwner(ownerId, batchSize);
                    for (com.softropic.skillars.platform.video.repo.Video video : active) {
                        videoLifecycleService.blockForSubscriptionExpiry(video.getId(), Instant.now());
                    }
                } while (!active.isEmpty());
            } else {
                log.debug("[SUB_LIFECYCLE_SKIPPED_CONCURRENT_SUB subscriberId={}]", entry.getSubscriberId());
            }
        }
    }
}
