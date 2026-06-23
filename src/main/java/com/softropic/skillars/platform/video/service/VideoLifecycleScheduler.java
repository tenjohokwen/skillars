package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.video.contract.LifecycleTrigger;
import com.softropic.skillars.platform.video.contract.PlayerSubscriptionQueryPort;
import com.softropic.skillars.platform.video.contract.exception.VideoProviderException;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoLifecycleLog;
import com.softropic.skillars.platform.video.repo.VideoLifecycleLogRepository;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class VideoLifecycleScheduler {

    private final VideoRepository videoRepository;
    private final VideoLifecycleLogRepository videoLifecycleLogRepository;
    private final VideoLifecycleService videoLifecycleService;
    private final VideoProviderAdapter videoProviderAdapter;
    private final ConfigService configService;
    private final TransactionTemplate transactionTemplate;
    private final QuotaService quotaService;
    private final PlayerSubscriptionQueryPort playerSubscriptionQueryPort;

    @Scheduled(cron = "${app.video.lifecycle.cron:0 0 3 * * *}")
    public void runLifecycleJob() {
        long blockedToArchivedDays = configService.getLong("platform.video.lifecycle.blocked_to_archived_days", 30L);
        long archivedToDeletedDays = configService.getLong("platform.video.lifecycle.archived_to_deleted_days", 90L);
        int batchSize = configService.getInt("platform.video.lifecycle.batch_size", 100);

        int archivedCount = runBlockedToArchivedPhase(blockedToArchivedDays, batchSize);
        int deletedCount  = runArchivedToDeletedPhase(archivedToDeletedDays, batchSize);

        log.info("VideoLifecycleScheduler completed: archived={} deleted={}", archivedCount, deletedCount);
    }

    private int runBlockedToArchivedPhase(long blockedToArchivedDays, int batchSize) {
        Instant threshold = Instant.now().minus(blockedToArchivedDays, ChronoUnit.DAYS);
        List<Video> candidates = videoRepository.findBlockedExceedingThreshold(threshold, batchSize);
        int count = 0;
        for (Video video : candidates) {
            try {
                UUID playerId = UUID.fromString(video.getOwnerId());
                if (playerSubscriptionQueryPort.hasActiveYearlySubscription(playerId)) {
                    log.debug("Yearly exemption: skipping BLOCKED→ARCHIVED for videoId={} ownerId={}", video.getId(), video.getOwnerId());
                    continue;
                }
            } catch (IllegalArgumentException e) {
                log.warn("Cannot parse ownerId as UUID for yearly check, proceeding: videoId={} ownerId={}", video.getId(), video.getOwnerId());
            }

            // External Bunny call outside @Transactional — 404 treated as success (idempotency)
            try {
                videoProviderAdapter.archiveAsset(video.getProviderAssetId());
            } catch (VideoProviderException e) {
                log.error("archiveAsset failed for videoId={}: {}", video.getId(), e.getMessage());
                continue;
            }

            UUID videoId = video.getId();
            transactionTemplate.execute(ignored -> {
                videoLifecycleService.archiveForLifecycle(videoId);
                videoLifecycleLogRepository.save(buildLog(videoId, "BLOCKED", "ARCHIVED", LifecycleTrigger.SYSTEM));
                return null;
            });
            count++;
        }
        log.info("Phase BLOCKED→ARCHIVED: processed={}", count);
        return count;
    }

    private int runArchivedToDeletedPhase(long archivedToDeletedDays, int batchSize) {
        Instant threshold = Instant.now().minus(archivedToDeletedDays, ChronoUnit.DAYS);
        List<Video> candidates = videoRepository.findArchivedExceedingThreshold(threshold, batchSize);
        int count = 0;
        for (Video video : candidates) {
            // External Bunny call outside @Transactional — 404 treated as success (idempotency)
            try {
                videoProviderAdapter.deleteAsset(video.getProviderAssetId());
            } catch (VideoProviderException e) {
                log.error("deleteAsset failed for videoId={}: {}", video.getId(), e.getMessage());
                continue;
            }

            UUID videoId = video.getId();
            transactionTemplate.execute(ignored -> {
                Video v = videoRepository.findById(videoId).orElseThrow();
                long released = videoLifecycleService.markPurged(v.getId());
                quotaService.decrementStorageBytes(v.getOwnerId(), released);
                videoLifecycleLogRepository.save(buildLog(v.getId(), "ARCHIVED", "DELETED", LifecycleTrigger.SYSTEM));
                return null;
            });
            count++;
        }
        log.info("Phase ARCHIVED→DELETED: processed={}", count);
        return count;
    }

    private VideoLifecycleLog buildLog(UUID videoId, String from, String to, String triggeredBy) {
        VideoLifecycleLog log = new VideoLifecycleLog();
        log.setVideoId(videoId);
        log.setFromState(from);
        log.setToState(to);
        log.setTriggeredBy(triggeredBy);
        return log;
    }
}
