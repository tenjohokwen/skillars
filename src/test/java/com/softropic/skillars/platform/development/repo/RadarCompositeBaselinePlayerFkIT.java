package com.softropic.skillars.platform.development.repo;

import com.softropic.skillars.config.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

// Story Deferred-77 AC9: development.player_radar_composites/player_radar_baselines both gained an
// ON DELETE CASCADE FK to main.player_profiles(id) in V113. Verifies the DB-level cascade fires on a
// direct player_profiles delete (no application code path deletes player_profiles today, so this
// exercises the constraint itself, not a service).
class RadarCompositeBaselinePlayerFkIT extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    private static final long PARENT_USER_ID = 9577000001L;
    private static final long PLAYER_ID = 9577000010L;

    @Test
    void deletingPlayerProfile_cascadesToRadarCompositesAndBaselines() {
        transactionTemplate.execute(status -> {
            insertParentUser();

            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Radar FK Player', ?, 'MIDFIELDER', 'AGE_10_12', ?, false, ?, 'system')",
                PLAYER_ID, Date.valueOf(LocalDate.now().minusYears(10)),
                PARENT_USER_ID, Timestamp.from(Instant.now()));

            jdbcTemplate.update(
                "INSERT INTO development.player_radar_composites " +
                "(player_id, skill_code, composite_score, entry_count, last_updated_at) " +
                "VALUES (?, 'PAC', 55.00, 3, ?)",
                PLAYER_ID, Timestamp.from(Instant.now()));

            jdbcTemplate.update(
                "INSERT INTO development.player_radar_baselines " +
                "(player_id, skill_code, baseline_score, recorded_at) " +
                "VALUES (?, 'PAC', 40.00, ?)",
                PLAYER_ID, Timestamp.from(Instant.now()));

            return null;
        });

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM development.player_radar_composites WHERE player_id = ?", Integer.class, PLAYER_ID))
            .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM development.player_radar_baselines WHERE player_id = ?", Integer.class, PLAYER_ID))
            .isEqualTo(1);

        transactionTemplate.execute(status ->
            jdbcTemplate.update("DELETE FROM main.player_profiles WHERE id = ?", PLAYER_ID));

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM development.player_radar_composites WHERE player_id = ?", Integer.class, PLAYER_ID))
            .isEqualTo(0);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM development.player_radar_baselines WHERE player_id = ?", Integer.class, PLAYER_ID))
            .isEqualTo(0);

        transactionTemplate.execute(status ->
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id = ?", PARENT_USER_ID));
    }

    private void insertParentUser() {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" " +
            "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, " +
            "status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, " +
            "activated, locked, login, login_id_type, password_hash, otp_enabled, " +
            "skillars_role, verification_status) " +
            "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, " +
            "'ACTIVE', '1990-01-01', ?, 'Test', 'OTHER', 'en', ?, 'DE', ?, " +
            "true, false, ?, 'EMAIL', ?, false, " +
            "?, 'BASIC_VERIFIED')",
            PARENT_USER_ID,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            "radarfk.parent@skillars-test.com", "PARENT",
            "69" + (PARENT_USER_ID % 100000000L),
            "radarfk.parent@skillars-test.com", "x", "PARENT"
        );
    }
}
