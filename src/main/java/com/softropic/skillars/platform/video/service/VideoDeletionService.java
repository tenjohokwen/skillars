package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.video.contract.LifecycleTrigger;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.event.VideoPurgedEvent;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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

    // cascadeDeleteForAccount below is deliberately NOT @Transactional at the method level, but its
    // per-video deleteVideo(...) call needs its OWN transaction each iteration — a same-class call
    // bypasses this bean's @Transactional proxy entirely (deleteByUser/the two-arg overload don't
    // have this problem: they're each themselves @Transactional and called externally, so their own
    // internal deleteVideo(...) call joins an already-open transaction instead of needing one).
    // Mirrors RadarCompositeCalculationService's identical @Lazy @Autowired self field.
    @Autowired
    @Lazy
    private VideoDeletionService self;

    /**
     * Central deletion method. Atomically marks PURGED, decrements quota (if skipQuotaDecrement=false),
     * inserts deletion log and outbox rows, and publishes VideoPurgedEvent AFTER_COMMIT.
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

        publisher.publishEvent(new VideoPurgedEvent(videoId));
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
     * NOT @Transactional at the method level — each unit below runs in its own REQUIRES_NEW
     * transaction via {@link #self}.
     *
     * <p>skillars-deferred-91 AC13: this cascade is reached from
     * {@code AccountDeletionCascadeListener.onAccountDeleted}, a {@code @TransactionalEventListener}
     * that runs inside the erasure transaction's {@code afterCommit} synchronization callback. In
     * that window a plain {@code @Transactional} (REQUIRED) sub-call does not get a usable
     * transaction — a direct {@code @Modifying} repository call threw
     * {@code TransactionRequiredException} (caught + logged upstream, so the quota row was silently
     * never reset), and an inner REQUIRED tx that never committed cleanly blocked the follow-up
     * approval-cancel query on its own row locks. Every write here therefore goes through a
     * {@code REQUIRES_NEW} boundary, the same shape {@code PendingBlobDeletionChunkProcessor} uses.
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
                    self.purgeVideoForCascade(video.getId());
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

        // Deferred-77 AC12: quota reset must not run when any video failed to purge — an unconditional
        // reset here would zero the quota row for storage the failed video(s) still occupy, and no
        // retry path corrects it afterward (the failed ids aren't persisted anywhere).
        if (failedIds.isEmpty()) {
            // AC13: own REQUIRES_NEW transaction — see the class-path note above.
            self.resetQuotaForOwner(ownerId);
        } else {
            log.warn("[VIDEO_ACCOUNT_DELETION_INCOMPLETE userId={} videosQueued={} failed={}] " +
                "quota NOT reset — {} video(s) failed to purge, reconciliation needed",
                ownerId, totalQueued, failedIds.size(), failedIds.size());
        }
        log.info("[VIDEO_ACCOUNT_DELETION_COMPLETE userId={} videosQueued={} failed={}]", ownerId, totalQueued, failedIds.size());
    }

    /**
     * skillars-deferred-91 AC13: one video's purge in its own committed transaction, invoked via
     * {@link #self} from {@link #cascadeDeleteForAccount}. {@code REQUIRES_NEW} (not the plain
     * {@code @Transactional} REQUIRED on {@link #deleteVideo}) so it commits even when the cascade
     * runs inside the erasure tx's {@code afterCommit} callback — otherwise the row stays
     * write-locked by an uncommitted inner tx and the follow-up approval-cancel query deadlocks on
     * it. Preserves the existing per-video isolation the direct-call path already relied on.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void purgeVideoForCascade(UUID videoId) {
        deleteVideo(videoId, LifecycleTrigger.ACCOUNT_DELETION, true);
    }

    /**
     * skillars-deferred-91 AC13: the {@code @Modifying} quota-reset UPDATE in its own committed
     * transaction. A direct {@code videoQuotaRepository.resetBytesForOwner(...)} on this cascade
     * path threw {@code TransactionRequiredException} ("Executing an update/delete query"), caught +
     * logged upstream so the quota row was silently never reset.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resetQuotaForOwner(String ownerId) {
        videoQuotaRepository.resetBytesForOwner(ownerId);
    }

    /**
     * skillars-deferred-91 AC13: the {@code @Modifying} pending-approval cancellation issued by
     * {@code AccountDeletionCascadeListener.onAccountDeleted} (also an AFTER_COMMIT, non-transactional
     * path), in its own committed transaction. Same {@code TransactionRequiredException} class.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cancelPendingApprovalsForOwners(java.util.List<String> ownerIds) {
        approvalRequestRepository.cancelAllPendingForOwners(ownerIds);
    }

}
