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

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

// Deferred-77 AC10 Phase 2 — replays radar composite calculations that failed even after the
// original @Async listener invocation. Mirrors VideoDeletionOutboxProcessor's claim/process/backoff
// shape (V59) rather than introducing a new queueing mechanism for this codebase.
//
// skillars-deferred-118 AC3: findClaimedBatch() was not scoped to the calling invocation's own claim
// and RadarCompositeDlqEntry carries no @Version, so two concurrent invocations could each read and
// unconditionally overwrite the other's status/attempts/lastError/nextRetryAt on the same row with
// no optimistic-lock protection at all.
//
// skillars-deferred-123 code review 2026-09-18 (Patch — this comment previously still claimed
// @SchedulerLock alone closes this, which is only true while the lock is held). AC3 scoped
// findClaimedBatch() to claimed_at = :claimedAt (this run's own claim instant), closing the fetch
// side regardless of lock state. Decision 5 additionally guards every terminal write
// (completeClaimed/failClaimed) on `status = 'CLAIMED' AND claimed_at = :claimedAt`, so even a run
// that outlives lockAtMostFor and loses its claim to a stale-recovery sweep cannot corrupt another
// instance's in-flight bookkeeping — a lost race writes 0 rows and is skipped, not silently applied.
//
// skillars-deferred-124 AC2 (revised by its own code review, 2026-09-19): processRow no longer
// catches anything itself. process()'s own loop wraps each processRow call in a guard that is now the
// SOLE call site for handleFailure, so a chronically-failing recalculateComposite/completeClaimed
// reaches max_attempts/DEAD instead of cycling CLAIMED -> resetStaleClaimed -> re-claimed -> throws
// forever. One residual case does NOT reach DEAD, and is not a regression of this AC: if
// configService.getBoundedLong itself is permanently broken (not the row's own processing — a config
// lookup failure), handleFailure can never complete its own transaction, so attempts is never
// persisted and the row stays CLAIMED -> reset -> re-claimed -> retried indefinitely. This is accepted
// as safe (not a poison row in the harmful sense — it self-heals the moment config lookups recover,
// and never loses or corrupts the row) rather than fixed; see
// handleFailure_itselfThrows_stillIsolatesBatchAndDoesNotAbortTheLoop for the exact scenario.
@Slf4j
@Component
@RequiredArgsConstructor
public class RadarCompositeDlqProcessor {

    private static final int BATCH_SIZE = 50;

    /**
     * skillars-deferred-123 code review 2026-09-18 (Decision 3). This class's stale-claim window and
     * its {@code lockAtMostFor} were <em>exactly equal</em> (10 minutes vs {@code PT10M}) — zero
     * buffer — while its structural twin {@code VideoDeletionOutboxProcessor} documents strict
     * inequality between the two as mandatory (skillars-deferred-120 Decision 1). That twin's Javadoc
     * also claimed this equality was "an accepted, unfixed weakness recorded in
     * {@code deferred-work.md}"; it was not recorded anywhere in the ledger.
     *
     * <p>The equality only became load-bearing with AC3. Before it, {@code resetStaleClaimed} keyed on
     * {@code next_retry_at} (eligibility time), so lock expiry and the stale window were unrelated
     * clocks. Keyed on {@code claimed_at} (actual claim time), the relationship is now the only thing
     * stopping a still-running tick from having its own rows reclaimed underneath it: at lock expiry
     * {@code T+10m}, the next instance computes {@code deadline = now - 10m}, which is already past
     * this run's {@code claimed_at}, so it resets and immediately re-claims rows this instance is
     * still processing — duplicate {@code recalculateComposite} on the same row.
     *
     * <p>This budget bounds the run at the source — the run cannot still be going when the lock
     * expires. Mirrors {@code QuotaReservationTimeoutService.MAX_RUN_DURATION} (8 minutes under a
     * {@code PT10M} lock) — the same lock duration, so the same 8 minutes. Unprocessed rows are handed
     * straight back to {@code PENDING} via {@code releaseClaimed} for the next firing (default 60s), so
     * nothing is lost and nothing waits on the stale sweep.
     *
     * <p><strong>skillars-deferred-125 AC2 (2026-09-21): "fixed at the source rather than by widening
     * the window" is no longer this class's whole story.</strong> {@code skillars-deferred-124}'s own
     * code review found this bound is sampled only at the top of {@link #process()}'s loop — never
     * inside {@code processRow}/{@code handleFailure} itself — so one row that individually blocks
     * longer than {@code lockAtMostFor - MAX_RUN_DURATION} (a 2-minute margin here) still overruns the
     * lock despite this budget existing. With the zero-margin equality this Javadoc used to defend,
     * that single-row overrun immediately triggered a duplicate reclaim on the very next tick. A real
     * buffer between {@code lockAtMostFor} and {@link #STALE_CLAIM_WINDOW} (see that field's own
     * Javadoc) is restored as defense-in-depth on top of this bound, not instead of it — this bound
     * still does the majority of the work by keeping the run itself inside the lock under normal
     * operation; the window buffer only matters for the single-slow-row residual this bound does not
     * reach. That narrower residual is accepted, not fixed, by this AC — see
     * {@code deferred-work.md}'s corresponding ledger entry.
     */
    private static final Duration MAX_RUN_DURATION = Duration.ofMinutes(8);

