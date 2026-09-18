package com.softropic.skillars.platform.video.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface VideoDeletionOutboxRepository extends JpaRepository<VideoDeletionOutbox, UUID> {

    // Phase 1: atomically claim a batch by updating status to CLAIMED within a single transaction.
    // FOR UPDATE SKIP LOCKED in the subquery prevents concurrent processors from double-claiming.
    // skillars-deferred-123 AC3: claimed_at stamped with this tick's own claim instant — see
    // VideoDeletionOutbox.claimedAt's Javadoc for why (resetStaleClaimed/findClaimedBatch below).
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE main.video_deletion_outbox
        SET status = 'CLAIMED', claimed_at = :now
        WHERE id = ANY(
            SELECT id FROM main.video_deletion_outbox
            WHERE status = 'PENDING' AND next_retry_at <= :now
            ORDER BY next_retry_at ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
        )
        """, nativeQuery = true)
    int claimPendingBatch(@Param("now") Instant now, @Param("batchSize") int batchSize);

    // Phase 2: fetch the rows this processor just claimed. skillars-deferred-123 AC3: scoped to this
    // run's own claimed_at (previously globally scoped to status = 'CLAIMED' alone, with no per-run
    // filter — a second instance's tick would return a still-in-flight first instance's rows too).
    @Query(value = """
        SELECT * FROM main.video_deletion_outbox
        WHERE status = 'CLAIMED' AND claimed_at = :claimedAt
        ORDER BY next_retry_at ASC
        LIMIT :batchSize
        """, nativeQuery = true)
    List<VideoDeletionOutbox> findClaimedBatch(@Param("claimedAt") Instant claimedAt, @Param("batchSize") int batchSize);

    // Recover rows stuck in CLAIMED state from a prior crashed run. skillars-deferred-123 AC3: keyed
    // on claimed_at (actual claim time), not next_retry_at (eligibility time) — a row backlogged well
    // before this run started was previously judged "stale" based on how long it sat in the queue,
    // not how long it had actually been claimed.
    // skillars-deferred-123 code review 2026-09-18 (Patch): also matches claimed_at IS NULL, not just
    // claimed_at IS NOT NULL. V144's backfill only covers rows already CLAIMED at migration time — a
    // pre-migration instance still running during a rolling deploy claims rows the OLD WAY (no
    // claimed_at stamp) after the migration has already committed. Without this, such a row matches
    // neither this reset query nor the now claim-scoped findClaimedBatch, and resetStaleClaimed is the
    // only recovery path for CLAIMED rows — it would be permanently unrecoverable. Also clears
    // claimed_at back to NULL, matching the field's own documented invariant (cleared on every
    // transition out of CLAIMED) — this was the one transition that previously left it stale.
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE main.video_deletion_outbox
        SET status = 'PENDING', claimed_at = NULL
        WHERE status = 'CLAIMED' AND (claimed_at IS NULL OR claimed_at < :deadline)
        """, nativeQuery = true)
    int resetStaleClaimed(@Param("deadline") Instant deadline);

    // skillars-deferred-123 code review 2026-09-18 (Decision 3): hand this run's OWN unprocessed rows
    // straight back to PENDING when the processor self-terminates on its MAX_RUN_DURATION budget.
    // Scoped to `claimed_at = :claimedAt`, so it can only ever touch rows this invocation claimed —
    // never a concurrent instance's. Every row the loop actually finished has already had claimed_at
    // cleared (COMPLETED and both handleFailure outcomes all null it), so "still CLAIMED with this
    // run's stamp" is exactly the unprocessed remainder. Without this, a bailed-out batch would sit
    // CLAIMED until STALE_CLAIM_WINDOW elapsed (20 minutes) even though the rows are known-good and
    // the very next tick could take them. claimed_at is nulled here too, keeping the field's documented
    // invariant — cleared on every transition out of CLAIMED — true for this path.
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE main.video_deletion_outbox
        SET status = 'PENDING', claimed_at = NULL
        WHERE status = 'CLAIMED' AND claimed_at = :claimedAt
        """, nativeQuery = true)
    int releaseClaimed(@Param("claimedAt") Instant claimedAt);

    // skillars-deferred-123 code review 2026-09-18 (Decision 5): the terminal writes below replace an
    // unconditional `outboxRepository.save(row)`. That save was an em.merge() of a DETACHED entity —
    // process() is not @Transactional and findClaimedBatch is a native query run outside a transaction,
    // so every row handed to processRow is detached — which rewrote EVERY column from a possibly-stale
    // snapshot, with no @Version on this entity and no @DynamicUpdate. If another instance re-claimed
    // the row and committed attempts/backoff in the meantime, the stale merge silently erased it; in
    // the other order it resurrected a COMPLETED row to PENDING with attempts reset to 0, so
    // max_attempts could never be reached and the row would be dispatched forever.
    //
    // The `status = 'CLAIMED' AND claimed_at = :claimedAt` predicate makes each write conditional on
    // this invocation STILL OWNING the claim it started with. A losing race affects 0 rows, and the
    // caller skips its side effects entirely rather than rolling them back — the same shape
    // DeletionSchedulerService.markPhysicallyDeleted already uses in this codebase.
    //
    // Only the columns this transition actually owns are written, so nothing else can be clobbered.
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE main.video_deletion_outbox
        SET status = 'COMPLETED', claimed_at = NULL
        WHERE id = :id AND status = 'CLAIMED' AND claimed_at = :claimedAt
        """, nativeQuery = true)
    int completeClaimed(@Param("id") UUID id, @Param("claimedAt") Instant claimedAt);

    @Modifying
    @Transactional
    @Query(value = """
        UPDATE main.video_deletion_outbox
        SET status = :status, attempts = :attempts, last_error = :lastError,
            next_retry_at = :nextRetryAt, claimed_at = NULL
        WHERE id = :id AND status = 'CLAIMED' AND claimed_at = :claimedAt
        """, nativeQuery = true)
    int failClaimed(@Param("id") UUID id, @Param("claimedAt") Instant claimedAt,
                    @Param("status") String status, @Param("attempts") int attempts,
                    @Param("lastError") String lastError, @Param("nextRetryAt") Instant nextRetryAt);

    boolean existsByVideoIdAndStatus(UUID videoId, String status);
}
