package com.softropic.skillars.db;

import com.softropic.skillars.config.AbstractIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Code review (2026-10-08) on skillars-deferred-149 AC5: the stored
 * {@code reviews.minSessionAgeDays}/{@code reviews.updateCooldownDays} pair can legally hold a
 * gap below 7 under the OLD strict-ordering rule (e.g. operator-set {@code 29}/{@code 30} between
 * {@code V156} and {@code V158}) — unnormalised, {@code ConfigStartupAssertion}'s new minimum-gap
 * check fails fast on every non-dev boot with no way to fix it short-circuiting the only write
 * path ({@code PUT /api/config}, which needs the app already running). {@code V158} adds an
 * idempotent two-pass normalisation closing this.
 *
 * <p>This class executes the ACTUAL {@code V158} migration file's SQL against seeded narrow-gap
 * rows — not a hand-copied reproduction of the logic, which could drift from what Flyway really
 * runs — proving the arithmetic is correct, not just that it compiles into a migration.
 * {@code main.platform_config} is a Flyway-seeded reference table that {@code
 * DatabaseResetTestExecutionListener} snapshots/restores per test method (see that class's own
 * javadoc), so mutating it here is safe and self-cleaning.
 */
class ReviewEligibilityGapNormalizationIT extends AbstractIntegrationTest {

    private static final String MIN_AGE_KEY = "reviews.minSessionAgeDays";
    private static final String COOLDOWN_KEY = "reviews.updateCooldownDays";

    private String loadMigrationSql() throws Exception {
        byte[] bytes = new ClassPathResource("db/migration/V158__user_security_session_invalidated_at.sql")
            .getContentAsByteArray();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void seedPair(int minAge, int cooldown) {
        // Bare jdbcTemplate writes never commit (hikari auto-commit=false) -- the same bug
        // AC2 fixed elsewhere in this story. Must go through transactionTemplate.
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("UPDATE main.platform_config SET value = ? WHERE key = ?",
                String.valueOf(minAge), MIN_AGE_KEY);
            jdbcTemplate.update("UPDATE main.platform_config SET value = ? WHERE key = ?",
                String.valueOf(cooldown), COOLDOWN_KEY);
            return null;
        });
    }

    private int readValue(String key) {
        return jdbcTemplate.queryForObject(
            "SELECT value::integer FROM main.platform_config WHERE key = ?", Integer.class, key);
    }

    private void runNormalization() throws Exception {
        String sql = loadMigrationSql();
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute(sql);
            return null;
        });
    }

    @Test
    void narrowGap_minSessionAgeDaysLoweredToRestoreSevenDayGap() throws Exception {
        // 29/30, gap=1 -- legal under the old strict-ordering rule, illegal under the new one.
        seedPair(29, 30);

        runNormalization();

        int minAge = readValue(MIN_AGE_KEY);
        int cooldown = readValue(COOLDOWN_KEY);
        assertThat(cooldown - minAge).isGreaterThanOrEqualTo(7);
        // Prefers lowering minSessionAgeDays over raising the operator's configured cooldown.
        assertThat(cooldown).isEqualTo(30);
        assertThat(minAge).isEqualTo(23);
    }

    @Test
    void narrowGap_cooldownTooSmallForFirstPassAlone_raisesCooldownToo() throws Exception {
        // 3/5, gap=2. Lowering minSessionAgeDays alone can reach at best 1 (cooldown - 7 = -2,
        // floored at 1), giving gap = 5 - 1 = 4, still short of 7 -- the second pass must raise
        // cooldown to close the remainder.
        seedPair(3, 5);

        runNormalization();

        int minAge = readValue(MIN_AGE_KEY);
        int cooldown = readValue(COOLDOWN_KEY);
        assertThat(cooldown - minAge).isGreaterThanOrEqualTo(7);
    }

    @Test
    void existingSafeGap_unchanged() throws Exception {
        // The V156 default (7/30, gap=23) must be left alone -- this is the common case on every
        // fresh database and must stay a true no-op.
        seedPair(7, 30);

        runNormalization();

        assertThat(readValue(MIN_AGE_KEY)).isEqualTo(7);
        assertThat(readValue(COOLDOWN_KEY)).isEqualTo(30);
    }

    @Test
    void idempotent_secondRunChangesNothing() throws Exception {
        seedPair(29, 30);
        runNormalization();
        int minAgeAfterFirst = readValue(MIN_AGE_KEY);
        int cooldownAfterFirst = readValue(COOLDOWN_KEY);

        runNormalization();

        assertThat(readValue(MIN_AGE_KEY)).isEqualTo(minAgeAfterFirst);
        assertThat(readValue(COOLDOWN_KEY)).isEqualTo(cooldownAfterFirst);
    }
}