    /**
     * skillars-deferred-123 code review 2026-09-18 (Decision 3): extracted from the inline
     * {@code minus(10, ChronoUnit.MINUTES)} literal it used to be, so the relationship between it,
     * {@link #MAX_RUN_DURATION} and this scheduler's {@code lockAtMostFor} is stated in one place and
     * can be asserted — matching {@code VideoDeletionOutboxProcessor.STALE_CLAIM_WINDOW}, whose Javadoc
     * this class's inline literal previously had no counterpart to.
     *
     * <p><strong>skillars-deferred-125 AC2 (2026-09-21): widened from 10 to 15 minutes, restoring a
     * real 5-minute buffer above {@code lockAtMostFor}'s {@code PT10M}.</strong> The value was
     * previously "deliberately unchanged at 10 minutes" on the reasoning that, once the run is bounded
     * at {@link #MAX_RUN_DURATION}, the equality with {@code lockAtMostFor} is safe — but that reasoning
     * assumed {@code MAX_RUN_DURATION} is a hard, continuously-enforced ceiling. It is not: {@code
     * skillars-deferred-124}'s own code review found the deadline is sampled only between loop
     * iterations, so a single row that individually blocks longer than the lock/{@code
     * MAX_RUN_DURATION} margin (2 minutes) can still overrun the lock. Once that happens, the previous
     * zero-margin equality meant the very next tick's {@code resetStaleClaimed} immediately freed and
     * re-claimed the still-processing run's rows — a real duplicate {@code recalculateComposite}, an
     * external side effect {@code claimed_by} cannot undo after the fact, only prevent from being
     * written twice. This class's structural twin, {@code VideoDeletionOutboxProcessor}, keeps the
     * equivalent 5-minute buffer amount between its own {@code LOCK_AT_MOST_FOR (15m)} and
     * {@code STALE_CLAIM_WINDOW (20m)} — this restores the same buffer amount here, not merely the same
     * ratio. {@link #MAX_RUN_DURATION}'s single-row-overrun residual is accepted, not eliminated, by
     * this widening — see that field's own Javadoc — but is now materially less consequential, since a
     * single overrunning row no longer immediately triggers a duplicate reclaim.
     *
     * <p><strong>Trade-off.</strong> Widening this window also raises this class's crash-recovery
     * latency (how long a genuinely dead instance's rows sit {@code CLAIMED} before the stale sweep
     * frees them) from 10 to 15 minutes. Mirroring {@code VideoDeletionOutboxProcessor.STALE_CLAIM_WINDOW}'s
     * own equivalent sentence for its 20-minute value: this is immaterial for a DLQ processor polled
     * every 60 seconds under normal operation — the extra 5 minutes only matters in the rare case of a
     * genuinely crashed instance, and even then only delays recovery, it does not lose work.
     *
     * <p><strong>skillars-deferred-126 AC1 (2026-09-21).</strong> The comparison this window feeds —
     * {@code resetStaleClaimed}'s {@code claimed_at < deadline} check — is now computed against the
     * database's own clock ({@code now()} inside the SQL itself), not each instance's own app clock,
     * matching {@code ShedLockConfig}'s {@code usingDbTime()} choice. Before this fix, if instance B's
     * clock ran ahead of instance A's by more than this window's margin above {@code lockAtMostFor}
     * (the 5 minutes described above), B's sweep could free and immediately re-claim rows A was still
     * legitimately processing — a duplicate {@code recalculateComposite}, purely from clock skew, with
     * no crash on either instance. That cross-instance clock-skew hazard is now closed; this window's
     * own sizing rationale above (the buffer above {@code lockAtMostFor}) is otherwise unchanged.
     */
    private static final Duration STALE_CLAIM_WINDOW = Duration.ofMinutes(15);

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
    // skillars-deferred-123 AC4: lockAtLeastFor property-ized — see OutboxService.sweep's identical
    // comment for the rationale.
    @SchedulerLock(name = "RadarCompositeDlqProcessor_process",
                   lockAtMostFor = "PT10M",
                   lockAtLeastFor = "${platform.development.radar_composite_dlq.lock_at_least:PT30S}")
    public void process() {
        // skillars-deferred-123 AC3: one Instant for the whole tick — see
        // VideoDeletionOutboxProcessor.process()'s identical comment for the full rationale.
        Instant runClaimedAt = Instant.now();
        // skillars-deferred-124 AC4: one UUID for the whole tick — see
        // VideoDeletionOutboxProcessor.process()'s identical comment for the full rationale.
        UUID runId = UUID.randomUUID();
        // skillars-deferred-126 AC1: passes the stale-window WIDTH, not a Java-computed absolute
        // deadline — resetStaleClaimed now computes the deadline itself from the database's own
        // now(), matching ShedLockConfig's usingDbTime() choice. See that method's own Javadoc.
        dlqRepository.resetStaleClaimed(STALE_CLAIM_WINDOW.toSeconds());
        // skillars-deferred-125 AC1: release a stranded claim on abnormal exit from this phase — see
        // VideoDeletionOutboxProcessor.process()'s identical comment for the full rationale. Not a
        // try/finally: that would also fire on the successful path and undo the claim before a single
        // row is processed.
        List<RadarCompositeDlqEntry> rows;
        try {
            dlqRepository.claimPendingBatch(runClaimedAt, runId, BATCH_SIZE);
            rows = dlqRepository.findClaimedBatch(runId, BATCH_SIZE);
        } catch (Exception e) {
            try {
                dlqRepository.releaseClaimed(runId);
            } catch (Exception inner) {
                // /bmad-code-review fix (2026-09-21): addSuppressed alongside the ERROR log — see
                // VideoDeletionOutboxProcessor.process()'s identical comment for the full rationale.
                e.addSuppressed(inner);
                log.error("[RELEASE_CLAIMED_ITSELF_THREW runId={}] releaseClaimed threw while recovering "
                    + "from a claim-phase failure below — any rows this run claimed are left CLAIMED, "
                    + "recovered by the next stale-claim sweep", runId, inner);
            }
            throw e;
        }

        // skillars-deferred-123 code review 2026-09-18 (Decision 3): self-terminate before this run
        // could outlive its own PT10M lock — see MAX_RUN_DURATION's Javadoc for why the previously
        // exact window/lock equality made that a live duplicate-processing path once AC3 landed.
        Instant deadline = runClaimedAt.plus(MAX_RUN_DURATION);
        // skillars-deferred-124 code review 2026-09-19 (Patch): named "attempted", not "processed" —
        // see VideoDeletionOutboxProcessor.process()'s identical comment for why.
        int attempted = 0;
        for (RadarCompositeDlqEntry row : rows) {
            if (Instant.now().isAfter(deadline)) {
                // /bmad-code-review fix (2026-09-21): this releaseClaimed call was unguarded — the
                // same stranded-claim class AC1 hardened the claim phase against, on the path most
                // likely to fail (a run that has already been executing long enough to hit its own
                // time budget). Logged and swallowed instead, mirroring AC1's own claim-phase guard
                // shape (and this method's own inner guard above) — the rows are left CLAIMED either
                // way, recovered by the next stale-claim sweep.
                int released;
                try {
                    released = dlqRepository.releaseClaimed(runId);
                } catch (Exception e) {
                    log.error("[RELEASE_CLAIMED_ITSELF_THREW runId={}] releaseClaimed threw while "
                        + "releasing rows after hitting the {} safety budget — any rows this run "
                        + "claimed are left CLAIMED, recovered by the next stale-claim sweep", runId,
                        MAX_RUN_DURATION, e);
                    released = 0;
                }
                log.warn("Stopped radar composite DLQ processing after {}/{} rows attempted — hit the "
                    + "{} safety budget under lockAtMostFor=PT10M; released {} unprocessed row(s) back "
                    + "to PENDING for the next scheduled run", attempted, rows.size(), MAX_RUN_DURATION, released);
                return;
            }
            // skillars-deferred-124 AC2 (revised by its own code review, 2026-09-19): loop-level guard
            // and the SOLE call site for handleFailure — processRow no longer catches anything itself,
            // see its own comment for why. Logged at WARN, not ERROR: reaching handleFailure for
            // attempt/backoff bookkeeping is the normal outcome for an ordinary recoverable failure, not
            // a paging-worthy surprise — see VideoDeletionOutboxProcessor.process()'s identical comment.
            // handleFailure's own inner guard stays at ERROR: a failure inside its own
            // transactionTemplate.execute (e.g. configService.getBoundedLong) is genuinely exceptional —
            // the row cannot even be marked failed for this tick and is left CLAIMED, recovered by the
            // next stale-claim sweep — but still cannot abort the batch either way.
            try {
                processRow(row, runId);
            } catch (Exception e) {
                log.warn("[ROW_FAILURE playerId={} dlqId={}] processRow failed — routing to "
                    + "handleFailure for attempt/backoff bookkeeping", row.getPlayerId(), row.getId(), e);
                try {
                    handleFailure(row, e, runId);
                } catch (Exception inner) {
                    log.error("[HANDLE_FAILURE_ITSELF_THREW playerId={} dlqId={}] handleFailure threw "
                        + "while recording the failure above — row left CLAIMED, recovered by the next "
                        + "stale-claim sweep", row.getPlayerId(), row.getId(), inner);
                }
            }
            attempted++;
        }
    }

