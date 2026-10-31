package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.arachnid.ArachnidClient;
import com.softropic.skillars.infrastructure.arachnid.ArachnidScanResult;
import com.softropic.skillars.infrastructure.feature.AppFeature;
import com.softropic.skillars.infrastructure.feature.FeatureToggleService;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.infrastructure.videointel.VideoIntelClient;
import com.softropic.skillars.infrastructure.videointel.VideoIntelException;
import com.softropic.skillars.infrastructure.videointel.VideoIntelScanResult;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.event.VideoModerationAdminAlertEvent;
import com.softropic.skillars.platform.video.contract.event.VideoModerationOwnerNotificationEvent;
import com.softropic.skillars.platform.video.contract.event.VideoModerationRetryEvent;
import com.softropic.skillars.platform.video.contract.event.VideoUploadedEvent;
import com.softropic.skillars.platform.video.contract.exception.TerminalStateViolationException;
import com.softropic.skillars.platform.video.contract.exception.VideoNotFoundException;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import com.softropic.skillars.platform.security.contract.event.AccountSuspensionRequestedEvent;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class ModerationOrchestrationService {

    // skillars-deferred-107 AC2: 0 → moderation lock is stale on creation (the TOCTOU window RI-9
    // closes reopens); huge → permanently stuck rows. Mirrors ConfigBounds.MODERATION_LOCK_TIMEOUT_MINUTES.
    private static final long LOCK_TIMEOUT_MIN_MINUTES = 1L;
    private static final long LOCK_TIMEOUT_MAX_MINUTES = 1440L;

    private final VideoLifecycleService videoLifecycleService;
    private final VideoService videoService;
    private final VideoRepository videoRepository;
    private final VideoModerationScanPersistenceService scanPersistenceService;
    private final ArachnidClient arachnidClient;
    private final VideoIntelClient videoIntelClient;
    private final VideoProviderAdapter videoProviderAdapter;
    private final FeatureToggleService featureToggleService;
    private final ConfigService configService;
    private final ApplicationEventPublisher publisher;
    private final TransactionTemplate transactionTemplate;
    private final PlayerProfileRepository playerProfileRepository;
    private final MinorUploadPolicy minorUploadPolicy;
    private final VideoOwnerAccountResolver videoOwnerAccountResolver;

    @Async("moderationTaskExecutor")
    @Observed(name = "video.moderation.pipeline")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVideoUploaded(VideoUploadedEvent event) {
        UUID videoId = event.videoId();
        String ownerId = event.ownerId();
        log.info("Moderation pipeline starting for videoId={}", videoId);

        // Step 1: PROCESSING → SCANNING + acquire lock in same TX to close TOCTOU window (RI-9)
        try {
            transactionTemplate.execute(status -> {
                videoLifecycleService.transitionOperationalState(videoId, OperationalState.SCANNING);
                long lockMinutes = configService.getBoundedLong("platform.moderation_lock_timeout_minutes",
                    LOCK_TIMEOUT_MIN_MINUTES, LOCK_TIMEOUT_MAX_MINUTES);
                videoRepository.findById(videoId).ifPresent(v -> {
                    v.setModerationLockUntil(Instant.now().plus(lockMinutes, ChronoUnit.MINUTES));
                    videoRepository.save(v);
                });
                return null;
            });
        } catch (TerminalStateViolationException e) {
            log.warn("Video {} already in terminal state when moderation pipeline started — skipping. state={}",
                     videoId, e.getMessage());
            return;
        }

        String mediaUrl;
        try {
            mediaUrl = resolveMediaUrl(videoId);
        } catch (IllegalStateException e) {
            log.error("Cannot resolve media URL for videoId={}: {}", videoId, e.getMessage());
            transactionTemplate.execute(status -> {
                videoLifecycleService.transitionOperationalState(videoId, OperationalState.FAILED);
                return null;
            });
            alertAdmin(videoId, ownerId, "Moderation pipeline error — null providerAssetId",
                       "videoId=" + videoId + " — manual intervention required", false);
            return;
        }

        if (!runArachnidLayer(videoId, ownerId, mediaUrl)) return;
        if (!runVideoIntelLayer(videoId, ownerId, mediaUrl)) return;
        runMinorSafetyGate(videoId, ownerId);
    }

    @Async("moderationTaskExecutor")
    @EventListener
    public void onModerationRetry(VideoModerationRetryEvent event) {
        UUID videoId = event.videoId();
        String ownerId = event.ownerId();
        log.info("Moderation SLA retry for videoId={}", videoId);

        // Guard: confirm the video is still in SCANNING — a concurrent transition (LOCKED, READY,
        // TRANSCODING, FAILED, DELETED) between the SLA monitor's query and this retry event
        // firing would cause the pipeline to re-run on a video that already finished moderation.
        OperationalState[] currentState = {null};
        transactionTemplate.execute(status -> {
            videoRepository.findById(videoId).ifPresent(v -> currentState[0] = v.getOperationalState());
            return null;
        });
        if (currentState[0] == null) {
            log.warn("SLA retry skipped — videoId={} not found (deleted since SLA query)", videoId);
            return;
        }
        if (currentState[0] != OperationalState.SCANNING) {
            log.info("SLA retry skipped — videoId={} no longer in SCANNING (state={})", videoId, currentState[0]);
            return;
        }

        try {
            acquireModerationLock(videoId);
            String mediaUrl = resolveMediaUrl(videoId);

            if (!runArachnidLayer(videoId, ownerId, mediaUrl)) return;
            if (!runVideoIntelLayer(videoId, ownerId, mediaUrl)) return;
            runMinorSafetyGate(videoId, ownerId);
        } catch (TerminalStateViolationException e) {
            log.warn("Video {} already in terminal state during SLA retry — skipping. state={}",
                     videoId, e.getMessage());
        } catch (VideoNotFoundException e) {
            log.error("Video {} not found during SLA retry — transitioning to FAILED", videoId);
            transactionTemplate.execute(status -> {
                videoLifecycleService.transitionOperationalState(videoId, OperationalState.FAILED);
                return null;
            });
            alertAdmin(videoId, ownerId, "Moderation SLA retry error — video not found",
                       "videoId=" + videoId + " — manual intervention required", false);
        } catch (IllegalStateException e) {
            log.error("Cannot resolve media URL during SLA retry for videoId={}: {}", videoId, e.getMessage());
            transactionTemplate.execute(status -> {
                videoLifecycleService.transitionOperationalState(videoId, OperationalState.FAILED);
                return null;
            });
            alertAdmin(videoId, ownerId, "Moderation SLA retry error — null providerAssetId",
                       "videoId=" + videoId + " — manual intervention required", false);
        }
    }

    private boolean runArachnidLayer(UUID videoId, String ownerId, String mediaUrl) {
        if (!featureToggleService.isEnabled(AppFeature.ARACHNID_ENABLED)) {
            recordScan(videoId, "ARACHNID", "SKIPPED", null, "Feature flag disabled");
            log.debug("Arachnid disabled for videoId={} — skipping Layer 1", videoId);
            return true;
        }
        ArachnidScanResult result;
        try {
            result = arachnidClient.scan(mediaUrl);
        } catch (RuntimeException e) {
            // ArachnidException, or anything unexpected from the scanner side (bad payload, null body): no verdict is
            // never a pass (S-18). The video stays SCANNING and the SLA monitor retries it.
            log.error("Arachnid unavailable for videoId={}: {}", videoId, e.getMessage());
            recordScan(videoId, "ARACHNID", "FAILED", null, e.getMessage());
            alertAdmin(videoId, ownerId, "Arachnid moderation unavailable",
                       "videoId=" + videoId + " error=" + e.getMessage(), false);
            return false;
        }
        if (result != null && result.matched()) {
            String matchType = Objects.toString(result.matchType(), "UNKNOWN");
            log.error("CSAM MATCH — videoId={} matchType={}", videoId, matchType);
            try {
                transactionTemplate.execute(status -> {
                    videoLifecycleService.transitionOperationalState(videoId, OperationalState.LOCKED);
                    return null;
                });
            } catch (TerminalStateViolationException ex) {
                log.warn("CSAM LOCKED transition skipped — videoId={} already in terminal state", videoId);
            }
            recordScan(videoId, "ARACHNID", "FLAGGED", null, "matchType=" + matchType);
            // Alert BEFORE suspension: a CSAM match with no admin notification is worse than a failed suspension
            alertAdmin(videoId, ownerId, "CSAM match detected",
                       "videoId=" + videoId + " ownerId=" + ownerId + " matchType=" + matchType, true);
            try {
                suspendAccount(ownerId, videoId);
            } catch (Exception ex) {
                log.error("CRITICAL: suspendAccount failed for CSAM match ownerId={} videoId={}: {}",
                          ownerId, videoId, ex.getMessage(), ex);
                alertAdmin(videoId, ownerId, "CSAM match — account suspension FAILED",
                           "videoId=" + videoId + " ownerId=" + ownerId
                           + " suspensionError=" + ex.getMessage(), true);
            }
            return false;
        }
        if (result == null) {
            recordScan(videoId, "ARACHNID", "FAILED", null, "empty scan result");
            alertAdmin(videoId, ownerId, "Arachnid moderation unavailable", "videoId=" + videoId + " error=empty result", false);
            return false;
        }
        recordScan(videoId, "ARACHNID", "PASSED", null, null);
        return true;
    }

    private boolean runVideoIntelLayer(UUID videoId, String ownerId, String mediaUrl) {
        if (!featureToggleService.isEnabled(AppFeature.VIDEOINTEL_ENABLED)) {
            recordScan(videoId, "VIDEOINTEL", "SKIPPED", null, "Feature flag disabled");
            log.debug("VideoIntel disabled for videoId={} — skipping Layer 2", videoId);
            return true;
        }
        try {
            VideoIntelScanResult result = videoIntelClient.detectExplicitContent(mediaUrl);
            if (result.flagged()) {
                log.warn("VideoIntel flagged explicit content: videoId={} confidence={}", videoId, result.confidence());
                try {
                    transactionTemplate.execute(status -> {
                        videoLifecycleService.transitionOperationalState(videoId, OperationalState.LOCKED);
                        return null;
                    });
                } catch (TerminalStateViolationException ex) {
                    log.warn("VideoIntel LOCKED transition skipped — videoId={} already in terminal state", videoId);
                }
                recordScan(videoId, "VIDEOINTEL", "FLAGGED", result.confidence(), result.description());
                notifyOwner(ownerId, videoId, "Your video has been flagged for review");
                return false;
            }
            recordScan(videoId, "VIDEOINTEL", "PASSED", null, null);
            return true;
        } catch (VideoIntelException e) {
            log.error("VideoIntel unavailable for videoId={}: {}", videoId, e.getMessage());
            recordScan(videoId, "VIDEOINTEL", "FAILED", null, e.getMessage());
            alertAdmin(videoId, ownerId, "VideoIntel moderation unavailable",
                       "videoId=" + videoId + " error=" + e.getMessage(), false);
            return false;
        }
    }

    /**
     * Layer 3: defensive minor check — fail CLOSED, never "approve".
     *
     * <p>ADR "Minors never upload" (2026-10-28): a minor's upload is refused at initiation
     * ({@link MinorUploadPolicy#assertMayUpload}), so a player video reaching this layer should always
     * belong to a self-registered adult. This re-check only catches an invariant break (or a profile
     * changed since initiation): such a video is moved to HIDDEN — unplayable for everyone — and an
     * urgent admin alert is raised. It is never released to transcoding; the old parental-approval
     * path (VideoApprovalService) is gone.
     * <ul>
     *   <li>Coach-owned video (UUID ownerId): SKIPPED, advances to transcoding.</li>
     *   <li>Self-registered adult player: PASSED, advances to transcoding.</li>
     *   <li>Anything else (minor, managed profile, no DOB, profile missing): FLAGGED, HIDDEN, alert.</li>
     * </ul>
     */
    private void runMinorSafetyGate(UUID videoId, String ownerId) {
        // Detect coach-owned videos by attempting UUID parse — coach ownerId is a UUID string
        Long playerId;
        try {
            playerId = Long.parseLong(ownerId);
        } catch (NumberFormatException e) {
            recordScan(videoId, "MINOR_GATE", "SKIPPED", null, "Coach video — age check not applicable");
            advanceToTranscoding(videoId, ownerId);
            return;
        }

        PlayerProfile profile = playerProfileRepository.findById(playerId).orElse(null);
        String refusal = minorUploadPolicy.refusalReason(profile);
        if (refusal == null) {
            recordScan(videoId, "MINOR_GATE", "PASSED", null, null);
            advanceToTranscoding(videoId, ownerId);
            return;
        }

        log.error("[MINOR_GATE_FAIL_CLOSED videoId={} ownerId={} reason={}] player video from a profile that "
            + "may not upload reached moderation — hiding it", videoId, ownerId, refusal);
        try {
            transactionTemplate.execute(status -> {
                videoLifecycleService.transitionOperationalState(videoId, OperationalState.HIDDEN);
                return null;
            });
        } catch (TerminalStateViolationException e) {
            log.warn("MINOR_GATE HIDDEN transition skipped — videoId={} already in terminal state", videoId);
        }
        recordScan(videoId, "MINOR_GATE", "FLAGGED", null, "Upload by a profile that may not upload: " + refusal);
        alertAdmin(videoId, ownerId, "Minor-safety gate hid a player video",
            "videoId=" + videoId + " ownerId=" + ownerId + " reason=" + refusal
                + " — minors never upload; the upload-initiation refusal was bypassed or the profile changed. "
                + "The video is HIDDEN (unplayable). Review and delete it.", true);
    }

    private void advanceToTranscoding(UUID videoId, String ownerId) {
        // Phase 1: read providerAssetId + encodingCompletedAt in a short TX
        final String[] providerAssetId = {null};
        final boolean[] encodingDone = {false};
        transactionTemplate.execute(status -> {
            Video v = videoRepository.findById(videoId)
                .orElseThrow(() -> new VideoNotFoundException(videoId));
            providerAssetId[0] = v.getProviderAssetId();
            encodingDone[0] = v.getEncodingCompletedAt() != null;
            return null;
        });

        if (encodingDone[0]) {
            // Fast-path: encoding completed before moderation finished.
            // The webhook handler saw SCANNING and returned without calling completeTranscoding().
            // No one else will drive TRANSCODING→READY — we must do it here.
            log.info("Encoding already completed for videoId={} — advancing SCANNING→TRANSCODING→READY directly", videoId);
            try {
                transactionTemplate.execute(status -> {
                    videoLifecycleService.transitionOperationalState(videoId, OperationalState.TRANSCODING);
                    return null;
                });
            } catch (TerminalStateViolationException e) {
                log.warn("Fast-path TRANSCODING transition skipped — videoId={} already in terminal state", videoId);
                return;
            }
            videoService.completeTranscoding(videoId);
        } else {
            // Normal path: Phase 2 — trigger transcoding OUTSIDE any TX
            if (providerAssetId[0] == null) {
                log.error("providerAssetId is null for videoId={} — cannot trigger transcoding; transitioning to FAILED", videoId);
                transactionTemplate.execute(status -> {
                    videoLifecycleService.transitionOperationalState(videoId, OperationalState.FAILED);
                    return null;
                });
                alertAdmin(videoId, ownerId, "Moderation pipeline error — null providerAssetId",
                           "videoId=" + videoId + " — manual intervention required", false);
                return;
            }
            try {
                videoProviderAdapter.triggerTranscoding(providerAssetId[0]);
            } catch (Exception e) {
                // Transient Bunny outage — video stays in SCANNING. SLA monitor will retry.
                log.error("triggerTranscoding failed for videoId={} — leaving in SCANNING for SLA retry: {}",
                          videoId, e.getMessage(), e);
                alertAdmin(videoId, ownerId, "Transcoding trigger failed — video left in SCANNING",
                           "videoId=" + videoId + " error=" + e.getMessage(), false);
                return;
            }
            // Phase 3: transition SCANNING→TRANSCODING in a new TX
            transactionTemplate.execute(status -> {
                videoLifecycleService.transitionOperationalState(videoId, OperationalState.TRANSCODING);
                return null;
            });
            // TRANSCODING→READY happens when encoding.success webhook fires
        }
    }

    private void recordScan(UUID videoId, String layer, String outcome, Double confidence, String details) {
        try {
            scanPersistenceService.upsertScanRecord(videoId, layer, outcome, confidence, details);
        } catch (DataIntegrityViolationException e) {
            // Two concurrent pipeline threads raced on the same (video_id, layer) pair.
            // The other thread won the insert — treat as idempotent.
            log.warn("Concurrent recordScan race on videoId={} layer={} — treating as idempotent: {}",
                     videoId, layer, e.getMessage());
        }
    }

    private String resolveMediaUrl(UUID videoId) {
        Video video = transactionTemplate.execute(status ->
            videoRepository.findById(videoId).orElseThrow(() -> new VideoNotFoundException(videoId)));
        if (video == null || video.getProviderAssetId() == null) {
            throw new IllegalStateException(
                "Cannot resolve media URL: providerAssetId is null for videoId=" + videoId);
        }
        return videoProviderAdapter.getRawVideoUrl(video.getProviderAssetId());
    }

    private void acquireModerationLock(UUID videoId) {
        long lockMinutes = configService.getBoundedLong("platform.moderation_lock_timeout_minutes",
            LOCK_TIMEOUT_MIN_MINUTES, LOCK_TIMEOUT_MAX_MINUTES);
        transactionTemplate.execute(status -> {
            Video v = videoRepository.findById(videoId).orElse(null);
            if (v == null) {
                log.warn("acquireModerationLock: videoId={} not found — lock not acquired", videoId);
                return null;
            }
            v.setModerationLockUntil(Instant.now().plus(lockMinutes, ChronoUnit.MINUTES));
            videoRepository.save(v);
            return null;
        });
    }

    private void suspendAccount(String ownerId, UUID videoId) {
        // AccountSuspensionEventListener (synchronous @EventListener) resolves ownerId to the account
        // and throws when nothing resolves — the caller turns that into a "suspension FAILED" alert.
        publisher.publishEvent(new AccountSuspensionRequestedEvent(ownerId, videoId));
        log.warn("AccountSuspensionRequestedEvent published for ownerId={} triggeredByVideoId={}", ownerId, videoId);
    }

    private void alertAdmin(UUID videoId, String ownerId, String subject, String body, boolean urgent) {
        // Published inside a transaction: the admin-alert envelope is sent by MailManager's AFTER_COMMIT
        // listener (no fallbackExecution), which silently drops an envelope published with no
        // transaction active — and every caller here runs on the @Async pipeline thread outside one.
        // Joins the caller's transaction when there is one.
        VideoModerationAdminAlertEvent alert = new VideoModerationAdminAlertEvent(videoId, ownerId, subject, body, urgent);
        transactionTemplate.execute(status -> {
            publisher.publishEvent(alert);
            return null;
        });
        log.warn("VideoModerationAdminAlertEvent published: {} — {}", subject, body);
    }

    private void notifyOwner(String ownerId, UUID videoId, String message) {
        // ownerId is not an email address — resolve the owner's account email.
        // A lookup failure must not escape the @Async pipeline (the video is already LOCKED); the
        // listener logs and skips a null recipient.
        VideoOwnerAccountResolver.OwnerAccount recipient;
        try {
            recipient = videoOwnerAccountResolver.resolveNotificationRecipient(ownerId).orElse(null);
        } catch (RuntimeException e) {
            log.error("[VIDEO_MODERATION_OWNER_NOTICE_RESOLVE_FAILED videoId={} ownerId={}]", videoId, ownerId, e);
            recipient = null;
        }
        publisher.publishEvent(new VideoModerationOwnerNotificationEvent(videoId, ownerId, message,
            recipient != null ? recipient.email() : null, recipient != null ? recipient.langKey() : null));
        log.info("VideoModerationOwnerNotificationEvent published for ownerId={}: {}", ownerId, message);
    }
}
