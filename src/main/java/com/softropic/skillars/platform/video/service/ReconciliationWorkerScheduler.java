package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.video.AssetStatus;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.video.config.VideoProperties;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.ReconciliationIncidentType;
import com.softropic.skillars.platform.video.contract.exception.VideoNotFoundException;
import com.softropic.skillars.platform.video.contract.exception.VideoProviderException;
import com.softropic.skillars.platform.video.contract.exception.VideoStateConflictException;
import com.softropic.skillars.platform.video.repo.PendingProviderAsset;
import com.softropic.skillars.platform.video.repo.PendingProviderAssetRepository;
import com.softropic.skillars.platform.video.repo.ReconciliationIncident;
import com.softropic.skillars.platform.video.repo.ReconciliationIncidentRepository;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.MDC;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationWorkerScheduler {

    /** skillars-deferred-100 code review (2026-09-08): failed-purge count that trips the stuck alert. */
    private static final int ORPHAN_ASSET_STUCK_ATTEMPTS = 10;

    private final VideoRepository videoRepository;
    private final VideoLifecycleService videoLifecycleService;
    private final VideoProviderAdapter videoProviderAdapter;
    private final ReconciliationIncidentRepository incidentRepository;
    private final PendingProviderAssetRepository pendingProviderAssetRepository;
    private final VideoProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final VideoMetrics videoMetrics;

    @Observed(name = "video.reconciliation.runCycle")
    @Scheduled(fixedDelayString = "${app.video.reconciliation.fixed-delay-ms:60000}",
               initialDelayString = "${app.video.reconciliation.fixed-delay-ms:60000}")
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
                } catch (VideoStateConflictException | VideoNotFoundException e) {
                    // skillars-deferred-100 code review (2026-09-08): the video moved to another state
                    // (or was deleted) between the batch load and this correction — reconcileToReady()
                    // / transitionOperationalState() then throw. That is this one video's problem, not
                    // the batch's: log and carry on so the remaining candidates are still processed.
                    log.info("Reconciliation skipped for video {} — state changed underneath the cycle: {}",
                        video.getId(), e.getMessage());
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
                // skillars-deferred-100 AC5: reconcileToReady(), not transitionOperationalState() —
                // PROCESSING→READY is no longer a valid plain transition, and this legitimate
                // provider-driven correction must not trip the video.moderation.bypass alarm.
                videoLifecycleService.reconcileToReady(video.getId(),
                    "reconciliation: provider reports READY, local state stuck at PROCESSING");
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

    /**
     * skillars-deferred-100 AC2: purge provider assets whose caller transaction rolled back after
     * {@code VideoService.initializeUpload} created them. Such an asset has a
     * {@code pending_provider_asset} tracking row (written in its own committed transaction before
     * the rollback) but no {@code Video} — so {@link #reconcile()} above, which only iterates rows
     * that HAVE a {@code Video}, can never see it.
     *
     * <p>Only rows older than {@code app.video.orphan-asset.ttl} are considered, so an upload still
     * in flight (initialize→confirm is seconds) is never touched. The provider {@code deleteAsset}
     * call is made OUTSIDE any transaction, mirroring {@link #reconcile()}'s HTTP-outside-tx
     * discipline; it is idempotent (a provider 404 is success). A transient
     * {@link VideoProviderException} leaves the row for the next cycle.
     *
     * <p><strong>Why a swept row cannot be an in-use asset (skillars-deferred-100 AC2 review):</strong>
     * Bunny's {@code POST /library/{id}/videos} returns a fresh GUID on every call and never
     * content-deduplicates, so a {@code providerAssetId} is globally unique to the one
     * {@code initializeUpload} that created it — a re-upload of identical bytes gets a different id.
     * {@code VideoService.initializeUpload} writes the {@code pending_provider_asset} row and, on
     * success, deletes it in the same transaction as the {@code Video} persist — both synchronous,
     * with no client round-trip between them — so a row that is still present <em>and</em> older
     * than the TTL, <em>and</em> has no {@code videos.provider_asset_id} match, is unambiguously an
     * orphan from a rolled-back caller transaction, not a slow live upload. The TTL is the tunable
     * safety margin: raise {@code app.video.orphan-asset.ttl} if a deployment ever holds a caller
     * transaction open longer than the default 30 minutes (it should not — that transaction is
     * request-scoped and bounded by statement/lock timeouts).
     */
    @Observed(name = "video.reconciliation.orphanAssetSweep")
    @Scheduled(fixedDelayString = "${app.video.orphan-asset.sweep-delay-ms:300000}",
               initialDelayString = "${app.video.orphan-asset.sweep-delay-ms:300000}")
    // lockAtLeastFor = PT0S: the only goal is mutual exclusion of concurrent runs across instances;
    // the 5-minute fixedDelay already spaces runs out, so there is no reason to hold the lock after
    // the method returns (and a minimum hold would make back-to-back calls in a test silently skip).
    @SchedulerLock(name = "ReconciliationWorkerScheduler_sweepOrphanedProviderAssets",
                   lockAtMostFor = "PT10M", lockAtLeastFor = "PT0S")
    public void sweepOrphanedProviderAssets() {
        Instant cutoff = Instant.now().minus(properties.getOrphanAsset().getTtl());
        int batchSize = properties.getOrphanAsset().getBatchSize();

        // skillars-deferred-100 code review (2026-09-08): order by attempts first so a row whose
        // deleteAsset keeps failing (a non-404 4xx like a bad API key, or a persistent 5xx — the
        // Bunny adapter collapses all of these to VideoProviderException) sinks below fresh work
        // instead of permanently occupying the head of every batch (the deferred-90 D1 failure mode).
        List<PendingProviderAsset> candidates = Objects.requireNonNullElse(
            transactionTemplate.execute(status ->
                pendingProviderAssetRepository.findByCreatedAtBeforeOrderByAttemptsAscCreatedAtAsc(
                    cutoff, PageRequest.of(0, batchSize))),
            List.of());

        for (PendingProviderAsset pending : candidates) {
            MDC.put("providerAssetId", pending.getProviderAssetId());
            MDC.put("provider", pending.getProvider());
            try {
                processPendingProviderAsset(pending);
            } catch (VideoProviderException e) {
                videoMetrics.recordOrphanAssetPurgeFailed();
                bumpAttempts(pending);
                log.warn("Transient provider error purging orphaned asset {} (attempt {}), will retry next cycle",
                    pending.getProviderAssetId(), pending.getAttempts() + 1, e);
            } catch (RuntimeException e) {
                // skillars-deferred-100 code review (2026-09-08): a DB error in the incident-write /
                // row-delete transaction, or any other unexpected failure for this row, must not
                // abort the rest of the batch.
                videoMetrics.recordOrphanAssetPurgeFailed();
                bumpAttempts(pending);
                log.error("Unexpected error purging orphaned asset {} (attempt {}), skipping this row",
                    pending.getProviderAssetId(), pending.getAttempts() + 1, e);
            } finally {
                MDC.remove("providerAssetId");
                MDC.remove("provider");
            }
        }

        long stuck = pendingProviderAssetRepository.countByAttemptsGreaterThanEqual(ORPHAN_ASSET_STUCK_ATTEMPTS);
        videoMetrics.updateOrphanAssetStuckCount(stuck);
        if (stuck > 0) {
            log.error("[ORPHANED_ASSET_STUCK] {} provider-asset row(s) have failed to purge >= {} times — "
                + "likely a provider auth/permission misconfiguration; needs manual investigation",
                stuck, ORPHAN_ASSET_STUCK_ATTEMPTS);
        }
    }

    private void bumpAttempts(PendingProviderAsset pending) {
        try {
            transactionTemplate.executeWithoutResult(s ->
                pendingProviderAssetRepository.incrementAttempts(pending.getId()));
        } catch (RuntimeException e) {
            log.warn("Could not record failed-attempt count for orphaned asset {}",
                pending.getProviderAssetId(), e);
        }
    }

    private void processPendingProviderAsset(PendingProviderAsset pending) {
        // A Video now carries this asset id — the upload committed (or retryUpload adopted it). Not
        // an orphan: just drop the stale tracking row.
        if (videoRepository.findByProviderAssetId(pending.getProviderAssetId()).isPresent()) {
            transactionTemplate.executeWithoutResult(s -> pendingProviderAssetRepository.delete(pending));
            return;
        }

        // No local Video and older than the TTL — the caller's transaction rolled back after the
        // asset was created. Purge it. HTTP call OUTSIDE any transaction (see reconcile()).
        videoMetrics.recordOrphanAssetFound();
        videoProviderAdapter.deleteAsset(pending.getProviderAssetId());

        transactionTemplate.executeWithoutResult(s -> {
            ReconciliationIncident incident = new ReconciliationIncident();
            incident.setIncidentType(ReconciliationIncidentType.ORPHANED_ASSET);
            incident.setProviderAssetId(pending.getProviderAssetId());
            incident.setDescription("Provider asset created by initializeUpload but the caller's "
                + "transaction rolled back before a Video row was committed; purged by the "
                + "orphaned-asset sweeper");
            incidentRepository.save(incident);
            pendingProviderAssetRepository.delete(pending);
        });
        videoMetrics.recordOrphanAssetPurged();
        log.warn("Reconciliation ORPHANED_ASSET purged provider asset {}", pending.getProviderAssetId());
    }
}
