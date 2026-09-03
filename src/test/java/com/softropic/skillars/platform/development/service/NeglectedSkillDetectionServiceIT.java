package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.config.AbstractIntegrationTest;

import com.softropic.skillars.platform.development.repo.NeglectedSkillFlag;
import com.softropic.skillars.platform.development.repo.NeglectedSkillFlagRepository;
import com.softropic.skillars.platform.development.repo.SluTargetRepository;
import com.softropic.skillars.platform.development.repo.SluWeeklySnapshotRepository;
import com.softropic.skillars.platform.security.SecurityIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT for AC 7 of Story 5.2 — the neglected-skill detection query must aggregate the
 * HIGHEST weekly_target_slu across ALL coaches for a player/skill, not a single coach's
 * target. A unit test with a pre-baked MAX() stub cannot catch a regression where the
 * repository JPQL accidentally scopes the MAX by a specific coach_id.
 */
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class NeglectedSkillDetectionServiceIT extends AbstractIntegrationTest {

    // skillars-deferred-90 AC8: was a hardcoded literal (9360000001L) reused against the shared
    // SecurityIT.SEC_DATA_SQL_PATH bundle — a per-run id + explicit teardown removes the collision.
    private final long playerId = ThreadLocalRandom.current().nextLong(8_000_000_000L, 8_999_999_999L);
    private static final String SKILL_CODE = "PAC";
    // threshold=0.30 → lowerBound = maxTarget * 0.70
    private static final BigDecimal THRESHOLD = new BigDecimal("0.30");
    // Story Deferred-76 AC9: this IT never seeds development.player_skill_stats rows for playerId
    // (only player_slu_targets and player_slu_weekly_snapshot, written directly via JDBC), so
    // countDistinctSessions(playerId) is 0 here — 0 keeps the new warmup gate a no-op so this IT's
    // actual subject (the MAX-across-coaches query) is unaffected by warmup behavior.
    private static final long WARMUP_SESSION_COUNT = 0L;

    @Autowired private SluTargetRepository sluTargetRepository;
    @Autowired private SluWeeklySnapshotRepository snapshotRepository;
    @Autowired private NeglectedSkillFlagRepository flagRepository;
    @Autowired private NeglectedSkillProcessor processor;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    private short evalYear;
    private short evalWeek;

    @AfterEach
    void cleanupPlayerRows() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM development.neglected_skill_flags WHERE player_id = ?", playerId);
            jdbcTemplate.update("DELETE FROM development.player_slu_weekly_snapshot WHERE player_id = ?", playerId);
            jdbcTemplate.update("DELETE FROM development.player_slu_targets WHERE player_id = ?", playerId);
            return null;
        });
    }


    @Test
    void multipleCoachesHighestTargetGovernsDetection_IT() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        evalYear = (short) now.get(IsoFields.WEEK_BASED_YEAR);
        evalWeek = (short) now.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);

        UUID coach1 = UUID.randomUUID();
        UUID coach2 = UUID.randomUUID();
        // Coach 1's target alone → lowerBound = 10 * 0.70 = 7.0
        // Coach 2's target (the highest) → lowerBound = 20 * 0.70 = 14.0
        seedTarget(coach1, new BigDecimal("10"));
        seedTarget(coach2, new BigDecimal("20"));

        // actual = 5 → below both possible lower bounds → flagged (sanity baseline)
        setSnapshotTotal(new BigDecimal("5"));
        processor.processPlayer(playerId, THRESHOLD, WARMUP_SESSION_COUNT, evalYear, evalWeek);
        assertOpenFlagExists();

        // actual = 10 → ABOVE coach1's lower bound (7.0) but BELOW the highest lower bound (14.0).
        // This is the discriminating case for AC 7: if the JPQL query regressed to scope MAX by a
        // single coach_id (e.g. only coach1), this would incorrectly resolve the flag (10 >= 7.0).
        // With the real MAX-across-coaches query, the flag correctly stays open (10 < 14.0).
        setSnapshotTotal(new BigDecimal("10"));
        processor.processPlayer(playerId, THRESHOLD, WARMUP_SESSION_COUNT, evalYear, evalWeek);
        assertOpenFlagExists();

        // actual = 14 → EXACTLY the highest lower bound. NeglectedSkillProcessor uses a strict
        // `actual.compareTo(lowerBound) < 0` check, so equality is NOT neglected — pins this
        // boundary so a future `<=` regression would be caught.
        setSnapshotTotal(new BigDecimal("14"));
        processor.processPlayer(playerId, THRESHOLD, WARMUP_SESSION_COUNT, evalYear, evalWeek);
        assertNoOpenFlag();

        // actual = 20 → above the highest lower bound (14.0) → resolved
        setSnapshotTotal(new BigDecimal("20"));
        processor.processPlayer(playerId, THRESHOLD, WARMUP_SESSION_COUNT, evalYear, evalWeek);
        assertNoOpenFlag();
    }

    private void seedTarget(UUID coachId, BigDecimal target) {
        sluTargetRepository.upsert(coachId, playerId, SKILL_CODE, target, Instant.now());
    }

    private void setSnapshotTotal(BigDecimal total) {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO development.player_slu_weekly_snapshot " +
                "(player_id, skill_code, iso_year, iso_week, total_slu) " +
                "VALUES (?, ?, ?, ?, ?) " +
                "ON CONFLICT (player_id, skill_code, iso_year, iso_week) " +
                "DO UPDATE SET total_slu = EXCLUDED.total_slu",
                playerId, SKILL_CODE, evalYear, evalWeek, total
            );
            return null;
        });
    }

    private void assertOpenFlagExists() {
        List<NeglectedSkillFlag> flags = flagRepository.findByPlayerIdAndResolvedAtIsNull(playerId);
        assertThat(flags).anyMatch(f -> SKILL_CODE.equals(f.getSkillCode()));
    }

    private void assertNoOpenFlag() {
        List<NeglectedSkillFlag> flags = flagRepository.findByPlayerIdAndResolvedAtIsNull(playerId);
        assertThat(flags).noneMatch(f -> SKILL_CODE.equals(f.getSkillCode()));
    }
}
