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
     * <p>Fixed at the source rather than by widening the window: {@link #process()} self-terminates at
     * this budget, so the run cannot still be going when the lock expires. Mirrors
     * {@code QuotaReservationTimeoutService.MAX_RUN_DURATION} (8 minutes under a {@code PT10M} lock) —
     * the same lock duration, so the same 8 minutes. Unprocessed rows are handed straight back to
     * {@code PENDING} via {@code releaseClaimed} for the next firing (default 60s), so nothing is lost
     * and nothing waits on the stale sweep.
     */
    private static final Duration MAX_RUN_DURATION = Duration.ofMinutes(8);

    /**
     * skillars-deferred-123 code review 2026-09-18 (Decision 3): extracted from the inline
     * {@code minus(10, ChronoUnit.MINUTES)} literal it used to be, so the relationship between it,
     * {@link #MAX_RUN_DURATION} and this scheduler's {@code lockAtMostFor} is stated in one place and
     * can be asserted — matching {@code VideoDeletionOutboxProcessor.STALE_CLAIM_WINDOW}, whose Javadoc
     * this class's inline literal previously had no counterpart to. Value deliberately unchanged at 10
     * minutes: with the run now bounded at {@link #MAX_RUN_DURATION}, widening the window is no longer
     * what makes the equality with {@code PT10M} safe — the bound is.
     */
    private static final Duration STALE_CLAIM_WINDOW = Duration.ofMinutes(10);

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
        dlqRepository.resetStaleClaimed(runClaimedAt.minus(STALE_CLAIM_WINDOW));
        dlqRepository.claimPendingBatch(runClaimedAt, BATCH_SIZE);
        List<RadarCompositeDlqEntry> rows = dlqRepository.findClaimedBatch(runClaimedAt, BATCH_SIZE);

        // skillars-deferred-123 code review 2026-09-18 (Decision 3): self-terminate before this run
        // could outlive its own PT10M lock — see MAX_RUN_DURATION's Javadoc for why the previously
        // exact window/lock equality made that a live duplicate-processing path once AC3 landed.
        Instant deadline = runClaimedAt.plus(MAX_RUN_DURATION);
        int processed = 0;
        for (RadarCompositeDlqEntry row : rows) {
            if (Instant.now().isAfter(deadline)) {
                int released = dlqRepository.releaseClaimed(runClaimedAt);
                log.warn("Stopped radar composite DLQ processing after {}/{} rows — hit the {} safety "
                    + "budget under lockAtMostFor=PT10M; released {} unprocessed row(s) back to PENDING "
                    + "for the next scheduled run", processed, rows.size(), MAX_RUN_DURATION, released);
                return;
            }
            processRow(row, runClaimedAt);
            processed++;
        }
    }

    private void processRow(RadarCompositeDlqEntry row, Instant runClaimedAt) {
        try {
            compositeCalculationService.recalculateComposite(
                row.getPlayerId(), row.getParentId(), Set.copyOf(row.getSkillCodes()));
            // skillars-deferred-123 code review 2026-09-18 (Decision 5): conditional on this run still
            // owning the claim — see the repository methods' comment for the lost-update mechanism.
            Boolean applied = transactionTemplate.execute(status ->
                dlqRepository.completeClaimed(row.getId(), runClaimedAt) > 0);
            if (!Boolean.TRUE.equals(applied)) {
                logClaimLost(row, "completion");
            }
        } catch (Exception e) {
            handleFailure(row, e, runClaimedAt);
        }
    }

    private void handleFailure(RadarCompositeDlqEntry row, Exception e, Instant runClaimedAt) {
        Boolean applied = transactionTemplate.execute(status -> {
            row.setAttempts(row.getAttempts() + 1);
            row.setLastError(e.getMessage());
            // skillars-deferred-107 AC2: 0/neg would dead-letter on the first attempt (or never).
            int maxAttempts = (int) configService.getBoundedLong(
                "platform.development.radar_composite_dlq.max_attempts", 5L, 1L, 100L);
            // skillars-deferred-123 AC3: cleared on BOTH outcomes — see
            // VideoDeletionOutboxProcessor.handleFailure's identical comment for why DEAD must clear
            // it too, not just PENDING.
            row.setClaimedAt(null);
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
            return dlqRepository.failClaimed(row.getId(), runClaimedAt, row.getStatus(),
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
