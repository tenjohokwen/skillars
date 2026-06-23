package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.session.repo.DrillVideoRef;
import com.softropic.skillars.platform.session.repo.DrillVideoRefRepository;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoDeletionLog;
import com.softropic.skillars.platform.video.repo.VideoDeletionLogRepository;
import com.softropic.skillars.platform.video.repo.VideoDeletionOutbox;
import com.softropic.skillars.platform.video.repo.VideoDeletionOutboxRepository;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class VideoDeletionOutboxProcessor {

    private static final int BATCH_SIZE = 50;

    private final VideoDeletionOutboxRepository outboxRepository;
    private final VideoDeletionLogRepository deletionLogRepository;
    private final VideoRepository videoRepository;
    private final VideoProviderAdapter videoProviderAdapter;
    private final DrillVideoRefRepository drillVideoRefRepository;
    private final ConfigService configService;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(fixedDelayString = "${platform.video.deletion.outbox_poll_delay_ms:60000}")
    public void process() {
        // Reset any rows stuck in CLAIMED state for > 10 minutes (crashed run recovery)
        outboxRepository.resetStaleClaimed(Instant.now().minus(10, ChronoUnit.MINUTES));
        // Atomically claim a batch of PENDING rows; row locks released after the UPDATE commits
        outboxRepository.claimPendingBatch(Instant.now(), BATCH_SIZE);
        List<VideoDeletionOutbox> rows = outboxRepository.findClaimedBatch();
        for (VideoDeletionOutbox row : rows) {
            processRow(row);
        }
    }

    private void processRow(VideoDeletionOutbox row) {
        // Null Bunny ID short-circuit: video never reached encoding
        if (row.getBunnyVideoId() == null) {
            completeRow(row, null);
            return;
        }

        // Drill refCount check: prevent shared drill asset physical deletion.
        // Uses videoId lookup (stable UUID foreign key) — providerAssetId may have been cleared.
        // decrementRefCount and outbox completion are wrapped in a single transaction to prevent
        // a phantom retry calling deleteAsset if completeRow fails after a committed decrement.
        Optional<DrillVideoRef> drillRef = drillVideoRefRepository.findByVideoId(row.getVideoId());
        if (drillRef.isPresent() && drillRef.get().getRefCount() > 1) {
            final UUID drillId = drillRef.get().getDrillId();
            final int capturedRefCount = drillRef.get().getRefCount();
            transactionTemplate.execute(status -> {
                int decremented = drillVideoRefRepository.decrementRefCount(drillId);
                if (decremented == 0) {
                    log.warn("[DRILL_REF_ALREADY_ZERO videoId={} drillId={}] refCount already zero — physical delete skipped regardless",
                        row.getVideoId(), drillId);
                }
                appendDeletionLog(row, row.getBunnyVideoId());
                row.setStatus("COMPLETED");
                outboxRepository.save(row);
                return null;
            });
            log.debug("[DRILL_REF_DECREMENTED videoId={} drillId={}] refCount was {}, decremented — physical delete skipped",
                row.getVideoId(), drillId, capturedRefCount);
            return;
        }

        // Reload video: if providerAssetId already nulled, deletion already processed
        Optional<Video> videoOpt = videoRepository.findById(row.getVideoId());
        if (videoOpt.isPresent() && videoOpt.get().getProviderAssetId() == null) {
            completeRow(row, row.getBunnyVideoId());
            return;
        }

        // Physical deletion via Bunny.net — outside @Transactional
        try {
            videoProviderAdapter.deleteAsset(row.getBunnyVideoId());
            completeRowWithNullAsset(row);
        } catch (Exception e) {
            handleFailure(row, e);
        }
    }

    private void completeRow(VideoDeletionOutbox row, String bunnyVideoId) {
        transactionTemplate.execute(status -> {
            appendDeletionLog(row, bunnyVideoId);
            row.setStatus("COMPLETED");
            outboxRepository.save(row);
            return null;
        });
    }

    private void completeRowWithNullAsset(VideoDeletionOutbox row) {
        transactionTemplate.execute(status -> {
            videoRepository.findById(row.getVideoId()).ifPresent(v -> {
                v.setProviderAssetId(null);
                videoRepository.save(v);
            });
            appendDeletionLog(row, row.getBunnyVideoId());
            row.setStatus("COMPLETED");
            outboxRepository.save(row);
            return null;
        });
    }

    private void handleFailure(VideoDeletionOutbox row, Exception e) {
        transactionTemplate.execute(status -> {
            row.setAttempts(row.getAttempts() + 1);
            row.setLastError(e.getMessage());
            int maxAttempts = (int) configService.getLong("platform.video.deletion.max_attempts", 5L);
            if (row.getAttempts() >= maxAttempts) {
                row.setStatus("DEAD");
                log.error("[DEAD_LETTER videoId={} triggeredBy={}]", row.getVideoId(), row.getTriggeredBy());
            } else {
                long backoffMinutes = Math.min(60L, (long) Math.pow(2, row.getAttempts()));
                row.setNextRetryAt(Instant.now().plus(backoffMinutes, ChronoUnit.MINUTES));
                row.setStatus("PENDING");
            }
            outboxRepository.save(row);
            return null;
        });
    }

    private void appendDeletionLog(VideoDeletionOutbox row, String bunnyVideoId) {
        VideoDeletionLog logRow = new VideoDeletionLog();
        logRow.setVideoId(row.getVideoId());
        logRow.setTriggeredBy(row.getTriggeredBy());
        logRow.setBunnyVideoId(bunnyVideoId);
        deletionLogRepository.save(logRow);
    }
}
