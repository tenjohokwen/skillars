package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.development.repo.RadarCompositeDlqEntry;
import com.softropic.skillars.platform.development.repo.RadarCompositeDlqRepository;
import com.softropic.skillars.platform.config.service.ConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

// Deferred-77 AC10 Phase 2 — replays radar composite calculations that failed even after the
// original @Async listener invocation. Mirrors VideoDeletionOutboxProcessor's claim/process/backoff
// shape (V59) rather than introducing a new queueing mechanism for this codebase.
@Slf4j
@Component
@RequiredArgsConstructor
public class RadarCompositeDlqProcessor {

    private static final int BATCH_SIZE = 50;

    private final RadarCompositeDlqRepository dlqRepository;
    private final RadarCompositeCalculationService compositeCalculationService;
    private final ConfigService configService;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(fixedDelayString   = "${platform.development.radar_composite_dlq.poll_delay_ms:60000}",
               initialDelayString = "${platform.development.radar_composite_dlq.initial_delay_ms:0}")
    public void process() {
        dlqRepository.resetStaleClaimed(Instant.now().minus(10, ChronoUnit.MINUTES));
        dlqRepository.claimPendingBatch(Instant.now(), BATCH_SIZE);
        List<RadarCompositeDlqEntry> rows = dlqRepository.findClaimedBatch();
        for (RadarCompositeDlqEntry row : rows) {
            processRow(row);
        }
    }

    private void processRow(RadarCompositeDlqEntry row) {
        try {
            compositeCalculationService.recalculateComposite(
                row.getPlayerId(), row.getParentId(), Set.copyOf(row.getSkillCodes()));
            transactionTemplate.execute(status -> {
                row.setStatus("COMPLETED");
                dlqRepository.save(row);
                return null;
            });
        } catch (Exception e) {
            handleFailure(row, e);
        }
    }

    private void handleFailure(RadarCompositeDlqEntry row, Exception e) {
        transactionTemplate.execute(status -> {
            row.setAttempts(row.getAttempts() + 1);
            row.setLastError(e.getMessage());
            int maxAttempts = (int) configService.getLong("platform.development.radar_composite_dlq.max_attempts", 5L);
            if (row.getAttempts() >= maxAttempts) {
                row.setStatus("DEAD");
                log.error("[DEAD_LETTER playerId={} skillCodes={}] radar composite recalculation exhausted retries",
                    row.getPlayerId(), row.getSkillCodes());
            } else {
                long backoffMinutes = Math.min(60L, (long) Math.pow(2, row.getAttempts()));
                row.setNextRetryAt(Instant.now().plus(backoffMinutes, ChronoUnit.MINUTES));
                row.setStatus("PENDING");
            }
            dlqRepository.save(row);
            return null;
        });
    }
}
