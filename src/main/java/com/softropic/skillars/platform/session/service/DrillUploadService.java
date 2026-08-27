package com.softropic.skillars.platform.session.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.contract.CoachSubscriptionTier;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.security.contract.exception.FeatureGatedException;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.session.contract.DrillUploadInitiateRequest;
import com.softropic.skillars.platform.session.contract.DrillUploadInitiateResponse;
import com.softropic.skillars.platform.session.contract.SessionErrorCode;
import com.softropic.skillars.platform.session.contract.VideoPhysicalDeletionEvent;
import com.softropic.skillars.platform.session.contract.exception.DrillConstraintViolationException;
import com.softropic.skillars.platform.video.contract.exception.VideoValidationException;
import com.softropic.skillars.platform.session.repo.Drill;
import com.softropic.skillars.platform.session.repo.DrillRepository;
import com.softropic.skillars.platform.session.repo.DrillVideoRef;
import com.softropic.skillars.platform.session.repo.DrillVideoRefRepository;
import com.softropic.skillars.platform.video.contract.InitializeUploadRequest;
import com.softropic.skillars.platform.video.contract.InitializeUploadResponse;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.VideoType;
import com.softropic.skillars.platform.video.service.VideoTypeConstraints;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import com.softropic.skillars.platform.video.service.VideoService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
@Slf4j
@RequiredArgsConstructor
public class DrillUploadService {

    private static final String FEATURE_GATE_FULLY_DISABLED = "feature.gate.fully_disabled";

    private final DrillRepository drillRepository;
    private final DrillVideoRefRepository drillVideoRefRepository;
    private final VideoService videoService;
    private final VideoRepository videoRepository;
    private final ConfigService configService;
    private final CoachProfileService coachProfileService;
    private final ApplicationEventPublisher eventPublisher;
    private final VideoTypeConstraints videoTypeConstraints;
    private final MeterRegistry meterRegistry;
    private final EntityManager entityManager;
    private final PessimisticLockRetryer lockRetryer;