    private void processRow(RadarCompositeDlqEntry row, UUID runId) {
        // skillars-deferred-124 code review 2026-09-19 response: deliberately no local try/catch here
        // any more — see process()'s own comment for why letting everything propagate to the outer
        // guard (the SOLE call site for handleFailure) is what fixes the double-handleFailure-call bug.
        compositeCalculationService.recalculateComposite(
            row.getPlayerId(), row.getParentId(), Set.copyOf(row.getSkillCodes()));
        // skillars-deferred-123 code review 2026-09-18 (Decision 5): conditional on this run still
        // owning the claim — see the repository methods' comment for the lost-update mechanism.
        Boolean applied = transactionTemplate.execute(status ->
            dlqRepository.completeClaimed(row.getId(), runId) > 0);
        if (!Boolean.TRUE.equals(applied)) {
            logClaimLost(row, "completion");
        }
    }

    private void handleFailure(RadarCompositeDlqEntry row, Exception e, UUID runId) {
        Boolean applied = transactionTemplate.execute(status -> {
            row.setAttempts(row.getAttempts() + 1);
            row.setLastError(e.getMessage());
            // skillars-deferred-107 AC2: 0/neg would dead-letter on the first attempt (or never).
            int maxAttempts = (int) configService.getBoundedLong(
                "platform.development.radar_composite_dlq.max_attempts", 5L, 1L, 100L);
            // skillars-deferred-123 AC3 / skillars-deferred-124 AC4: cleared on BOTH outcomes — see
            // VideoDeletionOutboxProcessor.handleFailure's identical comment for why DEAD must clear
            // both fields too, not just PENDING.
            row.setClaimedAt(null);
            row.setClaimedBy(null);
            if (row.getAttempts() >= maxAttempts) {
                row.setStatus("DEAD");
                log.error("[DEAD_LETTER playerId={} skillCodes={}] radar composite recalculation exhausted retries",
                    row.getPlayerId(), row.getSkillCodes());
            } else {
                long backoffMinutes = Math.min(60L, (long) Math.pow(2, row.getAttempts()));
                row.setNextRetryAt(Instant.now().plus(backoffMinutes, ChronoUnit.MINUTES));
                row.setStatus("PENDING");
            }
            // skillars-deferred-123 code review 2026-09-18 (Decision 5): conditional on still owning
            // the claim — attempts/nextRetryAt are derived from this invocation's own possibly-stale
            // snapshot, so writing them over a re-claiming instance's would reset its attempt counter.
            return dlqRepository.failClaimed(row.getId(), runId, row.getStatus(),
                row.getAttempts(), row.getLastError(), row.getNextRetryAt()) > 0;
        });
        if (!Boolean.TRUE.equals(applied)) {
            logClaimLost(row, "failure bookkeeping");
        }
    }

    /**
     * skillars-deferred-123 code review 2026-09-18 (Decision 5): mirrors
     * {@code VideoDeletionOutboxProcessor.logClaimLost} — a lost claim is a benign race, not an error.
     */
    private void logClaimLost(RadarCompositeDlqEntry row, String stage) {
        log.info("Skipped {} for radar composite DLQ row {} (playerId={}) — this run no longer owns the "
                + "claim; another instance has taken it over", stage, row.getId(), row.getPlayerId());
    }
}
