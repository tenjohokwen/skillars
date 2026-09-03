package com.softropic.skillars.platform.outbox;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.development.service.SluSnapshotOutboxSupport;
import com.softropic.skillars.platform.development.repo.PlayerSkillStat;
import com.softropic.skillars.platform.outbox.repo.OutboxMessage;
import com.softropic.skillars.platform.outbox.repo.OutboxMessageRepository;
import com.softropic.skillars.platform.outbox.service.OutboxService;
import com.softropic.skillars.platform.security.SecurityIT;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-91 AC1 + AC4 — a real-database round-trip through the generic outbox
 * ({@code main.outbox_messages}) via the {@code SLU_SNAPSHOT_WRITE} handler:
 *
 * <ul>
 *   <li>producer enqueue → {@code drain()} → the weekly snapshot delta is applied and the row is
 *       removed on success (AC1);</li>
 *   <li>a row whose {@code aggregate_type} has no handler is kept with {@code attempts++} /
 *       {@code last_error}, never dropped (AC1);</li>
 *   <li>re-driving the same {@code SLU_SNAPSHOT_WRITE} payload is a no-op through the {@code V119}
 *       marker — {@code total_slu} does not double (AC4, no over-report).</li>
 * </ul>
 */
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class SluSnapshotOutboxIT extends AbstractIntegrationTest {

    private static final long PARENT_USER_ID = 9280_000_001L;
    private static final long PLAYER_ID      = 9280_000_002L;
    private static final short ISO_YEAR = 2026;
    private static final short ISO_WEEK = 35;

    @Autowired private OutboxService outboxService;
    @Autowired private OutboxMessageRepository outboxRepository;
    @Autowired private SluSnapshotOutboxSupport sluSnapshotOutboxSupport;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    private final UUID sessionId = UUID.randomUUID();
    private final UUID coachId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        transactionTemplate.execute(status -> {
            // skillars-deferred-91 code review: scoped to this test's own session id. An unscoped
            // truncate of main.outbox_messages would delete rows other ITs in this shared context
            // had just enqueued, and drain() would run their messages through real handlers.
            jdbcTemplate.update(
                "DELETE FROM main.outbox_messages WHERE aggregate_type = ? "
                    + "AND payload::text LIKE ?",
                SluSnapshotOutboxSupport.AGGREGATE_TYPE, "%" + sessionId + "%");
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) "
                + "VALUES (9280, 'ROLE_PARENT', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));
            jdbcTemplate.update(
                "INSERT INTO main.\"user\" (id, created_by, created_date, last_modified_by, "
                + "last_modified_date, request_id, session_id, status, dob, email, first_name, gender, "
                + "lang_key, last_name, iso2_country, phone, activated, locked, login, login_id_type, "
                + "password_hash, otp_enabled, skillars_role, verification_status) VALUES "
                + "(?, 'system', ?, 'system', ?, 'test-req', NULL, 'ACTIVE', '1985-06-01', ?, 'Test', "
                + "'OTHER', 'en', 'Parent', 'DE', ?, true, false, ?, 'EMAIL', 'hash', false, 'PARENT', "
                + "'BASIC_VERIFIED')",
                PARENT_USER_ID, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
                "outbox.parent9280@skillars-test.com", "6709280001", "outbox.parent9280@skillars-test.com");
            jdbcTemplate.update(
                "INSERT INTO main.player_profiles (id, name, date_of_birth, position, age_tier, "
                + "parent_id, independent_account_allowed, created_at, created_by) VALUES "
                + "(?, 'Outbox Player', ?, 'MIDFIELDER', 'AGE_10_12', ?, false, ?, 'system')",
                PLAYER_ID, Date.valueOf(LocalDate.now().minusYears(11)), PARENT_USER_ID,
                Timestamp.from(Instant.now()));
            // Two immutable SLU detail rows for one session/bucket: PAC=10.00, SHO=7.50.
            insertStat("PAC", "10.0000");
            insertStat("SHO", "7.5000");
            return null;
        });
    }

    private void insertStat(String skillCode, String sluValue) {
        jdbcTemplate.update(
            "INSERT INTO development.player_skill_stats (id, player_id, session_id, coach_id, "
            + "skill_code, slu_value, calculated_at) VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, NOW())",
            PLAYER_ID, sessionId, coachId, skillCode, new BigDecimal(sluValue));
    }

    private List<SluSnapshotOutboxSupport.SluDeltaDto> deltasFromStats() {
        return jdbcTemplate.query(
            "SELECT session_id, player_id, skill_code, slu_value FROM development.player_skill_stats "
            + "WHERE session_id = ?",
            (rs, i) -> new SluSnapshotOutboxSupport.SluDeltaDto(
                UUID.fromString(rs.getString("session_id")), rs.getLong("player_id"),
                rs.getString("skill_code"), rs.getBigDecimal("slu_value")),
            sessionId);
    }

    private long enqueue(List<SluSnapshotOutboxSupport.SluDeltaDto> deltas) throws Exception {
        var payload = new SluSnapshotOutboxSupport.SluSnapshotWritePayload(ISO_YEAR, ISO_WEEK, deltas);
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
        return transactionTemplate.execute(s -> {
            OutboxMessage m = outboxRepository.save(new OutboxMessage(SluSnapshotOutboxSupport.AGGREGATE_TYPE, json));
            return m.getId();
        });
    }

    private BigDecimal snapshotTotal(String skillCode) {
        return jdbcTemplate.queryForObject(
            "SELECT total_slu FROM development.player_slu_weekly_snapshot WHERE player_id = ? AND "
            + "skill_code = ? AND iso_year = ? AND iso_week = ?",
            BigDecimal.class, PLAYER_ID, skillCode, ISO_YEAR, ISO_WEEK);
    }

    private int snapshotRowCount() {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM development.player_slu_weekly_snapshot WHERE player_id = ? AND "
            + "iso_year = ? AND iso_week = ?",
            Integer.class, PLAYER_ID, ISO_YEAR, ISO_WEEK);
    }

    private int markerCount() {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM development.player_slu_weekly_snapshot_applied WHERE session_id = ?",
            Integer.class, sessionId);
    }

    /** Outbox rows belonging to THIS test's session — never a global count. */
    private long myOutboxRowCount() {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.outbox_messages WHERE aggregate_type = ? AND payload::text LIKE ?",
            Long.class, SluSnapshotOutboxSupport.AGGREGATE_TYPE, "%" + sessionId + "%");
    }

    @Test
    void enqueueThenDrain_appliesTheWeeklyDelta_andRemovesTheRow() throws Exception {
        enqueue(deltasFromStats());

        outboxService.drain();

        assertThat(snapshotTotal("PAC")).isEqualByComparingTo("10.0000");
        assertThat(snapshotTotal("SHO")).isEqualByComparingTo("7.5000");
        assertThat(markerCount()).isEqualTo(2);
        assertThat(myOutboxRowCount()).isZero();
    }

    @Test
    void unknownAggregateType_isKeptWithAttemptsIncremented_neverDropped() {
        Long id = transactionTemplate.execute(s ->
            outboxRepository.save(new OutboxMessage("SOME_FUTURE_AGGREGATE", "{}")).getId());

        outboxService.drain();

        OutboxMessage still = outboxRepository.findById(id).orElseThrow();
        assertThat(still.getAttempts()).isEqualTo(1);
        assertThat(still.getLastError()).contains("no OutboxMessageHandler registered");
    }

    @Test
    void redrivingTheSamePayload_isANoOpThroughTheV119Marker_noOverReport() throws Exception {
        enqueue(deltasFromStats());
        outboxService.drain();
        BigDecimal afterFirst = snapshotTotal("PAC");

        // Same session/bucket enqueued and drained again — the marker rows already exist.
        enqueue(deltasFromStats());
        outboxService.drain();

        assertThat(snapshotTotal("PAC")).isEqualByComparingTo(afterFirst).isEqualByComparingTo("10.0000");
        assertThat(markerCount()).isEqualTo(2);
        assertThat(myOutboxRowCount()).isZero();
    }

    /**
     * AC4's own wording asks for the <em>producer</em> path, not a hand-inserted outbox row: drive
     * {@code SluSnapshotOutboxSupport.enqueueFailedSnapshotWrite} — what
     * {@code SnapshotPersistenceRetrier}'s {@code @Recover} actually calls when the retries are
     * exhausted — then prove the snapshot is repaired. Added by the skillars-deferred-91 code review;
     * every other test in this class bypassed the producer API entirely.
     *
     * <p>{@code enqueueFailedSnapshotWrite} also fires {@code requestDrainAfterCommit()}, so the
     * {@code @Async} AFTER_COMMIT drain runs on its own; the assertion is under Awaitility rather
     * than following an explicit {@code drain()} call.
     */
    @Test
    void recoverPathProducerApi_enqueuesAndTheAfterCommitDrainRepairsTheSnapshot() {
        // snapshotTotal() uses queryForObject, which THROWS on zero rows rather than returning null,
        // so the precondition is expressed as a count.
        assertThat(snapshotRowCount())
            .as("precondition: the weekly snapshot under-reports before the re-drive")
            .isZero();

        List<PlayerSkillStat> stats = jdbcTemplate.query(
            "SELECT session_id, player_id, skill_code, slu_value FROM development.player_skill_stats "
            + "WHERE session_id = ?",
            (rs, i) -> {
                PlayerSkillStat stat = new PlayerSkillStat();
                stat.setSessionId(UUID.fromString(rs.getString("session_id")));
                stat.setPlayerId(rs.getLong("player_id"));
                stat.setSkillCode(rs.getString("skill_code"));
                stat.setSluValue(rs.getBigDecimal("slu_value"));
                return stat;
            },
            sessionId);
        assertThat(stats).hasSize(2);

        sluSnapshotOutboxSupport.enqueueFailedSnapshotWrite(stats, ISO_YEAR, ISO_WEEK);

        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(20))
            .untilAsserted(() -> {
                assertThat(snapshotTotal("PAC")).isEqualByComparingTo("10.0000");
                assertThat(snapshotTotal("SHO")).isEqualByComparingTo("7.5000");
                assertThat(markerCount()).isEqualTo(2);
                assertThat(myOutboxRowCount()).isZero();
            });
    }
}
