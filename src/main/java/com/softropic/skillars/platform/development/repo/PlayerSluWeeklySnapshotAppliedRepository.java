package com.softropic.skillars.platform.development.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

// IMMUTABLE: append-only — the idempotent write goes through SluWeeklySnapshotRepository's
// upsertAddIdempotent CTE, NOT this repository. The only mutating methods here are the GDPR
// Article 17 erasure path and the skillars-deferred-99 AC9 retention prune below.
public interface PlayerSluWeeklySnapshotAppliedRepository
        extends JpaRepository<PlayerSluWeeklySnapshotApplied,
                              PlayerSluWeeklySnapshotApplied.PlayerSluWeeklySnapshotAppliedId> {

    @Modifying
    @Query("DELETE FROM PlayerSluWeeklySnapshotApplied a WHERE a.id.playerId = :playerId")
    int deleteAllByPlayerId(@Param("playerId") Long playerId);

    /**
     * skillars-deferred-99 AC9: bounded batch delete of markers older than the retention window.
     * The markers only exist to make {@code upsertAddIdempotent} idempotent across a retry window
     * measured in minutes, so anything past the (default 90-day) floor is dead weight. {@code ctid}
     * batch-delete-in-one-statement is the house pattern (migration-conventions.md) — the subselect
     * and delete share one MVCC snapshot, so a concurrent write cannot make it remove an unintended
     * row. Returns the number of rows deleted; caller loops until a short batch.
     */
    @Modifying
    @Query(value = "DELETE FROM development.player_slu_weekly_snapshot_applied "
        + "WHERE ctid IN (SELECT ctid FROM development.player_slu_weekly_snapshot_applied "
        + "WHERE applied_at < :cutoff LIMIT :batchSize)", nativeQuery = true)
    int pruneAppliedBefore(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
