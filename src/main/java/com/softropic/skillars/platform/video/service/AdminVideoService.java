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

    @Observed(name = "video.admin.deleteVideo")
    public void deleteVideo(UUID videoId) {
        MDC.put("operation", "admin.deleteVideo");
        MDC.put("videoId", videoId.toString());
        try {
            Video video = videoRepository.findById(videoId)
                    .orElseThrow(() -> new VideoNotFoundException(videoId));

            // Provider call outside @Transactional — if this throws VideoProviderException (502), no DB change occurs
            if (video.getProviderAssetId() != null) {
                videoProviderAdapter.deleteAsset(video.getProviderAssetId());
            }

            // Phase 1 (atomic): set DELETED + mark any PENDING session EXPIRED. No quotaProvider call
            // inside this transaction, so a release() failure can no longer roll back the
            // DELETED/EXPIRED writes.
            transactionTemplate.execute(status -> {
                Video v = videoRepository.findById(videoId)
                        .orElseThrow(() -> new VideoNotFoundException(videoId));
                v.setOperationalState(OperationalState.DELETED);
                videoRepository.save(v);

                uploadSessionRepository.findFirstByVideoIdOrderByCreatedAtDesc(videoId)
                        .filter(s -> s.getStatus() == UploadSessionStatus.PENDING)
                        .ifPresent(s -> {
                            s.setStatus(UploadSessionStatus.EXPIRED);
                            uploadSessionRepository.save(s);
                        });
                return null;
            });

            // Phase 2: release quota OUTSIDE any transaction — same pattern as VideoService.failTranscoding.
            // Deferred-64 AC5: looked up via the SAME repository method Phase 1 already calls, but
            // without Phase 1's PENDING filter, so a session Phase 1 already flipped to EXPIRED on an
            // earlier call is still found here. Gated on quotaReleasedAt == null so a repeat call for
            // an already-DELETED video is retry-safe: it re-attempts the release exactly when it
            // previously failed or was never attempted, and never re-releases a session already marked.
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
            } else if (session != null && session.getReservationHandle() == null) {
                log.warn("No reservation handle found for videoId={} during admin delete — quota not released", videoId);
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

            if (video.getOperationalState() == OperationalState.DELETED) {
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
