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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Story Deferred-84 AC1: development.coach_radar_preferences (V51) gained an ON DELETE CASCADE FK to
// main.player_profiles(id) in V117 — the remainder skillars-deferred-77 AC9 (V113) left out of scope
// when it added the equivalent FK to the sibling player_radar_composites / player_radar_baselines
// tables. Verifies the DB-level cascade fires on a direct player_profiles delete (no application code
// path deletes player_profiles today, so this exercises the constraint itself, not a service).
class CoachRadarPreferencePlayerFkIT extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    private static final long PARENT_USER_ID = 9650000001L;
    private static final long PLAYER_ID = 9650000010L;

    // No FK on coach_id, so any UUID satisfies the column — a realistic-looking value for clarity only.
    private static final UUID COACH_ID = UUID.fromString("96500000-0000-0000-0000-000000000001");

    @Test
    void deletingPlayerProfile_cascadesToCoachRadarPreferences() {
        transactionTemplate.execute(status -> {
            insertParentUser();

            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'CRP FK Player', ?, 'MIDFIELDER', 'AGE_10_12', ?, false, ?, 'system')",
                PLAYER_ID, Date.valueOf(LocalDate.now().minusYears(10)),
                PARENT_USER_ID, Timestamp.from(Instant.now()));

            jdbcTemplate.update(
                "INSERT INTO development.coach_radar_preferences " +
                "(coach_id, player_id, selected_skills, updated_at) " +
                "VALUES (?, ?, '{PAC,SHO}', ?)",
                COACH_ID, PLAYER_ID, Timestamp.from(Instant.now()));

            return null;
        });

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM development.coach_radar_preferences WHERE player_id = ?", Integer.class, PLAYER_ID))
            .isEqualTo(1);

        transactionTemplate.execute(status ->
            jdbcTemplate.update("DELETE FROM main.player_profiles WHERE id = ?", PLAYER_ID));

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM development.coach_radar_preferences WHERE player_id = ?", Integer.class, PLAYER_ID))
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
            "crpfk.parent@skillars-test.com", "PARENT",
            "69" + (PARENT_USER_ID % 100000000L),
            "crpfk.parent@skillars-test.com", "x", "PARENT"
        );
    }
}
