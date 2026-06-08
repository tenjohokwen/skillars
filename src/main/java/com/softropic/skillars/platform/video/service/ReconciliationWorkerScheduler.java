package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.video.AssetStatus;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.video.config.VideoProperties;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.ReconciliationIncidentType;
import com.softropic.skillars.platform.video.contract.exception.VideoProviderException;
import com.softropic.skillars.platform.video.repo.ReconciliationIncident;
import com.softropic.skillars.platform.video.repo.ReconciliationIncidentRepository;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationWorkerScheduler {

    private final VideoRepository videoRepository;
    private final VideoLifecycleService videoLifecycleService;
    private final VideoProviderAdapter videoProviderAdapter;
    private final ReconciliationIncidentRepository incidentRepository;
    private final VideoProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final VideoMetrics videoMetrics;

    @Observed(name = "video.reconciliation.runCycle")
    @Scheduled(fixedDelayString = "${app.video.reconciliation.fixed-delay-ms:60000}")
    public void reconcile() {
        long cycleStart = System.nanoTime();
        try {
            int batchSize = properties.getReconciliation().getBatchSize();

            List<Video> videos = Objects.requireNonNullElse(
                transactionTemplate.execute(status ->
                    videoRepository.findNonTerminalForUpdate(batchSize)),
                List.of());

            for (Video video : videos) {
                MDC.put("videoId", video.getId().toString());
                MDC.put("providerAssetId", video.getProviderAssetId());
                MDC.put("localState", video.getOperationalState().name());
                MDC.put("provider", video.getProvider());
                try {
                    // getAssetStatus() is OUTSIDE any @Transactional boundary — no long-lived DB tx during HTTP call
                    AssetStatus providerStatus = videoProviderAdapter.getAssetStatus(video.getProviderAssetId());
                    processReconciliation(video, providerStatus);
                } catch (VideoProviderException e) {
                    // AC-6: transient provider failures skip, retry next cycle — do NOT mark FAILED
                    log.warn("Transient provider error for video {}, will retry next cycle", video.getId(), e);
                } finally {
                    MDC.remove("videoId");
                    MDC.remove("providerAssetId");
                    MDC.remove("localState");
                    MDC.remove("provider");
                }
            }
        } finally {
            videoMetrics.recordReconciliationCycleDuration(System.nanoTime() - cycleStart);
        }
    }

    private void processReconciliation(Video video, AssetStatus providerStatus) {
        OperationalState localState = video.getOperationalState();

        if (providerStatus == AssetStatus.READY && localState == OperationalState.PROCESSING) {
            transactionTemplate.execute(txStatus -> {
                videoLifecycleService.transitionOperationalState(video.getId(), OperationalState.READY);
                recordIncident(video, ReconciliationIncidentType.STATE_CORRECTED,
                    "Local state PROCESSING corrected to READY based on provider status");
                return null;
            });
            log.info("Reconciliation STATE_CORRECTED for video {}: PROCESSING → READY", video.getId());

        } else if (providerStatus == AssetStatus.DELETED) {
            transactionTemplate.execute(txStatus -> {
                videoLifecycleService.transitionOperationalState(video.getId(), OperationalState.FAILED);
                recordIncident(video, ReconciliationIncidentType.MISSING_ASSET,
                    "Provider asset not found; video marked FAILED");
                return null;
            });
            log.warn("Reconciliation MISSING_ASSET for video {}: marked FAILED", video.getId());

        } else if (providerStatus == AssetStatus.READY && localState == OperationalState.UPLOADING) {
            // UPLOADING→READY is invalid; upload confirmation path was missed — mark FAILED
            transactionTemplate.execute(txStatus -> {
                videoLifecycleService.transitionOperationalState(video.getId(), OperationalState.FAILED);
                recordIncident(video, ReconciliationIncidentType.MISSING_ASSET,
                    "Video still UPLOADING but provider reports READY; confirmation path missed, marked FAILED for retry");
                return null;
            });
            log.warn("Reconciliation for UPLOADING video {}: provider READY but no confirmation, marked FAILED", video.getId());
        }
        // Other statuses (UPLOADING+UPLOADING, PROCESSING+PROCESSING, FAILED): no action needed
    }

    private void recordIncident(Video video, ReconciliationIncidentType type, String description) {
        ReconciliationIncident incident = new ReconciliationIncident();
        incident.setVideoId(video.getId());
        incident.setIncidentType(type);
        incident.setProviderAssetId(video.getProviderAssetId());
        incident.setDescription(description);
        incidentRepository.save(incident);
    }
}
