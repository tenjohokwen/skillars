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
     * (skillars-deferred-125 AC2, 2026-09-21: that equality no longer exists at all — its
     * {@code STALE_CLAIM_WINDOW} was widened to 15 minutes, restoring a real 5-minute buffer above its
     * {@code PT10M} lock, since the loop bound alone turned out not to be airtight against a single
     * slow row; see that class's own Javadoc.)
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
     *
     * <p><strong>skillars-deferred-126 AC1 (2026-09-21).</strong> The comparison this window feeds —
     * {@code resetStaleClaimed}'s {@code claimed_at < deadline} check — is now computed against the
     * database's own clock ({@code now()} inside the SQL itself), not each instance's own app clock,
     * matching {@code ShedLockConfig}'s {@code usingDbTime()} choice. Before this fix, if instance B's
     * clock ran ahead of instance A's by more than this window's margin above {@code LOCK_AT_MOST_FOR}
     * (the 5 minutes described above), B's sweep could free and immediately re-claim rows A was still
     * legitimately processing — a duplicate {@code deleteAsset} call, purely from clock skew, with no
     * crash on either instance. That cross-instance clock-skew hazard is now closed; this window's own
     * sizing rationale above (the buffer above {@code LOCK_AT_MOST_FOR}) is otherwise unchanged.
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
     * claimed_at = :claimedAt} (this run's own claim instant, stamped by {@code claimPendingBatch} —
     * skillars-deferred-126 AC1, 2026-09-21: the stamp is now the <em>database's</em> own claim
     * instant, {@code now()}, not the claiming JVM's own wall clock; see {@link VideoDeletionOutbox
     * #getClaimedAt()}'s Javadoc for why), closing that path completely. {@code skillars-deferred-124
     * AC4} later replaced that predicate with {@code claimed_by} (a dedicated run-identity {@code UUID},
     * see {@link VideoDeletionOutbox#getClaimedBy()}'s Javadoc) — {@code claimed_at} still flows to
     * {@code resetStaleClaimed}'s staleness math below, but is no longer the identity check itself.
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
     *
     * <p><strong>skillars-deferred-124 AC2</strong> (revised by its own code review, 2026-09-19).
     * {@link #processRow} does not catch anything itself — every exception it can throw (the
     * {@code deleteAsset}+{@code completeRowWithNullAsset} pair, the drill-ref branch's {@code
     * transactionTemplate.execute}, the plain {@code completeRow} path, {@code
     * videoRepository.findById}) propagates to {@link #process}'s own loop, which wraps each {@link
     * #processRow} call in a guard that routes the exception through {@link #handleFailure} — the SOLE
     * call site for it. An earlier version of this design had {@code processRow} call {@code
     * handleFailure} itself for the {@code deleteAsset} case and relied on this outer guard only as a
     * backstop for everything else; that let {@code handleFailure}'s own failure trigger a SECOND
     * {@code handleFailure} call from here, double-counting {@code attempts} and overwriting the real
     * exception's message with the second failure's. Routing every path through one call site fixes
     * both: {@code handleFailure} runs at most once per row per tick, and the exception it receives is
     * always the true original cause. Routing to {@code handleFailure} — rather than a bare
     * log-and-continue — lets a chronically-throwing row still reach {@code max_attempts}/{@code DEAD}
     * instead of cycling {@code CLAIMED} -> {@code resetStaleClaimed} -> re-claimed -> throws forever.
     */
    @Scheduled(fixedDelayString   = "${platform.video.deletion.outbox_poll_delay_ms:60000}",
               initialDelayString = "${platform.video.deletion.outbox_initial_delay_ms:0}")
    // skillars-deferred-123 AC4: lockAtLeastFor property-ized — see OutboxService.sweep's identical
    // comment for the rationale.
    @SchedulerLock(name = "VideoDeletionOutboxProcessor_process",
                   lockAtMostFor = LOCK_AT_MOST_FOR,
                   lockAtLeastFor = "${platform.video.deletion.outbox_lock_at_least:PT30S}")
    public void process() {
        // skillars-deferred-123 AC3: one Instant for the whole tick — stamps claimPendingBatch's
        // claimed_at and feeds resetStaleClaimed's staleness check (see STALE_CLAIM_WINDOW's Javadoc).
        Instant runClaimedAt = Instant.now();
        // skillars-deferred-124 AC4: one UUID for the whole tick, alongside runClaimedAt — this run's
        // own identity token. claimPendingBatch stamps it, and findClaimedBatch/releaseClaimed/
        // completeClaimed/failClaimed (via processRow) now key their identity predicate on it instead
        // of claimed_at-exact-equality. See VideoDeletionOutbox.claimedBy's Javadoc for why.
        UUID runId = UUID.randomUUID();
        // Reset any rows stuck in CLAIMED state for longer than STALE_CLAIM_WINDOW (crashed run
        // recovery) — see STALE_CLAIM_WINDOW's own Javadoc for the invariant this must respect.
        // skillars-deferred-126 AC1: passes the stale-window WIDTH, not a Java-computed absolute
        // deadline — resetStaleClaimed now computes the deadline itself from the database's own
        // now(), matching ShedLockConfig's usingDbTime() choice. See that method's own Javadoc.
        outboxRepository.resetStaleClaimed(STALE_CLAIM_WINDOW.toSeconds());
        // Atomically claim a batch of PENDING rows; row locks released after the UPDATE commits.
        // skillars-deferred-125 AC1: claimPendingBatch's UPDATE is its own auto-committing statement —
        // by the time it returns, up to BATCH_SIZE rows are durably CLAIMED under runId, independent of
        // anything that happens next in this method. If findClaimedBatch throws for a reason that
        // leaves the connection usable (a statement timeout, a row-mapping failure), those rows would
        // otherwise be stranded CLAIMED until the next resetStaleClaimed tick (up to STALE_CLAIM_WINDOW
        // later) even though this run's own runId is about to go out of scope and could never itself
        // recover them. The catch here releases the claim on any exception from this phase and
        // rethrows — never a bare try/finally, which would also fire on the successful path and race a
        // concurrent invocation into re-claiming rows before a single one has been processed.
        List<VideoDeletionOutbox> rows;
        try {
            outboxRepository.claimPendingBatch(runClaimedAt, runId, BATCH_SIZE);
            rows = outboxRepository.findClaimedBatch(runId, BATCH_SIZE);
        } catch (Exception e) {
            try {
                outboxRepository.releaseClaimed(runId);
            } catch (Exception inner) {
                // /bmad-code-review fix (2026-09-21): addSuppressed alongside the ERROR log — logging
                // alone means the two failures are only correlated via runId across separate log
                // lines; attaching inner to e means any exception-tracking tool that captures e
                // directly (this method still rethrows it) sees both in one stack trace.
                e.addSuppressed(inner);
                log.error("[RELEASE_CLAIMED_ITSELF_THREW runId={}] releaseClaimed threw while recovering "
                    + "from a claim-phase failure below — any rows this run claimed are left CLAIMED, "
                    + "recovered by the next stale-claim sweep", runId, inner);
            }
            throw e;
        }

        // skillars-deferred-123 code review 2026-09-18 (Decision 3): self-terminate before this run
        // could outlive its own lock. See MAX_RUN_DURATION's Javadoc for why the previous
        // per-item-cost sizing was not a real guarantee.
        Instant deadline = runClaimedAt.plus(MAX_RUN_DURATION);
        // skillars-deferred-124 code review 2026-09-19 (Patch): named "attempted", not "processed" —
        // it counts loop iterations entered, including a row that AC2's own outer guard routed to
        // handleFailure (success or failure) and even the rare case where handleFailure itself threw
        // and left the row CLAIMED. "Processed" implied every counted row reached a resolved terminal
        // state, which overstated genuine forward progress in the bail-out log below.
        int attempted = 0;
        for (VideoDeletionOutbox row : rows) {
            if (Instant.now().isAfter(deadline)) {
                // /bmad-code-review fix (2026-09-21): this releaseClaimed call was unguarded — the
                // same stranded-claim class AC1 hardened the claim phase against, on the path most
                // likely to fail (a run that has already been executing long enough to hit its own
                // time budget). A throw here used to propagate out of process() uninformatively;
                // logged and swallowed instead, mirroring AC1's own claim-phase guard shape — the
                // rows are left CLAIMED either way, recovered by the next stale-claim sweep.
                int released;
                try {
                    released = outboxRepository.releaseClaimed(runId);
                } catch (Exception e) {
                    log.error("[RELEASE_CLAIMED_ITSELF_THREW runId={}] releaseClaimed threw while "
                        + "releasing rows after hitting the {} safety budget — any rows this run "
                        + "claimed are left CLAIMED, recovered by the next stale-claim sweep", runId,
                        MAX_RUN_DURATION, e);
                    released = 0;
                }
                log.warn("Stopped video deletion outbox processing after {}/{} rows attempted — hit the "
                    + "{} safety budget under lockAtMostFor={}; released {} unprocessed row(s) back to "
                    + "PENDING for the next scheduled run", attempted, rows.size(), MAX_RUN_DURATION,
                    LOCK_AT_MOST_FOR, released);
                return;
            }
            // skillars-deferred-124 AC2: loop-level guard, and (code review 2026-09-19 response) the
            // SOLE call site for handleFailure — processRow itself no longer catches anything, it just
            // lets whatever it throws (deleteAsset, the drill-ref branch's transactionTemplate.execute,
            // the plain completeRow path, videoRepository.findById — any of it) propagate here. Before
            // this, an exception from any of those used to propagate out of this loop and abandon every
            // remaining row in the batch. Routing through handleFailure — the same attempt-increment/
            // backoff/dead-letter path every other failure already gets — rather than a bare log means
            // a chronically-throwing row still reaches max_attempts/DEAD instead of cycling CLAIMED ->
            // resetStaleClaimed -> re-claimed -> throws forever (resetStaleClaimed only resets
            // status/claimed_at, never attempts/next_retry_at). Logged at WARN, not ERROR: reaching
            // here and going through handleFailure for backoff/retry bookkeeping IS the normal outcome
            // for an ordinary recoverable failure (a Bunny.net hiccup, a transient DB blip) — this is
            // the whole reason the retry/backoff/dead-letter mechanism exists, not a paging-worthy
            // surprise. handleFailure itself gets its own inner guard, logged at ERROR: a failure
            // inside its own transactionTemplate.execute (e.g. configService.getBoundedLong) is the
            // genuinely exceptional case — this row cannot even be marked failed for this tick, and is
            // simply left CLAIMED, recovered once STALE_CLAIM_WINDOW elapses on the next stale-claim
            // sweep — still cannot abort the batch either way.
            try {
                processRow(row, runId);
            } catch (Exception e) {
                log.warn("[ROW_FAILURE videoId={} outboxId={}] processRow failed — routing to "
                    + "handleFailure for attempt/backoff bookkeeping", row.getVideoId(), row.getId(), e);
                try {
                    handleFailure(row, e, runId);
                } catch (Exception inner) {
                    log.error("[HANDLE_FAILURE_ITSELF_THREW videoId={} outboxId={}] handleFailure threw "
                        + "while recording the failure above — row left CLAIMED, recovered by the next "
                        + "stale-claim sweep", row.getVideoId(), row.getId(), inner);
                }
            }
            attempted++;
        }
    }

    private void processRow(VideoDeletionOutbox row, UUID runId) {
        // Null Bunny ID short-circuit: video never reached encoding
        if (row.getBunnyVideoId() == null) {
            completeRow(row, null, runId);
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
                if (outboxRepository.completeClaimed(row.getId(), runId) == 0) {
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
            completeRow(row, row.getBunnyVideoId(), runId);
            return;
        }

        // Physical deletion via Bunny.net — outside @Transactional. Exceptions here are deliberately
        // NOT caught locally (code review 2026-09-19, response to AC2's own review): process()'s outer
        // guard is now the sole call site for handleFailure on every processRow path, not just this
        // one. Before this, a local catch-and-call-handleFailure here meant that if handleFailure
        // itself threw (e.g. configService.getBoundedLong), that exception escaped this method
        // uncaught and reached the outer guard, which called handleFailure a SECOND time on the same
        // row — double-counting attempts and overwriting this exception's own message with
        // handleFailure's. Letting it propagate makes handleFailure single-call, which fixes both the
        // double-count and the lost-original-error problem as one structural change.
        videoProviderAdapter.deleteAsset(row.getBunnyVideoId());
        completeRowWithNullAsset(row, runId);
    }

    private void completeRow(VideoDeletionOutbox row, String bunnyVideoId, UUID runId) {
        Boolean applied = transactionTemplate.execute(status -> {
            if (outboxRepository.completeClaimed(row.getId(), runId) == 0) {
                return Boolean.FALSE;
            }
            appendDeletionLog(row, bunnyVideoId);
            return Boolean.TRUE;
        });
        if (!Boolean.TRUE.equals(applied)) {
            logClaimLost(row, "completion");
        }
    }

    private void completeRowWithNullAsset(VideoDeletionOutbox row, UUID runId) {
        Boolean applied = transactionTemplate.execute(status -> {
            if (outboxRepository.completeClaimed(row.getId(), runId) == 0) {
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

    private void handleFailure(VideoDeletionOutbox row, Exception e, UUID runId) {
        Boolean applied = transactionTemplate.execute(status -> {
            row.setAttempts(row.getAttempts() + 1);
            row.setLastError(e.getMessage());
            // skillars-deferred-107 AC2: 0/neg would dead-letter on the first attempt (or never).
            int maxAttempts = (int) configService.getBoundedLong("platform.video.deletion.max_attempts", 5L, 1L, 100L);
            // skillars-deferred-123 AC3 / skillars-deferred-124 AC4: claimed_at/claimed_by cleared on
            // BOTH outcomes below — DEAD as well as PENDING. A DEAD row keeping a stale non-null
            // claimed_at/claimed_by would give a false claim-age/identity reading if the row were ever
            // re-queued by an unrelated path.
            row.setClaimedAt(null);
            row.setClaimedBy(null);
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
            return outboxRepository.failClaimed(row.getId(), runId, row.getStatus(),
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
