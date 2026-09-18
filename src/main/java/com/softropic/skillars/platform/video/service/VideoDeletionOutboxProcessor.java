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
     * {@code PT15M} lock keeps this class's own margin real instead of nominal.
     *
     * <p><strong>skillars-deferred-123 code review 2026-09-18 (Decision 3).</strong> Two corrections
     * to the reasoning that previously sat here.
     *
     * <p>First, this Javadoc used to describe {@code RadarCompositeDlqProcessor}'s equality between its
     * own 10-minute window and its {@code PT10M} lock as "an accepted, unfixed weakness recorded in
     * {@code deferred-work.md}". It was not recorded anywhere — the whole ledger was checked. It is now
     * fixed rather than accepted: that class bounds its loop the same way this one does (below).
     *
     * <p>Second, and more importantly, the buffer above was never the real guarantee. The margin was
     * derived from a self-described optimistic "~10s/item realistic worst case, not the full
     * 10s-connect + 30s-read hard timeout sum". But {@code VideoProviderConfig} really does set
     * {@code readTimeout = 30_000} with no retry at that layer, and the case that makes every item hit
     * that timeout — a Bunny.net outage — is correlated, not independent. {@code BATCH_SIZE (50) x 30s
     * = 25 minutes}, which exceeds this window AND the lock. The window was therefore sized against a
     * runtime the loop could genuinely overshoot. The fix is not a bigger number: {@link #process()}
     * now self-terminates at {@link #MAX_RUN_DURATION}, so the invariant is enforced at the source —
     * {@code MAX_RUN_DURATION (12m) < LOCK_AT_MOST_FOR (15m) < STALE_CLAIM_WINDOW (20m)} — instead of
     * being assumed from a per-item cost estimate that can drift whenever the adapter's timeouts change.
     */
    private static final Duration STALE_CLAIM_WINDOW = Duration.ofMinutes(20);

    /**
     * skillars-deferred-123 code review 2026-09-18 (Decision 3): safety budget that keeps
     * {@link #process()} from ever outliving its own {@code @SchedulerLock}. Mirrors
     * {@code QuotaReservationTimeoutService.MAX_RUN_DURATION}'s established pattern (8 minutes under a
     * {@code PT10M} lock) at the same ~80% ratio: 12 minutes under {@link #LOCK_AT_MOST_FOR}'s
     * {@code PT15M}. Bailing out is safe because the remaining rows are handed straight back to
     * {@code PENDING} via {@code releaseClaimed} and picked up by the next firing (default 60s
     * {@code fixedDelay}) — no work is lost and nothing waits for the stale-claim sweep.
     */
    private static final Duration MAX_RUN_DURATION = Duration.ofMinutes(12);

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
     * skillars-deferred-120 AC2 sizing basis, closed by skillars-deferred-123 AC3 (code review
     * 2026-09-18, Patch — this Javadoc previously still described the pre-fix behavior as current).
     * {@code findClaimedBatch()} used to be unscoped to this invocation's own claim (global {@code
     * WHERE status = 'CLAIMED'}, no per-run filter): two concurrent invocations claiming genuinely
     * disjoint rows via {@code claimPendingBatch} would each have their {@code findClaimedBatch()}
     * return both batches, so each processed rows the other was concurrently processing — real
     * duplicate {@code videoProviderAdapter.deleteAsset} calls and duplicate {@code
     * video_deletion_log} rows, not merely a shared-field race. AC3 scoped the fetch to {@code
     * claimed_at = :claimedAt} (this run's own claim instant, stamped by {@code claimPendingBatch}),
     * closing that path completely.
     *
     * <p><strong>{@code lockAtMostFor} vs {@link #STALE_CLAIM_WINDOW}.</strong> {@code
     * resetStaleClaimed} now keys staleness on <em>claim</em> time, not eligibility time — the other
     * half of AC3's fix — which makes this relationship load-bearing for the first time: once a run
     * can legitimately outlive {@code lockAtMostFor}, a second instance's {@code resetStaleClaimed}
     * must not free rows the first instance still holds. {@link #MAX_RUN_DURATION} makes that
     * structural rather than assumed from a per-item cost estimate — see its own Javadoc — with
     * {@code MAX_RUN_DURATION (12m) < LOCK_AT_MOST_FOR (15m) < STALE_CLAIM_WINDOW (20m)}. {@code
     * RadarCompositeDlqProcessor} closed the identical gap the same way (its own {@code
     * MAX_RUN_DURATION}/{@code STALE_CLAIM_WINDOW}) rather than being left as an accepted weakness —
     * this Javadoc previously claimed that equality was "recorded in {@code deferred-work.md}"; it
     * was not, and is now fixed rather than documented as accepted (see {@link #STALE_CLAIM_WINDOW}'s
     * own Javadoc). {@code lockAtLeastFor} mirrors {@code RadarCompositeDlqProcessor}'s identical
     * 60s-cadence reasoning (this scheduler's own cadence, {@code outbox_poll_delay_ms}, defaults to
     * 60000ms) — {@code PT30S} sits comfortably below it while still guarding the pathological
     * fast-fail-and-immediately-refire edge case. Crash-recovery latency (how long a stuck row from a
     * genuinely crashed instance waits before {@code resetStaleClaimed} frees it) is 20 minutes —
     * immaterial for a deletion outbox polled every 60 seconds under normal operation.
     */
    @Scheduled(fixedDelayString   = "${platform.video.deletion.outbox_poll_delay_ms:60000}",
               initialDelayString = "${platform.video.deletion.outbox_initial_delay_ms:0}")
    // skillars-deferred-123 AC4: lockAtLeastFor property-ized — see OutboxService.sweep's identical
    // comment for the rationale.
    @SchedulerLock(name = "VideoDeletionOutboxProcessor_process",
                   lockAtMostFor = LOCK_AT_MOST_FOR,
                   lockAtLeastFor = "${platform.video.deletion.outbox_lock_at_least:PT30S}")
    public void process() {
        // skillars-deferred-123 AC3: one Instant for the whole tick — the same value stamps
        // claimPendingBatch's claimed_at and scopes findClaimedBatch's fetch, so this run's own claim
        // is genuinely identifiable (closing the duplicate-processing path a second instance's
        // globally-scoped fetch used to leave open even after resetStaleClaimed was itself fixed).
        Instant runClaimedAt = Instant.now();
        // Reset any rows stuck in CLAIMED state for longer than STALE_CLAIM_WINDOW (crashed run
        // recovery) — see STALE_CLAIM_WINDOW's own Javadoc for the invariant this must respect.
        outboxRepository.resetStaleClaimed(runClaimedAt.minus(STALE_CLAIM_WINDOW));
        // Atomically claim a batch of PENDING rows; row locks released after the UPDATE commits
        outboxRepository.claimPendingBatch(runClaimedAt, BATCH_SIZE);
        List<VideoDeletionOutbox> rows = outboxRepository.findClaimedBatch(runClaimedAt, BATCH_SIZE);

        // skillars-deferred-123 code review 2026-09-18 (Decision 3): self-terminate before this run
        // could outlive its own lock. See MAX_RUN_DURATION's Javadoc for why the previous
        // per-item-cost sizing was not a real guarantee.
        Instant deadline = runClaimedAt.plus(MAX_RUN_DURATION);
        int processed = 0;
        for (VideoDeletionOutbox row : rows) {
            if (Instant.now().isAfter(deadline)) {
                int released = outboxRepository.releaseClaimed(runClaimedAt);
                log.warn("Stopped video deletion outbox processing after {}/{} rows — hit the {} safety "
                    + "budget under lockAtMostFor={}; released {} unprocessed row(s) back to PENDING for "
                    + "the next scheduled run", processed, rows.size(), MAX_RUN_DURATION,
                    LOCK_AT_MOST_FOR, released);
                return;
            }
            processRow(row, runClaimedAt);
            processed++;
        }
    }

    private void processRow(VideoDeletionOutbox row, Instant runClaimedAt) {
        // Null Bunny ID short-circuit: video never reached encoding
        if (row.getBunnyVideoId() == null) {
            completeRow(row, null, runClaimedAt);
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
            Boolean applied = transactionTemplate.execute(status -> {
                // skillars-deferred-123 code review 2026-09-18 (Decision 5): claim the terminal
                // transition FIRST. decrementRefCount is a real, non-idempotent side effect, so it
                // must not run at all if this invocation no longer owns the claim.
                if (outboxRepository.completeClaimed(row.getId(), runClaimedAt) == 0) {
                    return Boolean.FALSE;
                }
                int decremented = drillVideoRefRepository.decrementRefCount(drillId);
                if (decremented == 0) {
                    log.warn("[DRILL_REF_ALREADY_ZERO videoId={} drillId={}] refCount already zero — physical delete skipped regardless",
                        row.getVideoId(), drillId);
                }
                appendDeletionLog(row, row.getBunnyVideoId());
                return Boolean.TRUE;
            });
            if (!Boolean.TRUE.equals(applied)) {
                logClaimLost(row, "drill-ref decrement");
                return;
            }
            log.debug("[DRILL_REF_DECREMENTED videoId={} drillId={}] refCount was {}, decremented — physical delete skipped",
                row.getVideoId(), drillId, capturedRefCount);
            return;
        }

        // Reload video: if providerAssetId already nulled, deletion already processed
        Optional<Video> videoOpt = videoRepository.findById(row.getVideoId());
        if (videoOpt.isPresent() && videoOpt.get().getProviderAssetId() == null) {
            completeRow(row, row.getBunnyVideoId(), runClaimedAt);
            return;
        }

        // Physical deletion via Bunny.net — outside @Transactional
        try {
            videoProviderAdapter.deleteAsset(row.getBunnyVideoId());
            completeRowWithNullAsset(row, runClaimedAt);
        } catch (Exception e) {
            handleFailure(row, e, runClaimedAt);
        }
    }

    private void completeRow(VideoDeletionOutbox row, String bunnyVideoId, Instant runClaimedAt) {
        Boolean applied = transactionTemplate.execute(status -> {
            if (outboxRepository.completeClaimed(row.getId(), runClaimedAt) == 0) {
                return Boolean.FALSE;
            }
            appendDeletionLog(row, bunnyVideoId);
            return Boolean.TRUE;
        });
        if (!Boolean.TRUE.equals(applied)) {
            logClaimLost(row, "completion");
        }
    }

    private void completeRowWithNullAsset(VideoDeletionOutbox row, Instant runClaimedAt) {
        Boolean applied = transactionTemplate.execute(status -> {
            if (outboxRepository.completeClaimed(row.getId(), runClaimedAt) == 0) {
                return Boolean.FALSE;
            }
            videoRepository.findById(row.getVideoId()).ifPresent(v -> {
                v.setProviderAssetId(null);
                videoRepository.save(v);
            });
            appendDeletionLog(row, row.getBunnyVideoId());
            return Boolean.TRUE;
        });
        if (!Boolean.TRUE.equals(applied)) {
            // The provider asset is already gone at this point — the delete happened before this
            // transaction. Losing the claim here means another instance is doing the same bookkeeping,
            // so skipping it is correct; what must not happen is writing stale columns over theirs.
            logClaimLost(row, "completion after provider delete");
        }
    }

    private void handleFailure(VideoDeletionOutbox row, Exception e, Instant runClaimedAt) {
        Boolean applied = transactionTemplate.execute(status -> {
            row.setAttempts(row.getAttempts() + 1);
            row.setLastError(e.getMessage());
            // skillars-deferred-107 AC2: 0/neg would dead-letter on the first attempt (or never).
            int maxAttempts = (int) configService.getBoundedLong("platform.video.deletion.max_attempts", 5L, 1L, 100L);
            // skillars-deferred-123 AC3: claimed_at cleared on BOTH outcomes below — DEAD as well as
            // PENDING. A DEAD row keeping a stale non-null claimed_at would give a false claim-age
            // reading if the row were ever re-queued by an unrelated path.
            row.setClaimedAt(null);
            if (row.getAttempts() >= maxAttempts) {
                row.setStatus("DEAD");
                log.error("[DEAD_LETTER videoId={} triggeredBy={}]", row.getVideoId(), row.getTriggeredBy());
            } else {
                long backoffMinutes = Math.min(60L, (long) Math.pow(2, row.getAttempts()));
                row.setNextRetryAt(Instant.now().plus(backoffMinutes, ChronoUnit.MINUTES));
                row.setStatus("PENDING");
            }
            // skillars-deferred-123 code review 2026-09-18 (Decision 5): conditional on still owning
            // the claim. attempts/nextRetryAt above are derived from this invocation's own possibly-
            // stale snapshot, so if another instance has re-claimed and advanced the row, writing them
            // would reset its attempt counter — the path by which max_attempts could never be reached.
            return outboxRepository.failClaimed(row.getId(), runClaimedAt, row.getStatus(),
                row.getAttempts(), row.getLastError(), row.getNextRetryAt()) > 0;
        });
        if (!Boolean.TRUE.equals(applied)) {
            logClaimLost(row, "failure bookkeeping");
        }
    }

    /**
     * skillars-deferred-123 code review 2026-09-18 (Decision 5): a lost claim is not an error — it
     * means another instance legitimately owns this row now (this one's lock expired mid-run, or the
     * stale-claim sweep reclaimed it) and is doing, or has already done, the same work. Logged at INFO
     * so the occurrence is observable without paging anyone, mirroring the benign-race log level
     * PaymentPendingSweeper and SessionPackExpiryNotifier already use for the same class of event.
     */
    private void logClaimLost(VideoDeletionOutbox row, String stage) {
        log.info("Skipped {} for video deletion outbox row {} (videoId={}) — this run no longer owns the "
                + "claim; another instance has taken it over", stage, row.getId(), row.getVideoId());
    }

    private void appendDeletionLog(VideoDeletionOutbox row, String bunnyVideoId) {
        VideoDeletionLog logRow = new VideoDeletionLog();
        logRow.setVideoId(row.getVideoId());
        logRow.setTriggeredBy(row.getTriggeredBy());
        logRow.setBunnyVideoId(bunnyVideoId);
        deletionLogRepository.save(logRow);
    }
}
