package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.development.repo.RadarCompositeDlqEntry;
import com.softropic.skillars.platform.development.repo.RadarCompositeDlqRepository;
import com.softropic.skillars.platform.config.service.ConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
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
//
// skillars-deferred-118 AC3: findClaimedBatch() is not scoped to the calling invocation's own claim
// and RadarCompositeDlqEntry carries no @Version, so two concurrent invocations could each read and
// unconditionally overwrite the other's status/attempts/lastError/nextRetryAt on the same row with
// no optimistic-lock protection at all. @SchedulerLock below closes this by preventing the
// concurrent invocation in the first place.
@Slf4j
@Component
@RequiredArgsConstructor
public class RadarCompositeDlqProcessor {

    private static final int BATCH_SIZE = 50;

    private final RadarCompositeDlqRepository dlqRepository;
    private final RadarCompositeCalculationService compositeCalculationService;
    private final ConfigService configService;
    private final TransactionTemplate transactionTemplate;

    /**
     * skillars-deferred-118 AC3 sizing basis: {@code BATCH_SIZE = 50} is config-independent and
     * fixed, unlike the other two schedulers this AC covers. Per-row work in
     * {@link RadarCompositeCalculationService#recalculateComposite} is a pessimistic-lock-retried
     * read-then-upsert across up to three repositories (composite, baseline, player profile) — all
     * indexed DB round trips, no external HTTP call. At a pessimistic 5s/row (heavy lock contention,
     * well above the sub-second happy path): {@code 50 × 5s = 250s ≈ 4.2 minutes}. {@code PT10M}
     * gives real margin above that. {@code lockAtLeastFor} is deliberately NOT the {@code PT2M} used
     * by the 5-minute-{@code fixedDelay} siblings: this scheduler's own cadence
     * ({@code poll_delay_ms}, default 60000ms = 1 minute) is tighter than theirs, and a 2-minute
     * floor would force every-other-tick skipping — roughly halving this DLQ processor's effective
     * retry cadence, a real behavior change this AC does not call for. {@code PT30S} sits comfortably
     * below the default 60s cadence (so it never blocks the next scheduled tick under normal
     * operation) while still guarding the pathological fast-fail-and-immediately-refire edge case.
     */
    @Scheduled(fixedDelayString   = "${platform.development.radar_composite_dlq.poll_delay_ms:60000}",
               initialDelayString = "${platform.development.radar_composite_dlq.initial_delay_ms:0}")
    @SchedulerLock(name = "RadarCompositeDlqProcessor_process",
                   lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
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
            // skillars-deferred-107 AC2: 0/neg would dead-letter on the first attempt (or never).
            int maxAttempts = (int) configService.getBoundedLong(
                "platform.development.radar_composite_dlq.max_attempts", 5L, 1L, 100L);
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
