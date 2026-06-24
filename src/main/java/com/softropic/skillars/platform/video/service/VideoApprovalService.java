package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.service.PlayerProfileService;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.event.VideoApprovalOwnerNotificationEvent;
import com.softropic.skillars.platform.video.contract.event.VideoApprovalParentNotificationEvent;
import com.softropic.skillars.platform.video.contract.exception.VideoApprovalNotFoundException;
import com.softropic.skillars.platform.video.contract.exception.VideoAlreadyResolvedException;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoApprovalRequest;
import com.softropic.skillars.platform.video.repo.VideoApprovalRequestRepository;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class VideoApprovalService {

    private final VideoApprovalRequestRepository videoApprovalRequestRepository;
    private final VideoRepository videoRepository;
    private final VideoLifecycleService videoLifecycleService;
    private final VideoProviderAdapter videoProviderAdapter;
    private final VideoService videoService;
    private final PlayerProfileRepository playerProfileRepository;
    private final PlayerProfileService playerProfileService;
    private final ApplicationEventPublisher publisher;
    private final ConfigService configService;
    private final TransactionTemplate transactionTemplate;

    /**
     * Called by ModerationOrchestrationService after the minor safety gate fires.
     * Runs within the caller's transaction (no REQUIRES_NEW) so the HIDDEN state and
     * the approval row are committed atomically. Idempotent: skips if a PENDING row
     * for this video already exists.
     *
     * Returns {@code true} if the approval request was created (or already existed) and the video
     * should remain in HIDDEN awaiting parent action. Returns {@code false} for the unblockable-minor
     * fallback (no profile or no parent linked) — in that case this method transitions the video
     * HIDDEN→TRANSCODING itself. The caller is still responsible for the external Bunny
     * triggerTranscoding() call, which must happen AFTER the transaction commits (external HTTP calls
     * must not hold a DB connection open). The caller must NOT call advanceToTranscoding() — that
     * would attempt TRANSCODING→TRANSCODING, which is an invalid transition.
     */
    @Transactional
    public boolean createApprovalRequest(UUID videoId, Long playerId) {
        // Idempotent: if a PENDING approval already exists (e.g., moderation retry), skip
        if (videoApprovalRequestRepository.findByVideoIdAndStatus(videoId, "PENDING").isPresent()) {
            log.info("[VIDEO_APPROVAL_DUPLICATE_SKIPPED videoId={} playerId={}]", videoId, playerId);
            return true;
        }

        Video video = videoRepository.findById(videoId).orElse(null);
        if (video == null || video.getOperationalState() != OperationalState.HIDDEN) {
            throw new IllegalStateException(
                "createApprovalRequest called but video is not HIDDEN — videoId=" + videoId);
        }

        PlayerProfile profile = playerProfileRepository.findById(playerId).orElse(null);
        if (profile == null) {
            // Race: profile deleted between the outer gate check and this inner read.
            // Transition HIDDEN→TRANSCODING here so the video is never stuck in HIDDEN indefinitely.
            // The caller must still trigger Bunny encoding after the transaction commits — we only
            // handle the DB state transition; triggerTranscoding() is an external call that must not
            // run inside a transaction.
            log.warn("[VIDEO_APPROVAL_NO_PROFILE videoId={} playerId={}] PlayerProfile not found — transitioning HIDDEN→TRANSCODING as safe fallback", videoId, playerId);
            videoLifecycleService.transitionOperationalState(videoId, OperationalState.TRANSCODING);
            return false;
        }

        Long parentId = profile.getParentId();
        if (parentId == null) {
            // Minor whose parent account was deleted or was never linked — unblockable.
            // Transition HIDDEN→TRANSCODING here so the video is never stuck in HIDDEN indefinitely.
            // The caller must still trigger Bunny encoding after the transaction commits — we only
            // handle the DB state transition; triggerTranscoding() is an external call that must not
            // run inside a transaction.
            log.warn("[VIDEO_APPROVAL_NO_PARENT videoId={} playerId={}] No parent linked — transitioning HIDDEN→TRANSCODING as safe fallback", videoId, playerId);
            videoLifecycleService.transitionOperationalState(videoId, OperationalState.TRANSCODING);
            return false;
        }

        VideoApprovalRequest request = new VideoApprovalRequest();
        request.setVideoId(videoId);
        request.setPlayerId(playerId);
        request.setParentId(parentId);
        request.setStatus("PENDING");
        videoApprovalRequestRepository.save(request);

        if (configService.getBoolean("platform.video.approval.notification_enabled", true)) {
            String playerName = playerProfileService.getPlayerNameByPlayerId(playerId);
            String videoType = video.getVideoType() != null ? video.getVideoType().name() : "VIDEO";
            // Only primary parent (profile.parentId) is notified — secondary linked parents can
            // still approve/reject via RBAC but are not sent a notification here.
            log.info("[VIDEO_APPROVAL_CREATED videoId={} playerId={} parentId={}]", videoId, playerId, parentId);
            publisher.publishEvent(new VideoApprovalParentNotificationEvent(
                videoId, playerId, parentId, playerName, videoType, request.getCreatedAt()));
        } else {
            log.info("[VIDEO_APPROVAL_CREATED videoId={} playerId={} parentId={}]", videoId, playerId, parentId);
        }
        return true;
    }

    /**
     * Parent approves a video approval request.
     * Triggers actual Bunny encoding — the video was never submitted to Bunny before the HIDDEN gate intercepted it.
     * Without the triggerTranscoding() call the video stays un-encoded forever.
     *
     * DB work (status update + state transition) runs under a pessimistic write lock on the approval row
     * to prevent a concurrent approve call from also reaching triggerTranscoding(). The Bunny call happens
     * strictly after the transaction commits so Bunny cannot be called from within a connection-holding TX.
     */
    public void approveVideo(UUID approvalId, Long parentId) {
        final UUID[] videoIdRef = {null};
        final Long[] playerIdRef = {null};
        final String[] providerAssetIdRef = {null};
        final boolean[] encodingDoneRef = {false};
        final boolean[] idempotentRef = {false};

        transactionTemplate.execute(status -> {
            VideoApprovalRequest approval = videoApprovalRequestRepository.findByIdAndParentIdForUpdate(approvalId, parentId)
                .orElseThrow(() -> new VideoApprovalNotFoundException(approvalId));

            Video video = videoRepository.findById(approval.getVideoId()).orElse(null);

            // Idempotent guard: if the video has already advanced past HIDDEN (concurrent approve or race),
            // return silently. This check must come BEFORE the approval status check.
            if (video != null) {
                OperationalState state = video.getOperationalState();
                if (state == OperationalState.TRANSCODING || state == OperationalState.READY
                        || state == OperationalState.REJECTED || state == OperationalState.FAILED
                        || state == OperationalState.DELETED || state == OperationalState.PURGED) {
                    log.info("[VIDEO_APPROVAL_IDEMPOTENT approvalId={} videoId={} state={}] Already past HIDDEN — no-op", approvalId, approval.getVideoId(), state);
                    idempotentRef[0] = true;
                    return null;
                }
            }

            if (!"PENDING".equals(approval.getStatus())) {
                throw new VideoAlreadyResolvedException(approvalId, approval.getStatus());
            }

            if (video == null || video.getOperationalState() != OperationalState.HIDDEN) {
                throw new IllegalStateException(
                    "Video is not in HIDDEN state — cannot approve. videoId=" + approval.getVideoId());
            }

            approval.setStatus("APPROVED");
            approval.setResolvedAt(Instant.now());
            videoApprovalRequestRepository.save(approval);
            videoLifecycleService.transitionOperationalState(approval.getVideoId(), OperationalState.TRANSCODING);

            videoIdRef[0] = approval.getVideoId();
            playerIdRef[0] = approval.getPlayerId();
            providerAssetIdRef[0] = video.getProviderAssetId();
            encodingDoneRef[0] = video.getEncodingCompletedAt() != null;
            return null;
        });

        if (idempotentRef[0]) return;

        UUID videoId = videoIdRef[0];
        Long playerId = playerIdRef[0];
        String providerAssetId = providerAssetIdRef[0];

        // Trigger actual Bunny encoding after the transaction commits.
        // The video was never submitted to Bunny before the HIDDEN gate intercepted it.
        // Without this call the video stays un-encoded forever.
        // TODO: wire a video_encoding_retry_outbox pattern for failure recovery (SLA monitor watches
        //       SCANNING only — a stuck TRANSCODING video is not auto-rescued).
        if (encodingDoneRef[0]) {
            videoService.completeTranscoding(videoId);
        } else if (providerAssetId != null) {
            try {
                videoProviderAdapter.triggerTranscoding(providerAssetId);
            } catch (Exception e) {
                log.error("[VIDEO_APPROVAL_BUNNY_FAILED approvalId={} videoId={}] triggerTranscoding failed — video is stuck in TRANSCODING; manual ops intervention required: {}",
                          approvalId, videoId, e.getMessage(), e);
            }
        } else {
            log.error("[VIDEO_APPROVAL_BUNNY_FAILED approvalId={} videoId={}] providerAssetId is null — cannot trigger encoding",
                      approvalId, videoId);
        }

        publisher.publishEvent(new VideoApprovalOwnerNotificationEvent(videoId, playerId, "approved"));
        log.info("[VIDEO_APPROVAL_APPROVED approvalId={} videoId={} parentId={}]", approvalId, videoId, parentId);
    }

    /**
     * Parent rejects a video approval request.
     */
    @Transactional
    public void rejectVideo(UUID approvalId, Long parentId) {
        // Pessimistic lock — mirrors approveVideo — serialises concurrent reject calls so the second
        // concurrent call reads the already-REJECTED approval status and throws
        // VideoAlreadyResolvedException (409) rather than racing to a double state-transition
        // that ends in OptimisticLockException (500) on the Video @Version field.
        VideoApprovalRequest approval = videoApprovalRequestRepository.findByIdAndParentIdForUpdate(approvalId, parentId)
            .orElseThrow(() -> new VideoApprovalNotFoundException(approvalId));

        if ("CANCELLED".equals(approval.getStatus())) {
            // CANCELLED means the video was deleted while the approval was pending — treat as not found
            throw new VideoApprovalNotFoundException(approvalId);
        }
        if (!"PENDING".equals(approval.getStatus())) {
            throw new VideoAlreadyResolvedException(approvalId, approval.getStatus());
        }

        Video video = videoRepository.findById(approval.getVideoId()).orElse(null);

        // If video has already advanced past HIDDEN (concurrent approve won), return silently.
        // TRANSCODING→REJECTED is not in VALID_TRANSITIONS; calling transitionOperationalState from
        // any post-HIDDEN state would throw TerminalStateViolationException.
        if (video != null && video.getOperationalState() != OperationalState.HIDDEN) {
            OperationalState state = video.getOperationalState();
            if (state == OperationalState.REJECTED) {
                return; // already rejected — idempotent
            }
            log.info("[VIDEO_APPROVAL_REJECT_SKIPPED approvalId={} videoId={} state={}] Video no longer HIDDEN — concurrent approve won; skipping reject", approvalId, approval.getVideoId(), state);
            return;
        }

        approval.setStatus("REJECTED");
        approval.setResolvedAt(Instant.now());
        videoApprovalRequestRepository.save(approval);

        videoLifecycleService.transitionOperationalState(approval.getVideoId(), OperationalState.REJECTED);

        publisher.publishEvent(new VideoApprovalOwnerNotificationEvent(
            approval.getVideoId(), approval.getPlayerId(), "rejected"));
        log.info("[VIDEO_APPROVAL_REJECTED approvalId={} videoId={} parentId={}]", approvalId, approval.getVideoId(), parentId);
    }

    /**
     * Returns all PENDING approval requests for this parent's players.
     */
    @Transactional(readOnly = true)
    public List<VideoApprovalRequest> getPendingApprovalsForParent(Long parentId) {
        return videoApprovalRequestRepository.findByParentIdAndStatus(parentId, "PENDING");
    }
}
