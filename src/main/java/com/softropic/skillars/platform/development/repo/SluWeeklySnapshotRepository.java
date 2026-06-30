package com.softropic.skillars.platform.development.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

public interface SluWeeklySnapshotRepository
        extends JpaRepository<PlayerSluWeeklySnapshot, PlayerSluWeeklySnapshot.PlayerSluSnapshotId> {

    @Modifying
    @Transactional
    @Query(nativeQuery = true, value = """
            INSERT INTO development.player_slu_weekly_snapshot
                (player_id, skill_code, iso_year, iso_week, total_slu)
            VALUES (:playerId, :skillCode, :isoYear, :isoWeek, :sluValue)
            ON CONFLICT (player_id, skill_code, iso_year, iso_week)
            DO UPDATE SET total_slu = player_slu_weekly_snapshot.total_slu + EXCLUDED.total_slu
            """)
    void upsertAdd(@Param("playerId") Long playerId,
                   @Param("skillCode") String skillCode,
                   @Param("isoYear") short isoYear,
                   @Param("isoWeek") short isoWeek,
                   @Param("sluValue") BigDecimal sluValue);

    @Query("SELECT s FROM PlayerSluWeeklySnapshot s WHERE s.id.playerId = :playerId " +
           "AND (s.id.isoYear > :fromYear OR (s.id.isoYear = :fromYear AND s.id.isoWeek >= :fromWeek)) " +
           "ORDER BY s.id.isoYear ASC, s.id.isoWeek ASC")
    List<PlayerSluWeeklySnapshot> findByPlayerIdFromWeek(@Param("playerId") Long playerId,
                                                          @Param("fromYear") short fromYear,
                                                          @Param("fromWeek") short fromWeek);

    @Query("SELECT s FROM PlayerSluWeeklySnapshot s WHERE s.id.playerId = :playerId " +
           "AND s.id.isoYear = :isoYear AND s.id.isoWeek = :isoWeek")
    List<PlayerSluWeeklySnapshot> findByPlayerIdAndWeek(@Param("playerId") Long playerId,
                                                         @Param("isoYear") short isoYear,
                                                         @Param("isoWeek") short isoWeek);

    @Modifying
    @Query("DELETE FROM PlayerSluWeeklySnapshot s WHERE s.id.playerId = :playerId")
    int deleteAllByPlayerId(@Param("playerId") Long playerId);
}
