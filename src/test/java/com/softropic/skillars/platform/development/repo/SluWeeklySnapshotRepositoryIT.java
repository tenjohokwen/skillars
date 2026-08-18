package com.softropic.skillars.platform.development.repo;

import com.softropic.skillars.config.AbstractIntegrationTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * AC5 (skillars-deferred-31): {@code findByPlayerIdFromWeek} encodes an ISO week-based-year range as
 * two three-term boolean clauses — {@code (isoYear > :fromYear OR (isoYear = :fromYear AND isoWeek >=
 * :fromWeek)) AND (isoYear < :toYear OR (isoYear = :toYear AND isoWeek <= :toWeek))}. Its only two
 * production callers are {@code SluDashboardService} and {@code SluNarrativeService}, and its only
 * test appearances were mocked stubs in {@code SluDashboardServiceTest}, which assert the arguments
 * passed and never execute the query. Nothing had ever run it against Postgres at any date.
 *
 * <p>Each test below makes one half of the predicate load-bearing, and the year-rollover case is the
 * one a naive {@code isoWeek BETWEEN :fromWeek AND :toWeek} would get wrong — which is exactly what
 * the two {@code isoYear >} / {@code isoYear <} disjuncts exist for.
 *
 * <p>{@code player_slu_weekly_snapshot.player_id} has no foreign key (V48), so no {@code main."user"}
 * seed is needed. {@code skill_code} does FK to {@code development.skill_definitions(code)} — the
 * codes below are seeded by V46.
 */
class SluWeeklySnapshotRepositoryIT extends AbstractIntegrationTest {

    // Fixture id range 9630000001-9630000002, claimed in docs/testing/test-data-isolation.md.
    private static final long PLAYER_ID = 9_630_000_001L;
    private static final long OTHER_PLAYER_ID = 9_630_000_002L;

    private static final String SKILL_PACE = "PAC";
    private static final String SKILL_SHOOTING = "SHO";

    @Autowired private SluWeeklySnapshotRepository repository;

