package com.softropic.skillars.platform.marketplace.repo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface CoachReliabilityStrikeRepository extends JpaRepository<CoachReliabilityStrike, UUID> {

    @Query("SELECT s.coachId, COUNT(s) FROM CoachReliabilityStrike s " +
           "WHERE s.coachId IN :coachIds AND s.createdAt > :since " +
           "GROUP BY s.coachId")
    List<Object[]> countByCoachIdInAndCreatedAtAfter(
        @Param("coachIds") List<UUID> coachIds,
        @Param("since") OffsetDateTime since
    );

    long countByCoachIdAndCreatedAtAfter(UUID coachId, OffsetDateTime since);

    List<CoachReliabilityStrike> findByCoachIdOrderByCreatedAtDesc(UUID coachId);

    Page<CoachReliabilityStrike> findByCoachId(UUID coachId, Pageable pageable);

    // skillars-deferred-122 AC2: bulk @Modifying DELETE, not the entity-based deleteById this
    // replaces. CoachReliabilityStrike has no @Version, but Hibernate's entity-delete still throws
    // an unconditional post-delete row-count check (StaleStateException) when 0 rows are affected —
    // a genuine 500 for the loser of a concurrent duplicate deleteStrike call, since the initial
    // findById existence/ownership check above takes no lock. This bulk DELETE returns the affected
    // row count instead, letting the caller translate 0 into the same 404
    // (ResourceNotFoundException) the "never existed" case already produces. Mirrors the
    // LoginAttemptRepository bulk-delete precedent (skillars-deferred-119/-120).
    //
    // Code review 2026-09-18: clearAutomatically = true. This bulk delete bypasses the persistence
    // context, leaving the already-loaded `strike` entity in AdminCoachEnforcementService.deleteStrike
    // managed-but-stale — currently safe only because that caller captures strike.getCreatedAt() into
    // a local before calling this method and a comment there forbids re-reading it. Clearing the
    // context after this @Modifying query enforces that structurally instead of by convention: any
    // future re-read of a stale managed entity from this same transaction now re-fetches from the DB.
    //
    // skillars-deferred-123 AC7 (accepted risk, documented not fixed — see deferred-work.md
    // [DECIDED: accepted risk — skillars-deferred-123]): this DELETE carries no NOWAIT/lock-timeout.
    // If two admins call deleteStrike for the *same* strike concurrently, the second DELETE blocks on
    // Postgres's ordinary row lock until the first transaction commits or rolls back, then affects 0
    // rows (translated to a clean 404 by the AC2 fix above). This wait sits outside
    // AdminCoachEnforcementService.deleteStrike's lockRetryer.withBoundedRetry (:293/its own
    // Timer.start) — the DELETE runs immediately at :285, eight lines before that call — so it is not
    // measured by the persistence.lock_retry metric. Accepted because the wait is naturally bounded by
    // the winning transaction's own work, which is itself lock-retry-bounded (PessimisticLockRetryer's
    // ~3.2s worst-case budget for the coach-row lock inside that same winning transaction) — this
    // codebase's current call shape cannot make this wait open-ended. This would need revisiting if a
    // future change to deleteStrike or its callees makes the winning transaction's own work unbounded
    // (e.g. an external network call added inside that transaction).
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM CoachReliabilityStrike s WHERE s.id = :id AND s.coachId = :coachId")
    int deleteByIdAndCoachId(@Param("id") UUID id, @Param("coachId") UUID coachId);
}
