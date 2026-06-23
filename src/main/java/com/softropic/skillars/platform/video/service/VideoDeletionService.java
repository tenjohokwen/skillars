package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.video.contract.LifecycleTrigger;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.event.VideoPhysicalDeletionEvent;
import com.softropic.skillars.platform.video.contract.exception.VideoDeletionNotAuthorisedException;
import com.softropic.skillars.platform.video.contract.exception.VideoNotFoundException;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoApprovalRequestRepository;
import com.softropic.skillars.platform.video.repo.VideoDeletionLog;
import com.softropic.skillars.platform.video.repo.VideoDeletionLogRepository;
import com.softropic.skillars.platform.video.repo.VideoDeletionOutbox;
import com.softropic.skillars.platform.video.repo.VideoDeletionOutboxRepository;
import com.softropic.skillars.platform.video.repo.VideoQuotaRepository;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoDeletionService {

    private static final int BATCH_SIZE = 100;

    private final VideoRepository videoRepository;
    private final VideoDeletionOutboxRepository outboxRepository;
    private final VideoDeletionLogRepository deletionLogRepository;
    private final VideoApprovalRequestRepository approvalRequestRepository;
    private final VideoQuotaRepository videoQuotaRepository;
    private final QuotaService quotaService;
    private final ConfigService configService;
    private final ApplicationEventPublisher publisher;
    private final VideoAccessGuard videoAccessGuard;

    /**
     * Central deletion method. Atomically marks PURGED, decrements quota (if skipQuotaDecrement=false),
     * inserts deletion log and outbox rows, and publishes VideoPhysicalDeletionEvent AFTER_COMMIT.
     * Direct field mutation is used — NOT markPurged() which asserts operationalState==READY.
     */
    @Transactional
    public void deleteVideo(UUID videoId, String triggeredBy, boolean skipQuotaDecrement) {
        Video video = videoRepository.findById(videoId)
            .orElseThrow(() -> new VideoNotFoundException(videoId));

        if (video.getOperationalState() == OperationalState.PURGED) {
            log.warn("[DELETION_IDEMPOTENT videoId={}] Video already PURGED, skipping", videoId);
            return;
        }

        long storageBytes = video.getStorageBytes() != null ? video.getStorageBytes() : 0L;
        String bunnyVideoId = video.getProviderAssetId();

        // Directly set PURGED — do NOT call transitionOperationalState() or markPurged()
        // providerAssetId is intentionally NOT nulled here; the outbox processor needs it to call
        // Bunny deleteAsset(). It is nulled in completeRowWithNullAsset() after confirmed deletion.
        video.setOperationalState(OperationalState.PURGED);
        video.setStorageBytes(0L);
        videoRepository.save(video);

        if (!skipQuotaDecrement) {
            quotaService.decrementStorageBytes(video.getOwnerId(), storageBytes);
        }

        VideoDeletionLog logRow = new VideoDeletionLog();
        logRow.setVideoId(videoId);
        logRow.setTriggeredBy(triggeredBy);
        logRow.setBunnyVideoId(bunnyVideoId);
        deletionLogRepository.save(logRow);

        if (!outboxRepository.existsByVideoIdAndStatus(videoId, "PENDING")) {
            VideoDeletionOutbox outbox = new VideoDeletionOutbox();
            outbox.setVideoId(videoId);
            outbox.setBunnyVideoId(bunnyVideoId);
            outbox.setTriggeredBy(triggeredBy);
            outboxRepository.save(outbox);
        }

        publisher.publishEvent(new VideoPhysicalDeletionEvent(videoId));
    }

    @Transactional
    public void deleteVideo(UUID videoId, String triggeredBy) {
        deleteVideo(videoId, triggeredBy, false);
    }

    /**
     * User-initiated deletion. Validates ownership (belt-and-suspenders behind @PreAuthorize),
     * cancels pending approval requests, and delegates to deleteVideo().
     */
    @Transactional
    public void deleteByUser(UUID videoId, String currentUser) {
        Video video = videoRepository.findById(videoId)
            .orElseThrow(() -> new VideoNotFoundException(videoId));

        if (video.getOperationalState() == OperationalState.PURGED) {
            log.debug("[DELETION_IDEMPOTENT videoId={}] Already PURGED — idempotent 204", videoId);
            return;
        }

        // Belt-and-suspenders: re-verify authorization, guarding against bypassed @PreAuthorize.
        // Wrapped in try-catch so non-HTTP callers (tests, internal services without a security
        // context) fail fast rather than silently — VideoAccessGuard.canDelete() will throw
        // IllegalStateException if no security context is present.
        try {
            videoAccessGuard.canDelete(null, videoId);
        } catch (VideoNotFoundException e) {
            log.debug("[DELETION_IDEMPOTENT videoId={}] Deleted concurrently between initial check and guard re-verify — idempotent 204", videoId);
            return;
        } catch (VideoDeletionNotAuthorisedException e) {
            log.warn("[DELETION_AUTH_BYPASS_ATTEMPT videoId={} caller={}] @PreAuthorize bypass detected", videoId, currentUser);
            throw e;
        } catch (Exception e) {
            // No security context (internal call) — trust the @PreAuthorize guard was already invoked
            log.warn("[DELETION_GUARD_SKIPPED videoId={} caller={}] Could not re-verify: {}", videoId, currentUser, e.getMessage());
        }

        if (configService.getBoolean("platform.video.approvalCancellation.enabled", true)) {
            approvalRequestRepository.cancelAllPendingForVideo(videoId);
        }

        try {
            deleteVideo(videoId, LifecycleTrigger.USER_DELETION, false);
        } catch (ObjectOptimisticLockingFailureException e) {
            log.info("[DELETION_CONCURRENT videoId={}] Concurrent delete detected — idempotent 204", videoId);
            // Already deleted by concurrent request — treat as idempotent
        }
    }

    /**
     * Account deletion cascade for a single ownerId. Batches video deletions, then resets quota.
     * NOT @Transactional at the method level — each deleteVideo() runs in its own transaction.
     */
    public void cascadeDeleteForAccount(String ownerId) {
        log.info("[VIDEO_ACCOUNT_DELETION] Starting cascade for ownerId={}", ownerId);
        int totalQueued = 0;
        Set<UUID> failedIds = new HashSet<>();
        boolean anyProgress;
        do {
            anyProgress = false;
            Page<Video> page = videoRepository.findByOwnerIdAndOperationalStateNot(
                ownerId, OperationalState.PURGED, PageRequest.of(0, BATCH_SIZE));
            if (page.isEmpty()) break;
            for (Video video : page.getContent()) {
                if (failedIds.contains(video.getId())) continue;
                try {
                    deleteVideo(video.getId(), LifecycleTrigger.ACCOUNT_DELETION, true);
                    totalQueued++;
                    anyProgress = true;
                    log.debug("[VIDEO_ACCOUNT_DELETION] Queued videoId={} for ownerId={}", video.getId(), ownerId);
                } catch (Exception e) {
                    failedIds.add(video.getId());
                    log.error("[ACCOUNT_DELETION_VIDEO_FAILURE videoId={} userId={}] Failed to purge video, continuing",
                        video.getId(), ownerId, e);
                }
            }
        } while (anyProgress);

        videoQuotaRepository.resetBytesForOwner(ownerId);
        log.info("[VIDEO_ACCOUNT_DELETION_COMPLETE userId={} videosQueued={} failed={}]", ownerId, totalQueued, failedIds.size());
    }

}