    @BeforeEach
    @AfterEach
    void clearFixtures() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "DELETE FROM development.player_slu_weekly_snapshot WHERE player_id IN (?, ?)",
                PLAYER_ID, OTHER_PLAYER_ID);
            return null;
        });
    }

    private void seed(long playerId, String skillCode, int isoYear, int isoWeek, String totalSlu) {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO development.player_slu_weekly_snapshot "
                    + "(player_id, skill_code, iso_year, iso_week, total_slu) VALUES (?, ?, ?, ?, ?)",
                playerId, skillCode, (short) isoYear, (short) isoWeek, new BigDecimal(totalSlu));
            return null;
        });
    }

    /**
     * Both boundaries are inclusive and both exclusions are inside the same year, so this test alone
     * pins the {@code isoWeek >= :fromWeek} and {@code isoWeek <= :toWeek} halves.
     */
    @Test
    void findByPlayerIdFromWeek_sameYearWindow_includesBothBoundariesAndExcludesOutside() {
        seed(PLAYER_ID, SKILL_PACE, 2026, 8, "1.0000");   // before the window
        seed(PLAYER_ID, SKILL_PACE, 2026, 9, "2.0000");   // lower boundary — inclusive
        seed(PLAYER_ID, SKILL_PACE, 2026, 10, "3.0000");  // inside
        seed(PLAYER_ID, SKILL_PACE, 2026, 11, "4.0000");  // upper boundary — inclusive
        seed(PLAYER_ID, SKILL_PACE, 2026, 12, "5.0000");  // after the window

        List<PlayerSluWeeklySnapshot> result = repository.findByPlayerIdFromWeek(
            PLAYER_ID, (short) 2026, (short) 9, (short) 2026, (short) 11);

        assertThat(result)
            .extracting(s -> s.getId().getIsoYear(), s -> s.getId().getIsoWeek())
            .containsExactly(
                tuple((short) 2026, (short) 9),
                tuple((short) 2026, (short) 10),
                tuple((short) 2026, (short) 11));
    }

    /**
     * The case the two {@code isoYear >} / {@code isoYear <} disjuncts exist for. A window of
     * (2025, 52) → (2026, 2) spans the year boundary: weeks 1 and 2 of 2026 are numerically BELOW
     * fromWeek 52, so a naive {@code isoWeek BETWEEN 52 AND 2} returns nothing at all here.
     */
    @Test
    void findByPlayerIdFromWeek_windowSpanningYearRollover_includesWeeksOnBothSides() {
        seed(PLAYER_ID, SKILL_PACE, 2025, 51, "1.0000");  // before the window
        seed(PLAYER_ID, SKILL_PACE, 2025, 52, "2.0000");  // lower boundary — inclusive
        seed(PLAYER_ID, SKILL_PACE, 2026, 1, "3.0000");   // inside, across the rollover
        seed(PLAYER_ID, SKILL_PACE, 2026, 2, "4.0000");   // upper boundary — inclusive
        seed(PLAYER_ID, SKILL_PACE, 2026, 3, "5.0000");   // after the window

        List<PlayerSluWeeklySnapshot> result = repository.findByPlayerIdFromWeek(
            PLAYER_ID, (short) 2025, (short) 52, (short) 2026, (short) 2);

        assertThat(result)
            .extracting(s -> s.getId().getIsoYear(), s -> s.getId().getIsoWeek())
            .containsExactly(
                tuple((short) 2025, (short) 52),
                tuple((short) 2026, (short) 1),
                tuple((short) 2026, (short) 2));
    }

    /**
     * A full intervening year must come back whole: with the window (2024, 50) → (2026, 3), every
     * week of 2025 satisfies both disjuncts on the year term alone and its week number is never
     * compared. Without {@code isoYear > :fromYear} the 2025 rows below fromWeek 50 vanish.
     */
    @Test
    void findByPlayerIdFromWeek_windowSpanningTwoRollovers_returnsTheWholeInterveningYear() {
        seed(PLAYER_ID, SKILL_PACE, 2024, 49, "1.0000");  // before the window
        seed(PLAYER_ID, SKILL_PACE, 2024, 50, "2.0000");  // lower boundary — inclusive
        seed(PLAYER_ID, SKILL_PACE, 2025, 1, "3.0000");   // intervening year, week below fromWeek
        seed(PLAYER_ID, SKILL_PACE, 2025, 52, "4.0000");  // intervening year, week above toWeek
        seed(PLAYER_ID, SKILL_PACE, 2026, 3, "5.0000");   // upper boundary — inclusive
        seed(PLAYER_ID, SKILL_PACE, 2026, 4, "6.0000");   // after the window

        List<PlayerSluWeeklySnapshot> result = repository.findByPlayerIdFromWeek(
            PLAYER_ID, (short) 2024, (short) 50, (short) 2026, (short) 3);

        assertThat(result)
            .extracting(s -> s.getId().getIsoYear(), s -> s.getId().getIsoWeek())
            .containsExactly(
                tuple((short) 2024, (short) 50),
                tuple((short) 2025, (short) 1),
                tuple((short) 2025, (short) 52),
                tuple((short) 2026, (short) 3));
    }

    /** The {@code s.id.playerId = :playerId} predicate was as unproven as the rest of the query. */
    @Test
    void findByPlayerIdFromWeek_excludesAnotherPlayerInsideTheSameWindow() {
        seed(PLAYER_ID, SKILL_PACE, 2026, 10, "1.0000");
        seed(OTHER_PLAYER_ID, SKILL_PACE, 2026, 10, "99.0000");

        List<PlayerSluWeeklySnapshot> result = repository.findByPlayerIdFromWeek(
            PLAYER_ID, (short) 2026, (short) 9, (short) 2026, (short) 11);

        assertThat(result)
            .extracting(s -> s.getId().getPlayerId())
            .containsExactly(PLAYER_ID);
        assertThat(result).singleElement()
            .satisfies(s -> assertThat(s.getTotalSlu()).isEqualByComparingTo("1.0000"));
    }

    /**
     * The ORDER BY is (isoYear ASC, isoWeek ASC) and both callers render a chronological trend from
     * it, so insertion order must not survive into the result. Seeded deliberately backwards, and
     * with two skill codes per week so that ties on (year, week) do not hide a broken year sort.
     */
    @Test
    void findByPlayerIdFromWeek_returnsRowsAscendingByYearThenWeekRegardlessOfInsertOrder() {
        seed(PLAYER_ID, SKILL_SHOOTING, 2026, 2, "1.0000");
        seed(PLAYER_ID, SKILL_PACE, 2026, 1, "2.0000");
        seed(PLAYER_ID, SKILL_SHOOTING, 2025, 52, "3.0000");
        seed(PLAYER_ID, SKILL_PACE, 2025, 52, "4.0000");
        seed(PLAYER_ID, SKILL_PACE, 2026, 2, "5.0000");

        List<PlayerSluWeeklySnapshot> result = repository.findByPlayerIdFromWeek(
            PLAYER_ID, (short) 2025, (short) 52, (short) 2026, (short) 2);

        assertThat(result).hasSize(5);
        assertThat(result)
            .extracting(s -> s.getId().getIsoYear() * 100 + s.getId().getIsoWeek())
            .isSorted();
        assertThat(result)
            .extracting(s -> s.getId().getIsoYear(), s -> s.getId().getIsoWeek())
            .startsWith(tuple((short) 2025, (short) 52), tuple((short) 2025, (short) 52))
            .endsWith(tuple((short) 2026, (short) 2), tuple((short) 2026, (short) 2));
    }
}
