package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.config.AbstractIntegrationTest;

import com.softropic.skillars.platform.development.repo.NeglectedSkillFlag;
import com.softropic.skillars.platform.development.repo.NeglectedSkillFlagRepository;
import com.softropic.skillars.platform.development.repo.SluTargetRepository;
import com.softropic.skillars.platform.development.repo.SluWeeklySnapshotRepository;
import com.softropic.skillars.platform.security.SecurityIT;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT for AC 7 of Story 5.2 — the neglected-skill detection query must aggregate the
 * HIGHEST weekly_target_slu across ALL coaches for a player/skill, not a single coach's
 * target. A unit test with a pre-baked MAX() stub cannot catch a regression where the
 * repository JPQL accidentally scopes the MAX by a specific coach_id.
 */
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class NeglectedSkillDetectionServiceIT extends AbstractIntegrationTest {

    private static final long PLAYER_ID = 9360000001L;
    private static final String SKILL_CODE = "PAC";
    // threshold=0.30 → lowerBound = maxTarget * 0.70
    private static final BigDecimal THRESHOLD = new BigDecimal("0.30");

    @Autowired private SluTargetRepository sluTargetRepository;
    @Autowired private SluWeeklySnapshotRepository snapshotRepository;
    @Autowired private NeglectedSkillFlagRepository flagRepository;
    @Autowired private NeglectedSkillProcessor processor;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    private short evalYear;
    private short evalWeek;


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
        processor.processPlayer(PLAYER_ID, THRESHOLD, evalYear, evalWeek);
        assertOpenFlagExists();

        // actual = 10 → ABOVE coach1's lower bound (7.0) but BELOW the highest lower bound (14.0).
        // This is the discriminating case for AC 7: if the JPQL query regressed to scope MAX by a
        // single coach_id (e.g. only coach1), this would incorrectly resolve the flag (10 >= 7.0).
        // With the real MAX-across-coaches query, the flag correctly stays open (10 < 14.0).
        setSnapshotTotal(new BigDecimal("10"));
        processor.processPlayer(PLAYER_ID, THRESHOLD, evalYear, evalWeek);
        assertOpenFlagExists();

        // actual = 14 → EXACTLY the highest lower bound. NeglectedSkillProcessor uses a strict
        // `actual.compareTo(lowerBound) < 0` check, so equality is NOT neglected — pins this
        // boundary so a future `<=` regression would be caught.
        setSnapshotTotal(new BigDecimal("14"));
        processor.processPlayer(PLAYER_ID, THRESHOLD, evalYear, evalWeek);
        assertNoOpenFlag();

        // actual = 20 → above the highest lower bound (14.0) → resolved
        setSnapshotTotal(new BigDecimal("20"));
        processor.processPlayer(PLAYER_ID, THRESHOLD, evalYear, evalWeek);
        assertNoOpenFlag();
    }

    private void seedTarget(UUID coachId, BigDecimal target) {
        sluTargetRepository.upsert(coachId, PLAYER_ID, SKILL_CODE, target, Instant.now());
    }

    private void setSnapshotTotal(BigDecimal total) {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO development.player_slu_weekly_snapshot " +
                "(player_id, skill_code, iso_year, iso_week, total_slu) " +
                "VALUES (?, ?, ?, ?, ?) " +
                "ON CONFLICT (player_id, skill_code, iso_year, iso_week) " +
                "DO UPDATE SET total_slu = EXCLUDED.total_slu",
                PLAYER_ID, SKILL_CODE, evalYear, evalWeek, total
            );
            return null;
        });
    }

    private void assertOpenFlagExists() {
        List<NeglectedSkillFlag> flags = flagRepository.findByPlayerIdAndResolvedAtIsNull(PLAYER_ID);
        assertThat(flags).anyMatch(f -> SKILL_CODE.equals(f.getSkillCode()));
    }

    private void assertNoOpenFlag() {
        List<NeglectedSkillFlag> flags = flagRepository.findByPlayerIdAndResolvedAtIsNull(PLAYER_ID);
        assertThat(flags).noneMatch(f -> SKILL_CODE.equals(f.getSkillCode()));
    }
}
