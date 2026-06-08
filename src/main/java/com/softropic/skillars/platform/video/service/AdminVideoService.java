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
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

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

            // Atomic: set DELETED + release quota for any PENDING session
            transactionTemplate.execute(status -> {
                Video v = videoRepository.findById(videoId)
                        .orElseThrow(() -> new VideoNotFoundException(videoId));
                v.setOperationalState(OperationalState.DELETED);
                videoRepository.save(v);

                uploadSessionRepository.findFirstByVideoIdOrderByCreatedAtDesc(videoId)
                        .filter(s -> s.getStatus() == UploadSessionStatus.PENDING)
                        .ifPresent(s -> {
                            quotaProvider.release(s.getReservationHandle());
                            s.setStatus(UploadSessionStatus.EXPIRED);
                            uploadSessionRepository.save(s);
                        });
                return null;
            });

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
                    videoLifecycleService.transitionOperationalState(videoId, OperationalState.READY);
                    incident = saveIncident(fresh, ReconciliationIncidentType.STATE_CORRECTED,
                            "Admin reconcile: local PROCESSING corrected to READY");
                    log.info("Admin reconcile STATE_CORRECTED for video {}: PROCESSING → READY", videoId);

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