    public DrillUploadInitiateResponse initiateUpload(UUID drillId, Long coachUserId, DrillUploadInitiateRequest req) {
        UUID coachId = resolveCoachId(coachUserId);

        Drill drill = drillRepository.findById(drillId)
            .orElseThrow(() -> new ResourceNotFoundException("Drill not found", "drill"));

        if (!"PRIVATE".equals(drill.getLibraryType()) || !coachId.equals(drill.getOwnerCoachId())) {
            throw new OperationNotAllowedException("Drill upload not allowed", SessionErrorCode.DRILL_NOT_OWNED);
        }

        checkDrillUploadGate(coachId);

        // Delegates to platform.video module — single source of truth for type constraints.
        // VideoValidationException is translated here so it surfaces as a drill constraint violation
        // rather than falling through to ApiAdvice's catch-all Throwable handler.
        try {
            videoTypeConstraints.validate(VideoType.DRILL_DEMO, req.fileSizeBytes(), req.durationSeconds());
        } catch (VideoValidationException e) {
            throw new DrillConstraintViolationException("video", e.getMessage());
        }

        // Story Deferred-75 AC5: locks the Drill row for the duration of the check-then-act sequence
        // below, closing the TOCTOU race where two concurrent initiateUpload calls both pass the
        // existing/READY check before either commits its video-ref write. The provider call
        // (videoService.initializeUpload) stays inside the locked region so a second, lock-waiting
        // caller sees the first caller's committed write and correctly hits the READY/
        // DRILL_VIDEO_ALREADY_LINKED guard instead of also creating a provider video.
        return lockRetryer.withBoundedRetry(() -> {
            drillRepository.findByIdForUpdate(drill.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                    "Drill was deleted by another user or no longer accessible", "drill"));
            // The locked entity return is discarded because drill is already managed by this
            // EntityManager (fetched at line 63). refresh() re-syncs the managed instance with
            // the locked database row without needing the returned entity.
            entityManager.refresh(drill, LockModeType.PESSIMISTIC_WRITE);

            Optional<DrillVideoRef> existing = drillVideoRefRepository.findByDrillId(drillId);
            UUID existingVideoId = existing.map(DrillVideoRef::getVideoId).orElse(null);
            if (existingVideoId != null) {
                videoRepository.findById(existingVideoId).ifPresent(video -> {
                    if (video.getOperationalState() == OperationalState.READY) {
                        throw new OperationNotAllowedException(
                            "A video is already linked to this drill. Remove it before uploading a new one.",
                            SessionErrorCode.DRILL_VIDEO_ALREADY_LINKED);
                    }
                });
            }

            InitializeUploadResponse resp = videoService.initializeUpload(
                new InitializeUploadRequest(coachId.toString(), req.fileName(), req.fileSizeBytes(),
                    req.mimeType(), VideoType.DRILL_DEMO));

            if (existing.isPresent()) {
                drillVideoRefRepository.setVideoId(drillId, resp.videoId());
                // Replacing a non-READY video's ref (PROCESSING/FAILED — a READY one already threw
                // above): the old reservation is otherwise orphaned until the reaper's timeout.
                // Mirrors deleteVideo's own check-and-publish ordering.
                if (existingVideoId != null && !drillVideoRefRepository.existsByVideoId(existingVideoId)) {
                    eventPublisher.publishEvent(new VideoPhysicalDeletionEvent(existingVideoId, drillId));
                }
            } else {
                drillVideoRefRepository.upsertVideoId(drillId, resp.videoId());
            }

            return new DrillUploadInitiateResponse(resp.videoId(), resp.sessionId(), resp.signedUploadUrl(), resp.expiresAt());
        });
    }

    public void deleteVideo(UUID drillId, Long coachUserId) {
        UUID coachId = resolveCoachId(coachUserId);

        Drill drill = drillRepository.findById(drillId)
            .orElseThrow(() -> new ResourceNotFoundException("Drill not found", "drill"));

        if (!"PRIVATE".equals(drill.getLibraryType()) || !coachId.equals(drill.getOwnerCoachId())) {
            throw new OperationNotAllowedException("Drill upload not allowed", SessionErrorCode.DRILL_NOT_OWNED);
        }

        // Story Deferred-75 AC5: locks the Drill row so two concurrent deletes on the same drillId
        // cannot both observe existsByVideoId()==false before either commits its own clear, closing
        // the ledger's Def14 double-publish race.
        lockRetryer.withBoundedRetry(() -> {
            drillRepository.findByIdForUpdate(drill.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Drill not found", "drill"));
            entityManager.refresh(drill, LockModeType.PESSIMISTIC_WRITE);

            drillVideoRefRepository.findByDrillId(drillId).ifPresent(ref -> {
                if (ref.getVideoId() == null) return;
                UUID videoId = ref.getVideoId();

                drillVideoRefRepository.clearVideoId(drillId);

                if (!drillVideoRefRepository.existsByVideoId(videoId)) {
                    eventPublisher.publishEvent(new VideoPhysicalDeletionEvent(videoId, drillId));
                }
            });
            return null;
        });
    }

    @Transactional(readOnly = true)
    public boolean isVideoUploadEligible(Long coachUserId) {
        try {
            UUID coachId = resolveCoachId(coachUserId);
            CoachSubscriptionTier tier = coachProfileService.getCoachSubscriptionTier(coachId);
            return configService.getBoolean("feature.drillVideoUpload.enabled." + tier.name());
        } catch (Exception e) {
            return false;
        }
    }

    private void checkDrillUploadGate(UUID coachId) {
        CoachSubscriptionTier tier = coachProfileService.getCoachSubscriptionTier(coachId);
        boolean enabled = configService.getBoolean("feature.drillVideoUpload.enabled." + tier.name());
        if (!enabled) {
            throw new FeatureGatedException("drill_video_upload", resolveMinUploadTier());
        }
    }

    private String resolveMinUploadTier() {
        for (CoachSubscriptionTier t : CoachSubscriptionTier.values()) {
            if (configService.find("feature.drillVideoUpload.enabled." + t.name())
                    .map("true"::equalsIgnoreCase).orElse(false)) {
                return t.name();
            }
        }
        log.warn("No CoachSubscriptionTier has feature.drillVideoUpload.enabled.* set to true — "
                + "drill_video_upload is unreachable for every coach regardless of subscription");
        Counter.builder(FEATURE_GATE_FULLY_DISABLED)
            .tag("feature", "drillVideoUpload")
            .register(meterRegistry)
            .increment();
        return null;
    }

    private UUID resolveCoachId(Long userId) {
        return coachProfileService.getCoachIdByUserId(userId);
    }
}
