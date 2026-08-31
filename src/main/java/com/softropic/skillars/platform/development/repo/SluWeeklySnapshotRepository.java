package com.softropic.skillars.platform.development.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface SluWeeklySnapshotRepository
        extends JpaRepository<PlayerSluWeeklySnapshot, PlayerSluWeeklySnapshot.PlayerSluSnapshotId> {

    /**
     * Idempotent additive upsert keyed on {@code (sessionId, playerId, skillCode, isoYear, isoWeek)}.
     *
     * <p>One statement, one snapshot: the CTE inserts a marker row into
     * {@code player_slu_weekly_snapshot_applied} ({@code ON CONFLICT DO NOTHING RETURNING 1}), and the
     * main additive upsert into {@code player_slu_weekly_snapshot} runs only
     * {@code WHERE EXISTS (SELECT 1 FROM ins)} — i.e. only when the marker was newly inserted. Both
     * writes commit or roll back together inside {@link SnapshotBatchWriter#writeAll}'s
     * {@code @Transactional} boundary.
     *
     * <ul>
     *   <li><b>Genuine failure + retry:</b> nothing from the failed attempt persisted → {@code ins}
     *       returns a row → the delta is applied exactly once.</li>
     *   <li><b>Ambiguous commit + retry:</b> the (actually-committed) first attempt's marker is
     *       present → {@code ON CONFLICT DO NOTHING} yields no {@code ins} row → the snapshot upsert
     *       matches zero rows → no-op. This is the skillars-deferred-86 AC1 fix.</li>
     *   <li><b>Two distinct sessions, same weekly bucket:</b> each inserts its own marker (different
     *       {@code session_id}) → both deltas apply → the weekly total is their sum, unchanged from
     *       the pre-story behaviour.</li>
     * </ul>
     */
    @Modifying
    @Transactional
    @Query(nativeQuery = true, value = """
            WITH ins AS (
                INSERT INTO development.player_slu_weekly_snapshot_applied
                    (session_id, player_id, skill_code, iso_year, iso_week)
                VALUES (:sessionId, :playerId, :skillCode, :isoYear, :isoWeek)
                ON CONFLICT DO NOTHING
                RETURNING 1
            )
            INSERT INTO development.player_slu_weekly_snapshot
                (player_id, skill_code, iso_year, iso_week, total_slu)
            SELECT :playerId, :skillCode, :isoYear, :isoWeek, :sluValue
            WHERE EXISTS (SELECT 1 FROM ins)
            ON CONFLICT (player_id, skill_code, iso_year, iso_week)
            DO UPDATE SET total_slu = player_slu_weekly_snapshot.total_slu + EXCLUDED.total_slu
            """)
    void upsertAddIdempotent(@Param("sessionId") UUID sessionId,
                             @Param("playerId") Long playerId,
                             @Param("skillCode") String skillCode,
                             @Param("isoYear") short isoYear,
                             @Param("isoWeek") short isoWeek,
                             @Param("sluValue") BigDecimal sluValue);

    @Query("SELECT s FROM PlayerSluWeeklySnapshot s WHERE s.id.playerId = :playerId " +
           "AND (s.id.isoYear > :fromYear OR (s.id.isoYear = :fromYear AND s.id.isoWeek >= :fromWeek)) " +
           "AND (s.id.isoYear < :toYear OR (s.id.isoYear = :toYear AND s.id.isoWeek <= :toWeek)) " +
           "ORDER BY s.id.isoYear ASC, s.id.isoWeek ASC")
    List<PlayerSluWeeklySnapshot> findByPlayerIdFromWeek(@Param("playerId") Long playerId,
                                                          @Param("fromYear") short fromYear,
                                                          @Param("fromWeek") short fromWeek,
                                                          @Param("toYear") short toYear,
                                                          @Param("toWeek") short toWeek);

    @Query("SELECT s FROM PlayerSluWeeklySnapshot s WHERE s.id.playerId = :playerId " +
           "AND s.id.isoYear = :isoYear AND s.id.isoWeek = :isoWeek")
    List<PlayerSluWeeklySnapshot> findByPlayerIdAndWeek(@Param("playerId") Long playerId,
                                                         @Param("isoYear") short isoYear,
                                                         @Param("isoWeek") short isoWeek);

    @Modifying
    @Query("DELETE FROM PlayerSluWeeklySnapshot s WHERE s.id.playerId = :playerId")
    int deleteAllByPlayerId(@Param("playerId") Long playerId);
}
