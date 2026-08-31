package com.softropic.skillars.platform.development.repo;

import com.softropic.skillars.config.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
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

// Story skillars-deferred-86 AC4: development.coach_radar_preferences.coach_id (V51) gained an
// ON DELETE CASCADE FK to marketplace.coach_profiles(id) in V118 — the half V117 (deferred-84 AC1)
// explicitly left "for a future pass on its own merits" when it added the sibling player_id FK.
// Verifies the DB-level cascade fires on a direct coach_profiles delete (no application code path
// deletes coach_profiles today, so this exercises the constraint itself, not a service).
//
// M4 (senior-dev review): coach_profiles is a parent to many tables (coach_specialties,
// coach_age_groups, coach_pricing, coach_availability_windows, coach_subscriptions,
// player_skill_stats.coach_id, bookings, reviews, player_slu_targets.coach_id, ...) and any of
// those FKs that is NOT ON DELETE CASCADE would make a raw DELETE FROM marketplace.coach_profiles
// fail on an unrelated constraint. So this test seeds a DEDICATED, otherwise-unreferenced coach
// (one user + one coach_profiles row + one coach_radar_preferences row and nothing else) — the
// isolated fixture is deliberate so the test does not silently break when a future coach child
// table is added.
class CoachRadarPreferencesCoachFkIT extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    private static final long PARENT_USER_ID = 9660000001L;
    private static final long COACH_USER_ID  = 9660000002L;
    private static final long PLAYER_ID      = 9660000010L;
    private static final UUID COACH_ID       = UUID.fromString("96600000-0000-0000-0000-000000000001");

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            // coach_radar_preferences rows for COACH_ID / PLAYER_ID are gone via the CASCADE under
            // test, but delete defensively in case an assertion failed before the DELETE ran.
            jdbcTemplate.update("DELETE FROM development.coach_radar_preferences WHERE coach_id = ?", COACH_ID);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id = ?", COACH_ID);
            jdbcTemplate.update("DELETE FROM main.player_profiles WHERE id = ?", PLAYER_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?)", COACH_USER_ID, PARENT_USER_ID);
            return null;
        });
    }

    @Test
    void deletingCoachProfile_cascadesToCoachRadarPreferences() {
        transactionTemplate.execute(status -> {
            insertUser(PARENT_USER_ID, "PARENT", "d86crpfk.parent@skillars-test.com");
            insertUser(COACH_USER_ID, "COACH", "d86crpfk.coach@skillars-test.com");

            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles (id, user_id, display_name, canonical_timezone, status) " +
                "VALUES (?, ?, 'D86 CRP FK Coach', 'Europe/Berlin', 'ACTIVE')",
                COACH_ID, COACH_USER_ID);

            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'D86 CRP FK Player', ?, 'MIDFIELDER', 'AGE_10_12', ?, false, ?, 'system')",
                PLAYER_ID, Date.valueOf(LocalDate.now().minusYears(10)),
                PARENT_USER_ID, Timestamp.from(Instant.now()));

            jdbcTemplate.update(
                "INSERT INTO development.coach_radar_preferences (coach_id, player_id, selected_skills, updated_at) " +
                "VALUES (?, ?, '{PAC,SHO}', ?)",
                COACH_ID, PLAYER_ID, Timestamp.from(Instant.now()));

            return null;
        });

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM development.coach_radar_preferences WHERE coach_id = ?", Integer.class, COACH_ID))
            .isEqualTo(1);

        transactionTemplate.execute(status ->
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id = ?", COACH_ID));

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM development.coach_radar_preferences WHERE coach_id = ?", Integer.class, COACH_ID))
            .isEqualTo(0);
    }

    private void insertUser(long id, String role, String email) {
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
            id,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            email, role,
            "66" + (id % 100000000L),
            email, "x", role
        );
    }
}
