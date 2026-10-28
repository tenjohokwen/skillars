package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.video.AssetStatus;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.video.contract.*;
import com.softropic.skillars.platform.video.contract.exception.VideoNotFoundException;
import com.softropic.skillars.platform.video.contract.exception.VideoValidationException;
import com.softropic.skillars.platform.video.repo.*;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminVideoService {

    private final VideoRepository videoRepository;
    private final UploadSessionRepository uploadSessionRepository;
    private final ReconciliationIncidentRepository incidentRepository;
    private final VideoLifecycleService videoLifecycleService;
    private final VideoProviderAdapter videoProviderAdapter;
    private final QuotaProvider quotaProvider;
    private final TransactionTemplate transactionTemplate;
    private final VideoModerationScanRepository moderationScanRepository;
    private final VideoDeletionService videoDeletionService;

    static final int REVIEW_QUEUE_PAGE_SIZE = 20;
    private static final String SCAN_OUTCOME_FLAGGED = "FLAGGED";

    /**
     * Admin video review queue (story 6.3 / FR-ADM-002): videos that need an operator — moderation
     * LOCKED, FAILED, or playback BLOCKED — most recently changed first. Read-only; actions go through
     * the existing {@link #setVideoAccessState}, {@link #deleteVideo} and {@link #triggerReconciliation}.
     */
    @Observed(name = "video.admin.reviewQueue")
    @Transactional(readOnly = true)
    public Page<AdminVideoQueueItemDto> getReviewQueue(AdminVideoQueueFilter filter, int page) {
        AdminVideoQueueFilter f = filter == null ? AdminVideoQueueFilter.ATTENTION : filter;
        boolean all = f == AdminVideoQueueFilter.ATTENTION;
        Pageable pageable = PageRequest.of(Math.max(0, page), REVIEW_QUEUE_PAGE_SIZE);
        Page<Video> videos = videoRepository.findAdminReviewQueue(
                all || f == AdminVideoQueueFilter.LOCKED,
                all || f == AdminVideoQueueFilter.FAILED,
                all || f == AdminVideoQueueFilter.BLOCKED,
                pageable);
        Map<UUID, List<String>> flagged = flaggedLayersFor(videos.getContent().stream().map(Video::getId).toList());
        return videos.map(v -> toQueueItem(v, flagged.getOrDefault(v.getId(), List.of())));
    }

    /** Single-video admin lookup, same shape as a queue row; lets the admin act on any video by id. */
    @Observed(name = "video.admin.getVideo")
    @Transactional(readOnly = true)
    public AdminVideoQueueItemDto getVideo(UUID videoId) {
        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new VideoNotFoundException(videoId));
        return toQueueItem(video, flaggedLayersFor(List.of(videoId)).getOrDefault(videoId, List.of()));
    }

    private Map<UUID, List<String>> flaggedLayersFor(List<UUID> videoIds) {
        if (videoIds.isEmpty()) {
            return Map.of();
        }
        return moderationScanRepository.findByVideoIdInAndOutcome(videoIds, SCAN_OUTCOME_FLAGGED).stream()
                .collect(Collectors.groupingBy(VideoModerationScan::getVideoId,
                        Collectors.mapping(VideoModerationScan::getLayer,
                                Collectors.collectingAndThen(Collectors.toList(),
                                        l -> l.stream().sorted().toList()))));
    }

    private static AdminVideoQueueItemDto toQueueItem(Video v, List<String> flaggedLayers) {
        return new AdminVideoQueueItemDto(v.getId(), v.getTitle(), v.getOperationalState(), v.getAccessState(),
                v.getOwnerId(), v.getVisibility(), v.getVideoType() != null ? v.getVideoType().name() : null,
                v.getCreatedAt(), v.getUpdatedAt(), v.getLifecycleLockedAt(), v.getArchivedAt(), flaggedLayers);
    }

    @Observed(name = "video.admin.setAccessState")
    public Video setVideoAccessState(UUID videoId, AccessState newAccessState) {
        MDC.put("operation", "admin.setAccessState");
        MDC.put("videoId", videoId.toString());
        try {
            return videoLifecycleService.setAccessState(videoId, newAccessState);
        } finally {
            MDC.remove("operation");
            MDC.remove("videoId");
        }
    }

    /**
     * Admin delete. Routed through {@link VideoDeletionService#deleteByAdmin} so it gets the same
     * bookkeeping as every other delete: PURGED state, storage-quota decrement for committed bytes, a
     * {@code video_deletion_log} row, and an outbox row whose processor deletes the Bunny asset only when
     * no other drill still references it ({@code DrillVideoRef} ref-count). The previous bespoke path
     * set DELETED, deleted the Bunny asset inline with no ref-count check (taking a shared drill demo
     * away from every other session using it), never decremented {@code storage_used_bytes} for a READY
     * video and wrote no deletion log.
     */
    @Observed(name = "video.admin.deleteVideo")
    public void deleteVideo(UUID videoId) {
        MDC.put("operation", "admin.deleteVideo");
        MDC.put("videoId", videoId.toString());
        try {
            // Phase 1 (one transaction): cancel pending approvals, expire a PENDING upload session, purge.
            try {
                videoDeletionService.deleteByAdmin(videoId);
            } catch (ObjectOptimisticLockingFailureException e) {
                // Lost a race on the Video row. If the winner purged it, this delete is a no-op;
                // anything else (e.g. transcoding finished concurrently) is a real conflict — surface it.
                boolean purged = videoRepository.findById(videoId)
                        .map(v -> v.getOperationalState() == OperationalState.PURGED)
                        .orElse(true);
                if (!purged) {
                    throw e;
                }
                log.info("[DELETION_CONCURRENT videoId={}] Admin delete raced another delete — idempotent", videoId);
            }

            // Phase 2: release an ACTIVE (never-committed) quota reservation OUTSIDE any transaction —
            // same pattern as VideoService.failTranscoding. release() only flips ACTIVE→RELEASED, so for
            // a committed (READY) video it is a no-op and the committed bytes were decremented in Phase 1.
            // Deferred-64 AC5: no PENDING filter here (Phase 1 may already have flipped it to EXPIRED),
            // gated on quotaReleasedAt == null so a repeat call retries exactly a failed release.
            UploadSession session = uploadSessionRepository.findFirstByVideoIdOrderByCreatedAtDesc(videoId)
                    .orElse(null);
            if (session != null && session.getReservationHandle() != null && session.getQuotaReleasedAt() == null) {
                try {
                    quotaProvider.release(session.getReservationHandle());
                    session.setQuotaReleasedAt(java.time.Instant.now());
                    uploadSessionRepository.save(session);
                } catch (RuntimeException ex) {
                    log.error("Quota release failed for videoId={} reservationHandle={} — retriable on next delete call",
                        videoId, session.getReservationHandle(), ex);
                    throw ex;
                }
            }

            log.info("Admin deleted video {}", videoId);
        } finally {
            MDC.remove("operation");
            MDC.remove("videoId");
        }
    }

    @Observed(name = "video.admin.getUploadSession")
    public UploadSession getUploadSession(UUID uploadSessionId) {
        MDC.put("operation", "admin.getUploadSession");
        MDC.put("uploadSessionId", uploadSessionId.toString());
        try {
            return uploadSessionRepository.findById(uploadSessionId)
                    .orElseThrow(() -> new VideoValidationException(
                            "No upload session found for id: " + uploadSessionId));
        } finally {
            MDC.remove("operation");
            MDC.remove("uploadSessionId");
        }
    }

    @Observed(name = "video.admin.listVideoSessions")
    public List<UploadSession> listVideoSessions(UUID videoId) {
        MDC.put("operation", "admin.listVideoSessions");
        MDC.put("videoId", videoId.toString());
        try {
            videoRepository.findById(videoId)
                    .orElseThrow(() -> new VideoNotFoundException(videoId));
            return uploadSessionRepository.findAllByVideoIdOrderByCreatedAtDesc(videoId);
        } finally {
            MDC.remove("operation");
            MDC.remove("videoId");
        }
    }

    @Observed(name = "video.admin.triggerReconciliation")
    public ReconcileResponse triggerReconciliation(UUID videoId) {
        MDC.put("operation", "admin.triggerReconciliation");
        MDC.put("videoId", videoId.toString());
        try {
            Video video = videoRepository.findById(videoId)
                    .orElseThrow(() -> new VideoNotFoundException(videoId));

            // PURGED too: every delete (admin included) now ends in PURGED, and the deletion outbox
            // removes the Bunny asset — reconciling would try PURGED→FAILED on a "missing" asset.
            if (video.getOperationalState() == OperationalState.DELETED
                    || video.getOperationalState() == OperationalState.PURGED) {
                return new ReconcileResponse(video.getId(), video.getOperationalState(), video.getAccessState(), null);
            }

            if (video.getProviderAssetId() == null) {
                return new ReconcileResponse(video.getId(), video.getOperationalState(), video.getAccessState(), null);
            }

            // Provider call outside @Transactional
            AssetStatus providerStatus = videoProviderAdapter.getAssetStatus(video.getProviderAssetId());

            return Objects.requireNonNull(transactionTemplate.execute(txStatus -> {
                Video fresh = videoRepository.findById(videoId)
                        .orElseThrow(() -> new VideoNotFoundException(videoId));

                ReconciliationIncident incident = null;

                if (providerStatus == AssetStatus.READY && fresh.getOperationalState() == OperationalState.PROCESSING) {
                    // skillars-deferred-100 AC5: reconcileToReady() — this admin-triggered correction
                    // is legitimate and must not trip the video.moderation.bypass alarm.
                    boolean corrected = videoLifecycleService.reconcileToReady(videoId, "admin reconcile: provider READY, local PROCESSING");
                    if (corrected) {
                        incident = saveIncident(fresh, ReconciliationIncidentType.STATE_CORRECTED,
                                "Admin reconcile: local PROCESSING corrected to READY");
                        log.info("Admin reconcile STATE_CORRECTED for video {}: PROCESSING → READY", videoId);
                    }
                    // skillars-deferred-101 AC2: gate incident write on actual correction

                } else if (providerStatus == AssetStatus.DELETED) {
                    videoLifecycleService.transitionOperationalState(videoId, OperationalState.FAILED);
                    incident = saveIncident(fresh, ReconciliationIncidentType.MISSING_ASSET,
                            "Admin reconcile: provider asset missing, video marked FAILED");
                    log.warn("Admin reconcile MISSING_ASSET for video {}: marked FAILED", videoId);
                }

                Video finalVideo = videoRepository.findById(videoId)
                        .orElseThrow(() -> new VideoNotFoundException(videoId));

                ReconcileIncidentDto incidentDto = incident == null ? null
                        : new ReconcileIncidentDto(incident.getId(), incident.getIncidentType(),
                                incident.getProviderAssetId(), incident.getDescription(), incident.getCreatedAt());

                return new ReconcileResponse(
                        finalVideo.getId(), finalVideo.getOperationalState(), finalVideo.getAccessState(), incidentDto);
            }), "transactionTemplate returned null");
        } finally {
            MDC.remove("operation");
            MDC.remove("videoId");
        }
    }

    private ReconciliationIncident saveIncident(Video video, ReconciliationIncidentType type, String description) {
        ReconciliationIncident inc = new ReconciliationIncident();
        inc.setVideoId(video.getId());
        inc.setIncidentType(type);
        inc.setProviderAssetId(video.getProviderAssetId());
        inc.setDescription(description);
        return incidentRepository.save(inc);
    }
}
