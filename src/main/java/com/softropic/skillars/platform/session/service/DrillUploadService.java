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
import com.softropic.skillars.platform.video.repo.Video;
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
        // DRILL_VIDEO_ALREADY_LINKED guard instead of also creating a provider video. "Locked region"
        // means "while this method's transaction — and so the Postgres row lock findByIdForUpdate
        // took — is still open", not "inside the withBoundedRetry lambda": the lock is a DB-level
        // lock held until commit/rollback, so it is unaffected by where in this same transactional
        // method a statement runs.
        //
        // skillars-deferred-117 AC5: PessimisticLockRetryer.withBoundedRetry's contract requires the
        // retried supplier be read-only/side-effect-free — it can legitimately run more than once.
        // The writes (setVideoId/upsertVideoId) and the event publish used to be inside that lambda;
        // moved out to below (still inside this same @Transactional method, so "stays inside the
        // locked region" continues to hold exactly as before) so the supplier is now genuinely
        // read-only, not merely safe-by-luck via statement ordering.
        LockedDrillState locked = lockRetryer.withBoundedRetry("DrillUploadService.initiateUpload", () -> {
            drillRepository.findByIdForUpdate(drill.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                    "Drill was deleted by another user or no longer accessible", "drill"));
            // The locked entity return is discarded because drill is already managed by this
            // EntityManager (fetched at line 63). refresh() re-syncs the managed instance with
            // the locked database row without needing the returned entity.
            entityManager.refresh(drill, LockModeType.PESSIMISTIC_WRITE);

            Optional<DrillVideoRef> existing = drillVideoRefRepository.findByDrillId(drillId);
            UUID existingVideoId = existing.map(DrillVideoRef::getVideoId).orElse(null);
            // Deferred-89 AC3: hoisted out of the block below so the orphaned-reservation publish
            // further down can confirm the videos row still exists before re-queuing it for physical
            // deletion. Defence-in-depth: drill_video_refs.video_id has no FK (V38), so a ref can
            // point at a videoId whose videos row is already gone. Stays empty when there is no
            // prior video ref.
            Optional<Video> lockedVideo = Optional.empty();
            if (existingVideoId != null) {
                // Deferred-81 AC3. LOCK ORDERING: Drill (already held above) then Video, inside
                // this same retry block — deleteVideo below takes the pair in the identical
                // order, so the two methods can never form a Drill-then-Video / Video-then-Drill
                // cycle between them. Closes the cross-drill half of the Def14 TOCTOU race:
                // cloneDrill lets one videoId be shared by two Drill rows, so without this second
                // lock a call here and a deleteVideo/initiateUpload call on the other drill
                // sharing this videoId each take their own distinct Drill lock and never
                // serialize against each other's existsByVideoId check below. No entityManager
                // refresh needed here — unlike the drill row above, this is the first read of
                // this Video entity in this method, so findByIdForUpdate's own result already
                // reflects the now-locked row.
                lockedVideo = videoRepository.findByIdForUpdate(existingVideoId);
                if (lockedVideo.isPresent() && lockedVideo.get().getOperationalState() == OperationalState.READY) {
                    throw new OperationNotAllowedException(
                        "A video is already linked to this drill. Remove it before uploading a new one.",
                        SessionErrorCode.DRILL_VIDEO_ALREADY_LINKED);
                }
            }

            return new LockedDrillState(existing, existingVideoId, lockedVideo.isPresent());
        });

        InitializeUploadResponse resp = videoService.initializeUpload(
            new InitializeUploadRequest(coachId.toString(), req.fileName(), req.fileSizeBytes(),
                req.mimeType(), VideoType.DRILL_DEMO));

        if (locked.existing().isPresent()) {
            drillVideoRefRepository.setVideoId(drillId, resp.videoId());
            // Replacing a non-READY video's ref (PROCESSING/FAILED — a READY one already threw
            // above): the old reservation is otherwise orphaned until the reaper's timeout.
            // Mirrors deleteVideo's own check-and-publish ordering. Deferred-89 AC3: also gate on
            // lockedVideoPresent (the videos row held under the lock above) so an already-
            // soft-deleted / absent video is not re-queued for physical deletion.
            if (locked.existingVideoId() != null
                    && locked.lockedVideoPresent()
                    && !drillVideoRefRepository.existsByVideoId(locked.existingVideoId())) {
                eventPublisher.publishEvent(new VideoPhysicalDeletionEvent(locked.existingVideoId(), drillId));
            }
        } else {
            drillVideoRefRepository.upsertVideoId(drillId, resp.videoId());
        }

        return new DrillUploadInitiateResponse(resp.videoId(), resp.sessionId(), resp.signedUploadUrl(), resp.expiresAt());
    }

    /**
     * skillars-deferred-117 AC5: everything {@code initiateUpload}'s locked reads need to hand off
     * to the writes/publish that now run after {@code withBoundedRetry} returns.
     */
    private record LockedDrillState(Optional<DrillVideoRef> existing, UUID existingVideoId, boolean lockedVideoPresent) {
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
        // the ledger's Def14 double-publish race. "Locked region" reasoning mirrors initiateUpload's
        // comment above — the Postgres row lock is held for this whole transaction, not just for the
        // duration of the withBoundedRetry lambda.
        //
        // skillars-deferred-117 AC5: only the locked reads run inside withBoundedRetry now; the
        // clearVideoId write and event publish moved to below (still inside this same
        // @Transactional method) so the retried supplier is genuinely read-only.
        LockedVideoRef locked = lockRetryer.withBoundedRetry("DrillUploadService.deleteVideo", () -> {
            drillRepository.findByIdForUpdate(drill.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Drill not found", "drill"));
            entityManager.refresh(drill, LockModeType.PESSIMISTIC_WRITE);

            Optional<DrillVideoRef> ref = drillVideoRefRepository.findByDrillId(drillId);
            if (ref.isEmpty() || ref.get().getVideoId() == null) {
                return new LockedVideoRef(null, false);
            }
            UUID videoId = ref.get().getVideoId();

            // Deferred-81 AC3. LOCK ORDERING: Drill (already held above) then Video, inside
            // this same retry block — mirrors initiateUpload's identical ordering above, so
            // the two methods cannot deadlock against each other. Taken before clearVideoId
            // and the existsByVideoId check below, since those are exactly the check-then-act
            // pair this AC closes for a videoId shared across two Drill rows (see
            // initiateUpload's own comment for the full race description). Deferred-89 AC3: the
            // result is now captured (was discarded) — used for its locking side effect AND its
            // presence.
            Optional<Video> lockedVideo = videoRepository.findByIdForUpdate(videoId);
            return new LockedVideoRef(videoId, lockedVideo.isPresent());
        });

        if (locked.videoId() != null) {
            drillVideoRefRepository.clearVideoId(drillId);

            // Deferred-89 AC3: gate the re-publish on the videos row still existing.
            // drill_video_refs.video_id has no FK (V38), so a ref can outlive its videos row;
            // without this check an already-soft-deleted / absent video is re-queued for physical
            // deletion (downstream: a caught VideoNotFoundException + log noise).
            if (locked.lockedVideoPresent() && !drillVideoRefRepository.existsByVideoId(locked.videoId())) {
                eventPublisher.publishEvent(new VideoPhysicalDeletionEvent(locked.videoId(), drillId));
            }
        }
    }

    /** skillars-deferred-117 AC5: hand-off record for {@code deleteVideo}'s locked-then-write split. */
    private record LockedVideoRef(UUID videoId, boolean lockedVideoPresent) {
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
