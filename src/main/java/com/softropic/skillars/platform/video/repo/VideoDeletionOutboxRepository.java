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
    // skillars-deferred-123 AC3: claimed_at stamped at claim time — see VideoDeletionOutbox.claimedAt's
    // Javadoc for why (resetStaleClaimed below).
    // /bmad-code-review fix (2026-09-21): the stamp is no longer "this tick's own [app-clock] claim
    // instant" — skillars-deferred-126 AC1 below moved it to the database's own now(), so this
    // comment's own wording was directly contradicted by the very next paragraph. See AC1's comment
    // further down for the DB-time rationale.
    // skillars-deferred-124 AC4: claimed_by stamped with this tick's own run id (UUID.randomUUID(),
    // generated once per tick) alongside claimed_at — see VideoDeletionOutbox.claimedBy's Javadoc.
    // claimed_at keeps flowing to resetStaleClaimed's staleness check; claimed_by is now what
    // findClaimedBatch/releaseClaimed/completeClaimed/failClaimed key their identity predicate on.
    // skillars-deferred-126 AC1: claimed_at's stamp moved from the app-clock :now bind parameter to
    // the database's own now() — matching ShedLockConfig's usingDbTime() choice for the identical
    // cross-instance clock-skew reason. Scoped to this ONE clause only: next_retry_at <= :now (the
    // eligibility predicate, sharing the same :now bind parameter) is deliberately left untouched —
    // next_retry_at is itself app-clock-stamped elsewhere (row insertion, backoff computation), so
    // making eligibility DB-time too would require touching every writer of that column, a much
    // larger change than this fix's scope. Postgres allows a literal now() call and a bound
    // parameter in the same UPDATE statement freely. See resetStaleClaimed's own Javadoc below for
    // the comparison this stamp change was made for.
    // skillars-deferred-128 AC5: now() further replaced with clock_timestamp() for THIS claim-stamp
    // write. now() is Postgres's transaction_timestamp() — constant for the whole transaction, not
    // statement-accurate — so this stamp was only ever correct because process() carries no
    // @Transactional and ShedLock's own accessor runs REQUIRES_NEW, giving each @Modifying
    // @Transactional repository call its own fresh transaction where now() happens to equal
    // statement time. Nothing enforced that; adding @Transactional to process() (or calling it from
    // any transactional caller) in some future change would silently join this statement and
    // resetStaleClaimed's read into one transaction, stamping claimed_at with the OUTER
    // transaction's start time and freezing the staleness deadline for the whole run — eroding the
    // MAX_RUN_DURATION < lockAtMostFor < STALE_CLAIM_WINDOW margin (skillars-deferred-126 AC1) from
    // both ends at once, silently. clock_timestamp() returns the actual wall-clock time at the
    // moment it is evaluated, independent of transaction boundaries, removing that hidden coupling
    // entirely — for THIS timestamp symptom specifically (story review, 2026-09-22): a future
    // @Transactional process() would still break claim visibility (findClaimedBatch's own read would
    // then run inside the SAME uncommitted transaction as this claim) and deleteAsset's side-effect
    // atomicity far worse than this one stamp ever could; this fix does not address those. Confirmed
    // safe against per-row (not per-statement) evaluation: this codebase's
    // batch-identity predicate moved off claimed_at onto claimed_by back in skillars-deferred-124
    // AC4 (see findClaimedBatch/releaseClaimed/completeClaimed/failClaimed below), so a multi-row
    // claim landing slightly different claimed_at values per row is harmless today.
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE main.video_deletion_outbox
        SET status = 'CLAIMED', claimed_at = clock_timestamp(), claimed_by = :runId
        WHERE id = ANY(
            SELECT id FROM main.video_deletion_outbox
            WHERE status = 'PENDING' AND next_retry_at <= :now
            ORDER BY next_retry_at ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
        )
        """, nativeQuery = true)
    int claimPendingBatch(@Param("now") Instant now, @Param("runId") UUID runId, @Param("batchSize") int batchSize);

    // Phase 2: fetch the rows this processor just claimed. skillars-deferred-123 AC3: scoped to this
    // run's own claim (previously globally scoped to status = 'CLAIMED' alone, with no per-run
    // filter — a second instance's tick would return a still-in-flight first instance's rows too).
    // skillars-deferred-124 AC4: identity predicate moved from claimed_at-exact-equality to
    // claimed_by — see VideoDeletionOutbox.claimedBy's Javadoc for why.
    @Query(value = """
        SELECT * FROM main.video_deletion_outbox
        WHERE status = 'CLAIMED' AND claimed_by = :runId
        ORDER BY next_retry_at ASC
        LIMIT :batchSize
        """, nativeQuery = true)
    List<VideoDeletionOutbox> findClaimedBatch(@Param("runId") UUID runId, @Param("batchSize") int batchSize);

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
    // skillars-deferred-124 AC4: the WHERE predicate stays keyed on claimed_at (a time comparison, not
    // an identity one — this method's whole purpose is judging staleness by elapsed claim time, which
    // claimed_by carries no information about), but claimed_by is cleared here too — this transitions
    // a row out of CLAIMED exactly like every other terminal path, and the invariant applies
    // regardless of which predicate triggered the transition.
    // skillars-deferred-126 AC1: takes the stale-window WIDTH (seconds), not a Java-computed absolute
    // deadline — the deadline is now computed inside the SQL itself via the database's own now(),
    // matching claimPendingBatch's identical DB-time move above and ShedLockConfig's usingDbTime().
    // If instance B's app clock ran ahead of instance A's by more than this window's margin above
    // lockAtMostFor, the old app-clock deadline let B's sweep free and immediately re-claim rows A was
    // still legitimately processing — a duplicate videoProviderAdapter.deleteAsset call, an
    // externally-visible side effect claimed_by cannot undo after the fact. make_interval(secs => ...)
    // is used rather than now() - (:staleWindowSeconds * interval '1 second') for clarity and
    // unambiguous parameter typing, NOT to avoid a numeric cast (/bmad-code-review fix, 2026-09-21:
    // the original rationale here was factually wrong — make_interval's own secs parameter is ITSELF
    // declared double precision, so binding a bindable long performs the identical implicit bigint ->
    // double precision cast either way). The real benefit is that a named function argument gives
    // Postgres's parser an unambiguous target type to infer the bound parameter's type from, avoiding
    // a "could not determine data type of parameter" error the bare multiplication form can trigger,
    // and it reads unambiguously as "an interval of N seconds" rather than relying on
    // interval-arithmetic operator precedence.
    // skillars-deferred-128 AC5: the staleness comparison's now() replaced with clock_timestamp() to
    // match claimPendingBatch's identical stamp change above — see that method's Javadoc for the
    // full rationale (transaction_timestamp() vs. statement-accurate time). Only the RIGHT side of
    // this comparison is a live clock call (story review, 2026-09-22, correcting this comment's own
    // earlier "both sides" framing) — claimed_at on the left is a stored column value, written once
    // at claim time by claimPendingBatch above, not evaluated here; clock_timestamp() here reads
    // this statement's own true current time to compare that stored value against.
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE main.video_deletion_outbox
        SET status = 'PENDING', claimed_at = NULL, claimed_by = NULL
        WHERE status = 'CLAIMED'
          AND (claimed_at IS NULL OR claimed_at < clock_timestamp() - make_interval(secs => :staleWindowSeconds))
        """, nativeQuery = true)
    int resetStaleClaimed(@Param("staleWindowSeconds") long staleWindowSeconds);

    // skillars-deferred-123 code review 2026-09-18 (Decision 3): hand this run's OWN unprocessed rows
    // straight back to PENDING when the processor self-terminates on its MAX_RUN_DURATION budget.
    // Scoped to this run's own identity, so it can only ever touch rows this invocation claimed —
    // never a concurrent instance's. Every row the loop actually finished has already had claimed_at/
    // claimed_by cleared (COMPLETED and both handleFailure outcomes all null them), so "still CLAIMED
    // with this run's stamp" is exactly the unprocessed remainder. Without this, a bailed-out batch
    // would sit CLAIMED until STALE_CLAIM_WINDOW elapsed (20 minutes) even though the rows are
    // known-good and the very next tick could take them.
    // skillars-deferred-124 AC4: identity predicate moved from claimed_at-exact-equality to
    // claimed_by; claimed_at is still nulled alongside it, keeping both fields' documented invariant
    // (cleared on every transition out of CLAIMED) true for this path.
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE main.video_deletion_outbox
        SET status = 'PENDING', claimed_at = NULL, claimed_by = NULL
        WHERE status = 'CLAIMED' AND claimed_by = :runId
        """, nativeQuery = true)
    int releaseClaimed(@Param("runId") UUID runId);

    // skillars-deferred-123 code review 2026-09-18 (Decision 5): the terminal writes below replace an
    // unconditional `outboxRepository.save(row)`. That save was an em.merge() of a DETACHED entity —
    // process() is not @Transactional and findClaimedBatch is a native query run outside a transaction,
    // so every row handed to processRow is detached — which rewrote EVERY column from a possibly-stale
    // snapshot, with no @Version on this entity and no @DynamicUpdate. If another instance re-claimed
    // the row and committed attempts/backoff in the meantime, the stale merge silently erased it; in
    // the other order it resurrected a COMPLETED row to PENDING with attempts reset to 0, so
    // max_attempts could never be reached and the row would be dispatched forever.
    //
    // The conditional predicate (originally `claimed_at = :claimedAt`, now `claimed_by = :runId` per
    // skillars-deferred-124 AC4 below) makes each write conditional on this invocation STILL OWNING
    // the claim it started with. A losing race affects 0 rows, and the caller skips its side effects
    // entirely rather than rolling them back — the same shape
    // DeletionSchedulerService.markPhysicallyDeleted already uses in this codebase.
    //
    // Only the columns this transition actually owns are written, so nothing else can be clobbered.
    // skillars-deferred-124 AC4: identity predicate moved from claimed_at-exact-equality to
    // claimed_by; claimed_at cleared alongside claimed_by, keeping both fields' invariant true.
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE main.video_deletion_outbox
        SET status = 'COMPLETED', claimed_at = NULL, claimed_by = NULL
        WHERE id = :id AND status = 'CLAIMED' AND claimed_by = :runId
        """, nativeQuery = true)
    int completeClaimed(@Param("id") UUID id, @Param("runId") UUID runId);

    @Modifying
    @Transactional
    @Query(value = """
        UPDATE main.video_deletion_outbox
        SET status = :status, attempts = :attempts, last_error = :lastError,
            next_retry_at = :nextRetryAt, claimed_at = NULL, claimed_by = NULL
        WHERE id = :id AND status = 'CLAIMED' AND claimed_by = :runId
        """, nativeQuery = true)
    int failClaimed(@Param("id") UUID id, @Param("runId") UUID runId,
                    @Param("status") String status, @Param("attempts") int attempts,
                    @Param("lastError") String lastError, @Param("nextRetryAt") Instant nextRetryAt);

    boolean existsByVideoIdAndStatus(UUID videoId, String status);
}
