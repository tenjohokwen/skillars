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
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
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

    /**
     * skillars-deferred-120 code review (2026-09-17, Decision 1): {@code STALE_CLAIM_WINDOW} MUST
     * stay strictly greater than {@link #LOCK_AT_MOST_FOR}'s {@code Duration} equivalent — if it
     * does not, lock expiry actively triggers the double-processing {@code @SchedulerLock} exists to
     * prevent (see {@link #process()}'s Javadoc for the mechanism). A 5-minute buffer above the
     * {@code PT15M} lock (rather than matching {@code RadarCompositeDlqProcessor}'s knife's-edge
     * equality between its own 10-minute window and its {@code PT10M} lock — an accepted, unfixed
     * weakness recorded in {@code deferred-work.md}, not a pattern to copy into a new lock) keeps
     * this class's own margin real instead of nominal.
     */
    private static final Duration STALE_CLAIM_WINDOW = Duration.ofMinutes(20);

    /**
     * Kept as a named constant (not just the {@code @SchedulerLock} annotation literal) solely so
     * {@link #STALE_CLAIM_WINDOW}'s Javadoc can point at it and the invariant between the two is
     * documented in one place. {@code @SchedulerLock(lockAtMostFor = ...)} requires a compile-time
     * constant expression, which is exactly what this is.
     */
    private static final String LOCK_AT_MOST_FOR = "PT15M";

    private final VideoDeletionOutboxRepository outboxRepository;
    private final VideoDeletionLogRepository deletionLogRepository;
    private final VideoRepository videoRepository;
    private final VideoProviderAdapter videoProviderAdapter;
    private final DrillVideoRefRepository drillVideoRefRepository;
    private final ConfigService configService;
    private final TransactionTemplate transactionTemplate;

    /**
     * skillars-deferred-120 AC2 sizing basis: {@code findClaimedBatch()} is unscoped to this
     * invocation's own claim (global {@code WHERE status = 'CLAIMED'}, no per-run filter) — two
     * concurrent invocations (only reachable once this deployment scales to multiple instances;
     * {@code fixedDelay} cannot overlap itself within one instance) each claim genuinely disjoint
     * rows via {@code claimPendingBatch}, then each instance's {@code findClaimedBatch()} returns
     * both batches, so each processes rows the other is concurrently processing — real duplicate
     * {@code videoProviderAdapter.deleteAsset} calls and duplicate {@code video_deletion_log} rows,
     * not merely a shared-field race. Same fix and reasoning {@code skillars-deferred-118} AC3 used
     * to close {@code RadarCompositeDlqProcessor}'s identical bug shape (same {@code BATCH_SIZE = 50}
     * constant).
     *
     * <p><strong>{@code lockAtMostFor} MUST stay strictly less than {@link #STALE_CLAIM_WINDOW}
     * (code review 2026-09-17, Decision 1).</strong> {@code resetStaleClaimed} below keys on
     * <em>eligibility</em> time, not claim time ({@code claimPendingBatch} never stamps {@code
     * next_retry_at} when it claims), so once a run has held its claim for longer than that window,
     * a second instance's very first statement un-claims the still-in-flight rows and {@code
     * findClaimedBatch()} (unscoped, no {@code LIMIT}) hands them straight back over — the exact
     * double-processing this lock exists to prevent. An initial {@code PT15M} lock paired with the
     * pre-existing 10-minute window left a 5-minute range where lock expiry would have actively
     * triggered that outcome; {@link #STALE_CLAIM_WINDOW} was raised to 20 minutes (rather than
     * lowering the lock to match the window, which would have thinned this lock's own margin to
     * ~1.2x over its worst-case runtime) specifically to restore a real buffer between the two.
     * Worst case: {@code BATCH_SIZE} (50) × per-item {@code deleteAsset} cost — an external
     * Bunny.net HTTP call, no retries at this layer. Per {@code skillars-deferred-119} AC1's
     * S3-delete sizing precedent (~10s/item realistic worst case, not the full 10s-connect +
     * 30s-read hard timeout sum), 50 × 10s = 500s (~8.3 min). {@code PT15M} gives ~1.8x margin above
     * that, and a genuine 5-minute buffer below {@link #STALE_CLAIM_WINDOW} — unlike {@code
     * RadarCompositeDlqProcessor}, whose own 10-minute window and {@code PT10M} lock are exactly
     * equal (an accepted, unfixed weakness of the shared claim idiom, recorded in {@code
     * deferred-work.md}; not a pattern to copy into a new lock). {@code lockAtLeastFor} mirrors
     * {@code RadarCompositeDlqProcessor}'s identical 60s-cadence reasoning (this scheduler's own
     * cadence, {@code outbox_poll_delay_ms}, defaults to 60000ms) — {@code PT30S} sits comfortably
     * below it while still guarding the pathological fast-fail-and-immediately-refire edge case.
     * Crash-recovery latency (how long a stuck row from a genuinely crashed instance waits before
     * {@code resetStaleClaimed} frees it) moves from 10 to 20 minutes as a result — immaterial for a
     * deletion outbox polled every 60 seconds under normal operation.
     */
    @Scheduled(fixedDelayString   = "${platform.video.deletion.outbox_poll_delay_ms:60000}",
               initialDelayString = "${platform.video.deletion.outbox_initial_delay_ms:0}")
    @SchedulerLock(name = "VideoDeletionOutboxProcessor_process",
                   lockAtMostFor = LOCK_AT_MOST_FOR, lockAtLeastFor = "PT30S")
    public void process() {
        // Reset any rows stuck in CLAIMED state for longer than STALE_CLAIM_WINDOW (crashed run
        // recovery) — see STALE_CLAIM_WINDOW's own Javadoc for the invariant this must respect.
        outboxRepository.resetStaleClaimed(Instant.now().minus(STALE_CLAIM_WINDOW));
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
            // skillars-deferred-107 AC2: 0/neg would dead-letter on the first attempt (or never).
            int maxAttempts = (int) configService.getBoundedLong("platform.video.deletion.max_attempts", 5L, 1L, 100L);
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
