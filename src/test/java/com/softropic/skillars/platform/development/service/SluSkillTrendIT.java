package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.infrastructure.util.TestClockProvider;
import com.softropic.skillars.platform.development.contract.SkillTrendDirection;
import com.softropic.skillars.platform.development.contract.SkillTrendResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * skillars-deferred-98 AC1b — end-to-end proof of {@link SluDashboardService#getSkillTrends} over
 * real {@code development.player_slu_weekly_snapshot} rows read back through
 * {@code findByPlayerIdFromWeek} against Postgres. The service unit test mocks the repository; this
 * one seeds a fixed four-week fixture per skill and asserts all three classifications come back.
 *
 * <p>The clock is pinned to 2027-01-06 (ISO 2027-W01) so the 12-week window
 * (2026-W43 .. 2027-W01) covers the seeded weeks 2026-W49..W51 and 2027-W01 deterministically.
 *
 * <p>{@code player_slu_weekly_snapshot.player_id} has no FK (V48); {@code skill_code} FKs to
 * {@code development.skill_definitions(code)} — PAC/SHO/DRI are seeded by V46. Fixture id range
 * {@code 9652000001}-{@code 9652000009}, claimed in docs/testing/test-data-isolation.md.
 */
class SluSkillTrendIT extends AbstractIntegrationTest {

    private static final long PLAYER_ID = 9_652_000_001L;
    private static final long OTHER_PLAYER_ID = 9_652_000_002L;

    @Autowired private SluDashboardService sluDashboardService;

    @BeforeEach
    @AfterEach
    void clearFixtures() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "DELETE FROM development.player_slu_weekly_snapshot WHERE player_id IN (?, ?)",
                PLAYER_ID, OTHER_PLAYER_ID);
            return null;
        });
        TestClockProvider.unsetClock();
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

    @Test
    void getSkillTrends_overRealWeeklyRows_classifiesImprovingDecliningAndFlatPerSkill() {
        TestClockProvider.setClock(Clock.fixed(Instant.parse("2027-01-06T10:00:00Z"), ZoneOffset.UTC));

        int[][] weeks = {{2026, 49}, {2026, 50}, {2026, 51}, {2027, 1}};
        String[] rising = {"2.0000", "4.0000", "6.0000", "8.0000"};
        String[] falling = {"8.0000", "6.0000", "4.0000", "2.0000"};
        String[] flat = {"5.0000", "5.0000", "5.0000", "5.0000"};
        for (int i = 0; i < weeks.length; i++) {
            seed(PLAYER_ID, "PAC", weeks[i][0], weeks[i][1], rising[i]);
            seed(PLAYER_ID, "SHO", weeks[i][0], weeks[i][1], falling[i]);
            seed(PLAYER_ID, "DRI", weeks[i][0], weeks[i][1], flat[i]);
        }
        // Another player's rows inside the same window must not bleed into this player's trends.
        seed(OTHER_PLAYER_ID, "PAC", 2026, 50, "99.0000");

        SkillTrendResponse response = sluDashboardService.getSkillTrends(PLAYER_ID, 12);

        assertThat(response.trends())
            .extracting("skillCode", "direction", "weeksObserved")
            .containsExactly(
                tuple("DRI", SkillTrendDirection.FLAT, 4),
                tuple("PAC", SkillTrendDirection.IMPROVING, 4),
                tuple("SHO", SkillTrendDirection.DECLINING, 4));
    }

    @Test
    void getSkillTrends_withOnlyTwoWeeks_reportsInsufficientData() {
        TestClockProvider.setClock(Clock.fixed(Instant.parse("2027-01-06T10:00:00Z"), ZoneOffset.UTC));
        seed(PLAYER_ID, "PAC", 2026, 51, "3.0000");
        seed(PLAYER_ID, "PAC", 2027, 1, "9.0000");

        SkillTrendResponse response = sluDashboardService.getSkillTrends(PLAYER_ID, 12);

        assertThat(response.trends()).singleElement()
            .satisfies(t -> {
                assertThat(t.skillCode()).isEqualTo("PAC");
                assertThat(t.direction()).isEqualTo(SkillTrendDirection.INSUFFICIENT_DATA);
                assertThat(t.weeksObserved()).isEqualTo(2);
            });
    }
}
