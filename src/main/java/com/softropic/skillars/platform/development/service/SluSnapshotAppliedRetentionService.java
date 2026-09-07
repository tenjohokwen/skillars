package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.development.repo.PlayerSluWeeklySnapshotAppliedRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;

/**
 * skillars-deferred-99 AC9 — retention for {@code development.player_slu_weekly_snapshot_applied}.
 *
 * <p>That marker table has a 5-column PK led by a random session UUID and rows accumulate for the
 * lifetime of the deployment (removed today only by the per-player GDPR-erasure cascade). Each row's
 * only purpose is to make {@code SnapshotBatchWriter}'s additive upsert idempotent across a retry
 * window measured in <em>minutes</em>, so a 90-day floor is very safe. Growth is low — one row per
 * session × skill × ISO-week — so this is housekeeping, not urgent, but it is unbounded without a
 * prune.
 *
 * <p>Belongs in {@code platform.development.service} (domain-lifecycle scheduler), not infrastructure.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SluSnapshotAppliedRetentionService {

    /** Rows per batched delete. Large enough to be cheap, small enough to keep each txn short. */
    private static final int BATCH_SIZE = 5_000;
    /** Safety stop so one run cannot spin forever if the table is pathologically large. */
    private static final int MAX_BATCHES_PER_RUN = 500;

    private final PlayerSluWeeklySnapshotAppliedRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;

    @Value("${app.slu.snapshot-applied.retention-days:90}")
    private int retentionDays;

    @Scheduled(cron = "${app.slu.snapshot-applied.prune-cron:0 30 3 * * *}")
    @SchedulerLock(name = "SluSnapshotAppliedRetentionService_prune",
                   lockAtMostFor = "PT15M", lockAtLeastFor = "PT1M")
    public void prune() {
        try {
            int deleted = pruneOlderThanRetention();
            meterRegistry.counter("slu.snapshot_applied.prune.deleted").increment(deleted);
            if (deleted > 0) {
                log.info("Pruned {} player_slu_weekly_snapshot_applied marker(s) older than {} days",
                    deleted, retentionDays);
            }
        } catch (Exception e) {
            // Do not rethrow: a silently failing @Scheduled job would let the table grow unbounded
            // with nothing pointing at the cause. The counter feeds alerting.
            meterRegistry.counter("slu.snapshot_applied.prune.failures").increment();
            log.error("player_slu_weekly_snapshot_applied retention prune failed — the table will keep "
                + "growing until this is resolved", e);
        }
    }

    /** Package-private so an IT can drive exactly one prune without the cron or the scheduler lock. */
    int pruneOlderThanRetention() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        int total = 0;
        for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
            Integer batch = transactionTemplate.execute(s -> repository.pruneAppliedBefore(cutoff, BATCH_SIZE));
            int deleted = batch == null ? 0 : batch;
            total += deleted;
            if (deleted < BATCH_SIZE) {
                break;
            }
        }
        return total;
    }
}
