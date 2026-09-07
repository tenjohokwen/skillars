package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.security.SecurityIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-99 AC9 — {@link SluSnapshotAppliedRetentionService} deletes markers older than
 * the retention window and leaves recent ones (which is what keeps {@code upsertAddIdempotent}
 * idempotent for a live session) untouched.
 */
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class SluSnapshotAppliedRetentionServiceIT extends AbstractIntegrationTest {

	private static final long PARENT_USER_ID = 9285_000_001L;
	private static final long PLAYER_ID = 9285_000_002L;
	private static final short ISO_YEAR = 2026;
	private static final short ISO_WEEK = 20;

	@Autowired private SluSnapshotAppliedRetentionService retentionService;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private TransactionTemplate transactionTemplate;

	private final UUID recentSession = UUID.randomUUID();

	@BeforeEach
	void seed() {
		transactionTemplate.execute(s -> {
			jdbcTemplate.update("DELETE FROM development.player_slu_weekly_snapshot_applied WHERE player_id = ?", PLAYER_ID);
			jdbcTemplate.update(
				"INSERT INTO main.authority (id, name, status, created_by, created_date) "
					+ "VALUES (9285, 'ROLE_PARENT', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
				Timestamp.from(Instant.now()));
			jdbcTemplate.update(
				"INSERT INTO main.\"user\" (id, created_by, created_date, last_modified_by, "
					+ "last_modified_date, request_id, session_id, status, dob, email, first_name, gender, "
					+ "lang_key, last_name, iso2_country, phone, activated, locked, login, login_id_type, "
					+ "password_hash, otp_enabled, skillars_role, verification_status) VALUES "
					+ "(?, 'system', ?, 'system', ?, 'test-req', NULL, 'ACTIVE', '1985-06-01', ?, 'Test', "
					+ "'OTHER', 'en', 'Parent', 'DE', ?, true, false, ?, 'EMAIL', 'hash', false, 'PARENT', "
					+ "'BASIC_VERIFIED') ON CONFLICT (id) DO NOTHING",
				PARENT_USER_ID, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
				"slu-retention.parent9285@skillars-test.com", "6709285001",
				"slu-retention.parent9285@skillars-test.com");
			jdbcTemplate.update(
				"INSERT INTO main.player_profiles (id, name, date_of_birth, position, age_tier, "
					+ "parent_id, independent_account_allowed, created_at, created_by) VALUES "
					+ "(?, 'Retention Player', ?, 'MIDFIELDER', 'AGE_10_12', ?, false, ?, 'system') "
					+ "ON CONFLICT (id) DO NOTHING",
				PLAYER_ID, Date.valueOf(LocalDate.now().minusYears(11)), PARENT_USER_ID,
				Timestamp.from(Instant.now()));

			// 3 stale markers (100 days old) + 1 recent (1 day old), all skill_code PAC (V46 seed).
			insertMarker(UUID.randomUUID(), Instant.now().minus(100, ChronoUnit.DAYS));
			insertMarker(UUID.randomUUID(), Instant.now().minus(100, ChronoUnit.DAYS));
			insertMarker(UUID.randomUUID(), Instant.now().minus(100, ChronoUnit.DAYS));
			insertMarker(recentSession, Instant.now().minus(1, ChronoUnit.DAYS));
			return null;
		});
	}

	@AfterEach
	void cleanup() {
		transactionTemplate.execute(s -> {
			jdbcTemplate.update("DELETE FROM development.player_slu_weekly_snapshot_applied WHERE player_id = ?", PLAYER_ID);
			jdbcTemplate.update("DELETE FROM main.player_profiles WHERE id = ?", PLAYER_ID);
			jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id = ?", PARENT_USER_ID);
			return null;
		});
	}

	private void insertMarker(UUID sessionId, Instant appliedAt) {
		jdbcTemplate.update(
			"INSERT INTO development.player_slu_weekly_snapshot_applied "
				+ "(session_id, player_id, skill_code, iso_year, iso_week, applied_at) "
				+ "VALUES (?, ?, 'PAC', ?, ?, ?)",
			sessionId, PLAYER_ID, ISO_YEAR, ISO_WEEK, Timestamp.from(appliedAt));
	}

	private int markerCount() {
		return jdbcTemplate.queryForObject(
			"SELECT count(*) FROM development.player_slu_weekly_snapshot_applied WHERE player_id = ?",
			Integer.class, PLAYER_ID);
	}

	@Test
	void prune_removesOnlyMarkersOlderThanRetention() {
		assertThat(markerCount()).isEqualTo(4);

		int deleted = retentionService.pruneOlderThanRetention();

		assertThat(deleted).as("the 3 markers older than the 90-day default window").isEqualTo(3);
		assertThat(markerCount()).as("the 1-day-old marker is kept").isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject(
			"SELECT count(*) FROM development.player_slu_weekly_snapshot_applied WHERE session_id = ?",
			Integer.class, recentSession))
			.as("idempotency marker for a live session survives the prune")
			.isEqualTo(1);
	}

	@Test
	void prune_isANoOp_whenNothingIsStale() {
		transactionTemplate.execute(s -> {
			jdbcTemplate.update(
				"UPDATE development.player_slu_weekly_snapshot_applied SET applied_at = ? WHERE player_id = ?",
				Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS)), PLAYER_ID);
			return null;
		});

		assertThat(retentionService.pruneOlderThanRetention()).isZero();
		assertThat(markerCount()).isEqualTo(4);
	}
}
