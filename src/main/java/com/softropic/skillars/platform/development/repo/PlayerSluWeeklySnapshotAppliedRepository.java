package com.softropic.skillars.platform.development.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// IMMUTABLE: append-only — the idempotent write goes through SluWeeklySnapshotRepository's
// upsertAddIdempotent CTE, NOT this repository. The only mutating method here is the GDPR Article 17
// erasure path (mirrors SluWeeklySnapshotRepository.deleteAllByPlayerId).
public interface PlayerSluWeeklySnapshotAppliedRepository
        extends JpaRepository<PlayerSluWeeklySnapshotApplied,
                              PlayerSluWeeklySnapshotApplied.PlayerSluWeeklySnapshotAppliedId> {

    @Modifying
    @Query("DELETE FROM PlayerSluWeeklySnapshotApplied a WHERE a.id.playerId = :playerId")
    int deleteAllByPlayerId(@Param("playerId") Long playerId);
}
