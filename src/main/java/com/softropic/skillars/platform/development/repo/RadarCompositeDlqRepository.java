package com.softropic.skillars.platform.development.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface RadarCompositeDlqRepository extends JpaRepository<RadarCompositeDlqEntry, UUID> {

    // Mirrors VideoDeletionOutboxRepository's claim/reset shape (skillars-deferred-77 AC10 Phase 2).
    // skillars-deferred-123 AC3: claimed_at stamped/scoped/keyed identically — see
    // VideoDeletionOutboxRepository's own comments on each of the three queries below for the
    // full rationale (RadarCompositeDlqEntry.claimedAt's Javadoc points back here too).
    // skillars-deferred-124 AC4: claimed_by stamped/keyed identically too — see
    // VideoDeletionOutboxRepository.claimPendingBatch's own comment and
    // RadarCompositeDlqEntry.claimedBy's Javadoc.
    // skillars-deferred-126 AC1: claimed_at's stamp moved from the app-clock :now bind parameter to
    // the database's own now() — matching ShedLockConfig's usingDbTime() choice for the identical
    // cross-instance clock-skew reason. Scoped to this ONE clause only: next_retry_at <= :now (the
    // eligibility predicate, sharing the same :now bind parameter) is deliberately left untouched —
    // next_retry_at is itself app-clock-stamped elsewhere (row insertion, backoff computation), so
    // making eligibility DB-time too would require touching every writer of that column, a much
    // larger change than this fix's scope. Postgres allows a literal now() call and a bound
    // parameter in the same UPDATE statement freely. See resetStaleClaimed's own Javadoc below for
    // the comparison this stamp change was made for.
    // skillars-deferred-128 AC5: now() further replaced with clock_timestamp() — see
    // VideoDeletionOutboxRepository.claimPendingBatch's identical comment for the full
    // transaction_timestamp()-vs.-statement-accurate-time rationale (this claim-stamp write is
    // correct today only because process() carries no @Transactional and ShedLock's own accessor
    // runs REQUIRES_NEW; clock_timestamp() removes that hidden transaction-boundary coupling for
    // THIS timestamp symptom specifically — story review, 2026-09-22, correcting this comment's own
    // earlier "regardless of any future change" overclaim: a future @Transactional process() would
    // still break claim visibility and recalculateComposite's own side-effect atomicity far worse
    // than this one stamp ever could; this fix does not address those). Per-row evaluation is
    // confirmed safe: the
    // batch-identity predicate here is claimed_by (skillars-deferred-124 AC4), not claimed_at.
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE development.radar_composite_dlq
        SET status = 'CLAIMED', claimed_at = clock_timestamp(), claimed_by = :runId
        WHERE id = ANY(
            SELECT id FROM development.radar_composite_dlq
            WHERE status = 'PENDING' AND next_retry_at <= :now
            ORDER BY next_retry_at ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
        )
        """, nativeQuery = true)
    int claimPendingBatch(@Param("now") Instant now, @Param("runId") UUID runId, @Param("batchSize") int batchSize);

    // skillars-deferred-124 AC4: identity predicate moved from claimed_at-exact-equality to
    // claimed_by — see VideoDeletionOutboxRepository.findClaimedBatch's identical comment.
    @Query(value = """
        SELECT * FROM development.radar_composite_dlq
        WHERE status = 'CLAIMED' AND claimed_by = :runId
        ORDER BY next_retry_at ASC
        LIMIT :batchSize
        """, nativeQuery = true)
    List<RadarCompositeDlqEntry> findClaimedBatch(@Param("runId") UUID runId, @Param("batchSize") int batchSize);

    // skillars-deferred-123 code review 2026-09-18 (Patch): matches claimed_at IS NULL too, not just
    // claimed_at IS NOT NULL — see VideoDeletionOutboxRepository.resetStaleClaimed's comment for the
    // full rolling-deploy-stranding rationale. Also clears claimed_at back to NULL on this transition,
    // matching the field's documented invariant.
    // skillars-deferred-124 AC4: WHERE predicate stays keyed on claimed_at (a time comparison, not an
    // identity one), but claimed_by is cleared here too, same rationale as
    // VideoDeletionOutboxRepository.resetStaleClaimed.
    // skillars-deferred-126 AC1: takes the stale-window WIDTH (seconds), not a Java-computed absolute
    // deadline — the deadline is now computed inside the SQL itself via the database's own now(),
    // matching claimPendingBatch's identical DB-time move above and ShedLockConfig's usingDbTime().
    // If instance B's app clock ran ahead of instance A's by more than this window's margin above
    // lockAtMostFor, the old app-clock deadline let B's sweep free and immediately re-claim rows A was
    // still legitimately processing — a duplicate recalculateComposite, an externally-visible side
    // effect claimed_by cannot undo after the fact. make_interval(secs => ...) is used rather than
    // now() - (:staleWindowSeconds * interval '1 second') for clarity and unambiguous parameter
    // typing, NOT to avoid a numeric cast (/bmad-code-review fix, 2026-09-21: the original rationale
    // here was factually wrong — make_interval's own secs parameter is ITSELF declared double
    // precision, so binding a bindable long performs the identical implicit bigint -> double
    // precision cast either way). The real benefit is that a named function argument gives Postgres's
    // parser an unambiguous target type to infer the bound parameter's type from, avoiding a "could
    // not determine data type of parameter" error the bare multiplication form can trigger, and it
    // reads unambiguously as "an interval of N seconds" rather than relying on interval-arithmetic
    // operator precedence.
    // skillars-deferred-128 AC5: now() replaced with clock_timestamp() to match claimPendingBatch's
    // identical stamp change above — see that method's Javadoc for the full rationale.
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE development.radar_composite_dlq
        SET status = 'PENDING', claimed_at = NULL, claimed_by = NULL
        WHERE status = 'CLAIMED'
          AND (claimed_at IS NULL OR claimed_at < clock_timestamp() - make_interval(secs => :staleWindowSeconds))
        """, nativeQuery = true)
    int resetStaleClaimed(@Param("staleWindowSeconds") long staleWindowSeconds);

    // skillars-deferred-123 code review 2026-09-18 (Decision 3): mirrors
    // VideoDeletionOutboxRepository.releaseClaimed — see that method's comment for the full rationale.
    // skillars-deferred-124 AC4: identity predicate moved to claimed_by, same as that method.
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE development.radar_composite_dlq
        SET status = 'PENDING', claimed_at = NULL, claimed_by = NULL
        WHERE status = 'CLAIMED' AND claimed_by = :runId
        """, nativeQuery = true)
    int releaseClaimed(@Param("runId") UUID runId);

    // skillars-deferred-123 code review 2026-09-18 (Decision 5): mirrors
    // VideoDeletionOutboxRepository.completeClaimed/failClaimed — see those methods' comment for the
    // full rationale (detached-entity merge, no @Version on this entity either, lost updates in both
    // directions). Note this class's own header comment claims "@SchedulerLock below closes this by
    // preventing the concurrent invocation in the first place"; that only holds while the lock is
    // held, and lockAtMostFor expiry is the documented end of that guarantee, so these predicates are
    // what actually close it.
    // skillars-deferred-124 AC4: identity predicate moved to claimed_by, same as the video sibling.
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE development.radar_composite_dlq
        SET status = 'COMPLETED', claimed_at = NULL, claimed_by = NULL
        WHERE id = :id AND status = 'CLAIMED' AND claimed_by = :runId
        """, nativeQuery = true)
    int completeClaimed(@Param("id") UUID id, @Param("runId") UUID runId);

    @Modifying
    @Transactional
    @Query(value = """
        UPDATE development.radar_composite_dlq
        SET status = :status, attempts = :attempts, last_error = :lastError,
            next_retry_at = :nextRetryAt, claimed_at = NULL, claimed_by = NULL
        WHERE id = :id AND status = 'CLAIMED' AND claimed_by = :runId
        """, nativeQuery = true)
    int failClaimed(@Param("id") UUID id, @Param("runId") UUID runId,
                    @Param("status") String status, @Param("attempts") int attempts,
                    @Param("lastError") String lastError, @Param("nextRetryAt") Instant nextRetryAt);
}
