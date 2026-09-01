package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.config.AbstractIntegrationTest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.booking.contract.BookingCompletedEvent;
import com.softropic.skillars.platform.development.repo.PlayerSkillStat;
import com.softropic.skillars.platform.development.repo.SluRepository;
import com.softropic.skillars.platform.development.repo.SluWeeklySnapshotRepository;
import com.softropic.skillars.platform.development.repo.SnapshotBatchWriter;
import com.softropic.skillars.platform.security.SecurityIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class SluCalculationServiceIT extends AbstractIntegrationTest {

    private static final long   TEST_PLAYER_ID = 9900000001L;
    private static final long   TEST_PARENT_USER_ID = 9900000000L;
    private static final UUID   TEST_COACH_ID  = UUID.fromString("99000000-0000-0000-0000-000000000001");

    // Drill metadata JSON using valid skill_definitions codes (PAC, SHO — seeded by V46 migration)
    private static final String DRILL_METADATA_JSON =
        "{\"primarySkills\":[\"PAC\"],\"secondarySkills\":[\"SHO\"]," +
        "\"skillWeighting\":{\"PAC\":5,\"SHO\":3}," +
        "\"repDensity\":8,\"intensity\":7,\"pressureLevel\":6,\"cognitiveLoad\":3,\"matchRealism\":5," +
        "\"weakFootBias\":false,\"difficultyTier\":\"U14\",\"equipmentRequired\":[\"ball\"]," +
        "\"recommendedGroupSize\":\"2\",\"coachingPoints\":[],\"setupDiagram\":null}";

    private static final String ZERO_WEIGHT_METADATA_JSON =
        "{\"primarySkills\":[],\"secondarySkills\":[]," +
        "\"skillWeighting\":{\"PAC\":0,\"SHO\":0}," +
        "\"repDensity\":5,\"intensity\":5,\"pressureLevel\":5,\"cognitiveLoad\":3,\"matchRealism\":5," +
        "\"weakFootBias\":false,\"difficultyTier\":\"U12\",\"equipmentRequired\":[]," +
        "\"recommendedGroupSize\":\"1\",\"coachingPoints\":[],\"setupDiagram\":null}";

    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private javax.sql.DataSource dataSource;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private SluRepository sluRepository;
    @Autowired private SnapshotBatchWriter snapshotBatchWriter;
    @Autowired private SluWeeklySnapshotRepository snapshotRepository;
    @Autowired private SluPersistenceRetrier sluPersistenceRetrier;

    @MockitoBean
    VideoProviderAdapter videoProviderAdapter;

    // player_slu_weekly_snapshot_applied (skillars-deferred-86 AC1) has an FK to main.player_profiles
    // (ON DELETE CASCADE, mirroring V113/V117). player_skill_stats / player_slu_weekly_snapshot have
    // no such FK, which is why this IT historically never needed a player_profiles row — the marker
    // table does, so seed one (idempotently) for TEST_PLAYER_ID before every test.
    @BeforeEach
    void seedPlayerProfile() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.\"user\" " +
                "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, " +
                "status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, " +
                "activated, locked, login, login_id_type, password_hash, otp_enabled, " +
                "skillars_role, verification_status) " +
                "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, " +
                "'ACTIVE', '1990-01-01', 'd86slu.parent@skillars-test.com', 'Test', 'OTHER', 'en', 'x', 'DE', '9900000000', " +
                "true, false, 'd86slu.parent@skillars-test.com', 'EMAIL', 'x', false, " +
                "'PARENT', 'BASIC_VERIFIED') ON CONFLICT (id) DO NOTHING",
                TEST_PARENT_USER_ID, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'D86 SLU Player', ?, 'MIDFIELDER', 'AGE_10_12', ?, false, ?, 'system') " +
                "ON CONFLICT (id) DO NOTHING",
                TEST_PLAYER_ID, Date.valueOf(LocalDate.now().minusYears(10)),
                TEST_PARENT_USER_ID, Timestamp.from(Instant.now()));
            return null;
        });
    }

    // player_skill_stats is append-only (no application-level delete path), so every test that
    // legitimately writes rows for the shared TEST_PLAYER_ID must clean them up itself — otherwise
    // countStats()'s global per-player count leaks across test methods and makes the "writesNoRows"
    // assertions order-dependent on which tests happened to run first (JUnit 5's default ordering is
    // deterministic per JVM but not declaration-order, and can differ between environments).
    @AfterEach
    void cleanUpSluRows() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM development.player_skill_stats WHERE player_id = ?", TEST_PLAYER_ID);
            jdbcTemplate.update("DELETE FROM development.player_slu_weekly_snapshot WHERE player_id = ?", TEST_PLAYER_ID);
            // skillars-deferred-86 M1: the marker table honours the same "every test cleans up its
            // own rows for the shared player" contract — the ON DELETE CASCADE FK does not help here
            // because the player_profiles row is deleted last (and only if the seed inserted it).
            jdbcTemplate.update("DELETE FROM development.player_slu_weekly_snapshot_applied WHERE player_id = ?", TEST_PLAYER_ID);
            jdbcTemplate.update("DELETE FROM main.player_profiles WHERE id = ?", TEST_PLAYER_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id = ?", TEST_PARENT_USER_ID);
            return null;
        });
    }

    private List<PlayerSkillStat> buildStats(UUID sessionId, String... skillCodesAndValues) {
        List<PlayerSkillStat> stats = new ArrayList<>();
        for (int i = 0; i < skillCodesAndValues.length; i += 2) {
            PlayerSkillStat stat = new PlayerSkillStat();
            stat.setPlayerId(TEST_PLAYER_ID);
            stat.setSessionId(sessionId);
            stat.setCoachId(TEST_COACH_ID);
            stat.setSkillCode(skillCodesAndValues[i]);
            stat.setSluValue(new BigDecimal(skillCodesAndValues[i + 1]));
            stat.setCalculatedAt(Instant.now());
            stats.add(stat);
        }
        return stats;
    }

    private BigDecimal snapshotTotal() {
        return jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(total_slu), 0) FROM development.player_slu_weekly_snapshot WHERE player_id = ?",
            BigDecimal.class, TEST_PLAYER_ID);
    }

    private int markerCount(UUID sessionId) {
        Integer c = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM development.player_slu_weekly_snapshot_applied WHERE session_id = ? AND player_id = ?",
            Integer.class, sessionId, TEST_PLAYER_ID);
        return c != null ? c : 0;
    }

    @Test
    void writeAll_calledTwiceForSameSession_appliesDeltaOnceAndRecordsMarkers() {
        UUID sessionId = UUID.randomUUID();
        ZonedDateTime now = ZonedDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
        short isoYear = (short) now.get(IsoFields.WEEK_BASED_YEAR);
        short isoWeek = (short) now.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        List<PlayerSkillStat> stats = buildStats(sessionId, "PAC", "10.0000", "SHO", "4.0000");

        // First write — the delta lands.
        snapshotBatchWriter.writeAll(stats, isoYear, isoWeek);
        assertThat(snapshotTotal()).isEqualByComparingTo("14.0000");
        assertThat(markerCount(sessionId)).isEqualTo(2);

        // Second write with the same session — semantically the ambiguous-commit retry. No-op.
        snapshotBatchWriter.writeAll(stats, isoYear, isoWeek);
        assertThat(snapshotTotal()).isEqualByComparingTo("14.0000");
        assertThat(markerCount(sessionId)).isEqualTo(2);
    }

    @Test
    void writeAll_twoDistinctSessionsSameBucket_totalIsTheSum() {
        ZonedDateTime now = ZonedDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
        short isoYear = (short) now.get(IsoFields.WEEK_BASED_YEAR);
        short isoWeek = (short) now.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        UUID sessionA = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();

        snapshotBatchWriter.writeAll(buildStats(sessionA, "PAC", "10.0000"), isoYear, isoWeek);
        snapshotBatchWriter.writeAll(buildStats(sessionB, "PAC", "3.0000"), isoYear, isoWeek);

        assertThat(snapshotTotal()).isEqualByComparingTo("13.0000");
        assertThat(markerCount(sessionA)).isEqualTo(1);
        assertThat(markerCount(sessionB)).isEqualTo(1);
    }

    // ── AC2: SLU saveAll retry safety ────────────────────────────────────────

    /**
     * Pins the CURRENT (pre-AC2-guard) retry behaviour of the SLU save path, driven through the real
     * {@code @Retryable}-proxied {@link SluPersistenceRetrier} bean. Uses rows with a {@code null}
     * {@code session_id} (a legitimate Quick-Complete shape) so the new {@code existsBySessionId}
     * check-then-act is skipped ({@code sessionId != null} is false) and {@code saveAll} is actually
     * reached on both invocations — the second one is the retry, semantically (same "no fault
     * injector" approach AC1's tests use).
     *
     * <p>On the second call the instances already carry an id from attempt 1's {@code persist()}, so
     * {@code SimpleJpaRepository.save} routes them through {@code em.merge()} — a SELECT-by-id that
     * finds the committed rows and, every non-key column being {@code updatable = false}, issues no
     * UPDATE. Asserts: no exception, no duplicate rows, and — via a log appender on the retrier — the
     * {@code @Recover} "rows lost … manual recovery needed" path is never taken. This is the evidence
     * that skillars-deferred-86 H1's re-framing of AC2 (documentation + backstop, not a bug fix) is
     * correct.
     */
    @Test
    void saveSluWithRetryRetriedTwice_nullSession_mergesCleanlyThroughProxyWithNoRecover() {
        List<PlayerSkillStat> stats = buildStats(null, "PAC", "12.0000", "SHO", "5.0000");

        Logger retrierLogger = (Logger) LoggerFactory.getLogger(SluPersistenceRetrier.class);
        ListAppender<ILoggingEvent> logCapture = new ListAppender<>();
        logCapture.start();
        retrierLogger.addAppender(logCapture);
        try {
            transactionTemplate.execute(status -> {
                sluPersistenceRetrier.saveSluWithRetry(stats);   // attempt 1 — through the proxy, reaches saveAll → persist
                return null;
            });
            assertThat(stats).allMatch(s -> s.getId() != null);
            long afterFirst = countStatsWithNullSessionFor(stats);

            assertThatCode(() -> transactionTemplate.execute(status -> {
                sluPersistenceRetrier.saveSluWithRetry(stats);   // "retry" — reaches saveAll → em.merge, not persist
                return null;
            })).doesNotThrowAnyException();

            assertThat(countStatsWithNullSessionFor(stats)).isEqualTo(afterFirst).isEqualTo(2);
            assertThat(logCapture.list)
                .noneMatch(e -> e.getLevel() == Level.ERROR && e.getFormattedMessage().contains("rows lost"));
        } finally {
            retrierLogger.detachAppender(logCapture);
            transactionTemplate.execute(status -> {
                stats.forEach(s -> jdbcTemplate.update(
                    "DELETE FROM development.player_skill_stats WHERE id = ?", s.getId()));
                return null;
            });
        }
    }

    private long countStatsWithNullSessionFor(List<PlayerSkillStat> stats) {
        Long c = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM development.player_skill_stats WHERE player_id = ? AND session_id IS NULL",
            Long.class, TEST_PLAYER_ID);
        return c != null ? c : 0L;
    }

    @Test
    void saveSluWithRetry_calledAgainForAlreadyPersistedSession_writesNoDuplicateRows() {
        UUID drillId   = insertDrill(DRILL_METADATA_JSON);
        UUID bookingId = UUID.randomUUID();
        UUID sessionId = insertSession(bookingId, drillId, "SAVED", 10, 1);

        publishEventInTransaction(bookingId, true);
        await().atMost(5, SECONDS).until(() -> countStats() > 0);

        List<PlayerSkillStat> persisted = sluRepository.findBySessionId(sessionId);
        int before = countStatsForSession(sessionId);
        assertThat(before).isPositive();

        Logger retrierLogger = (Logger) LoggerFactory.getLogger(SluPersistenceRetrier.class);
        ListAppender<ILoggingEvent> logCapture = new ListAppender<>();
        logCapture.start();
        retrierLogger.addAppender(logCapture);
        try {
            // Direct second call through the @Retryable-proxied bean — AC2's check-then-act sees
            // existsBySessionId == true, returns before saveAll, so no exception and @Recover is
            // structurally unreachable (nothing was attempted to recover from).
            assertThatCode(() -> sluPersistenceRetrier.saveSluWithRetry(persisted)).doesNotThrowAnyException();
        } finally {
            retrierLogger.detachAppender(logCapture);
        }

        assertThat(countStatsForSession(sessionId)).isEqualTo(before);
        assertThat(logCapture.list)
            .noneMatch(e -> e.getLevel() == Level.ERROR && e.getFormattedMessage().contains("rows lost"));

        cleanDrill(drillId);
        cleanSession(bookingId);
    }

    // ── AC1 (skillars-deferred-89): concurrent-delivery collision on the EXISTING V47 index ──────
    //
    // Two BookingCompletedEvent deliveries for one session can both pass the non-locking
    // existsBySessionId / findBySessionId guards, both reach saveAll, and the loser's INSERT collides
    // with V47's uq_player_skill_stats_session_skill (PG 23505). Pre-AC1 that DataIntegrityViolationException
    // was retried 3× (it is a DataAccessException, already in retryFor) and then logged a FALSE
    // "rows lost … manual recovery needed" @Recover ERROR for rows the winner did persist. AC1 catches
    // that specific constraint inside saveSluWithRetry and returns normally (a clean idempotent no-op).
    //
    // Determinism: a second raw JDBC connection ("winner") inserts the same (session_id, skill_code)
    // rows and holds the transaction OPEN. The proxied saveSluWithRetry runs on a worker thread; its
    // existsBySessionId (own READ COMMITTED tx) sees nothing, so it proceeds to saveAll, whose
    // flush/commit blocks on the winner's uncommitted unique-key row. We wait — via pg_stat_activity —
    // until a backend is genuinely blocked on a lock against player_skill_stats, THEN commit the
    // winner, so the worker deterministically takes the 23505 path, never the existsBySessionId
    // short-circuit.
    @Test
    void saveSluWithRetry_concurrentDeliveryCollisionOnV47_isCleanNoOp_noRecoverError() throws Exception {
        UUID sessionId = UUID.randomUUID();
        List<PlayerSkillStat> losingDelivery = buildStats(sessionId, "PAC", "10.0000", "SHO", "4.0000");

        Logger retrierLogger = (Logger) LoggerFactory.getLogger(SluPersistenceRetrier.class);
        ListAppender<ILoggingEvent> logCapture = new ListAppender<>();
        logCapture.start();
        retrierLogger.addAppender(logCapture);

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (java.sql.Connection winner = dataSource.getConnection()) {
            winner.setAutoCommit(false);
            try (java.sql.PreparedStatement ps = winner.prepareStatement(
                "INSERT INTO development.player_skill_stats " +
                "(id, player_id, session_id, coach_id, skill_code, slu_value, calculated_at) " +
                "VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, now())")) {
                for (String skillCode : List.of("PAC", "SHO")) {
                    ps.setLong(1, TEST_PLAYER_ID);
                    ps.setObject(2, sessionId);
                    ps.setObject(3, TEST_COACH_ID);
                    ps.setString(4, skillCode);
                    ps.setBigDecimal(5, new BigDecimal("1.0000"));
                    ps.addBatch();
                }
                ps.executeBatch();   // winner's rows are written but NOT committed
            }

            java.util.concurrent.Future<SluSaveOutcome> loser =
                pool.submit(() -> sluPersistenceRetrier.saveSluWithRetry(losingDelivery));

            // Wait until the worker is actually blocked on a lock touching player_skill_stats.
            await().atMost(15, SECONDS).until(() -> {
                Integer blocked = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity " +
                    "WHERE wait_event_type = 'Lock' AND query ILIKE '%player_skill_stats%'",
                    Integer.class);
                return blocked != null && blocked > 0;
            });

            winner.commit();   // → the worker's INSERT now fails with 23505 → AC1 catch

            // ALREADY_PERSISTED, not SAVED: the collision path reports that a concurrent delivery
            // owns these rows (and the weekly-snapshot write for their bucket), so the dispatcher
            // skips its own snapshot write — see SluSaveOutcome / SluPersistenceDispatcherTest.
            assertThat(loser.get(15, SECONDS)).isEqualTo(SluSaveOutcome.ALREADY_PERSISTED);
        } finally {
            pool.shutdownNow();
            retrierLogger.detachAppender(logCapture);
        }

        // The losing delivery is a clean idempotent no-op: the AC1 catch fired once …
        assertThat(logCapture.list)
            .anyMatch(e -> e.getFormattedMessage().contains("already persisted by a concurrent delivery"));
        // … and the false @Recover "rows lost" ERROR did NOT fire.
        assertThat(logCapture.list)
            .noneMatch(e -> e.getLevel() == Level.ERROR && e.getFormattedMessage().contains("rows lost"));

        // End state: exactly one set of detail rows for the session (the winner's two), and the SUM
        // is the single-delivery value — the losing saveAll added nothing.
        assertThat(countStatsForSession(sessionId)).isEqualTo(2);
        BigDecimal sum = jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(slu_value), 0) FROM development.player_skill_stats WHERE session_id = ?",
            BigDecimal.class, sessionId);
        assertThat(sum).isEqualByComparingTo("2.0000");   // 1.0000 (PAC) + 1.0000 (SHO), winner only
    }

    @Test
    void upsertAddIdempotent_firstCallAppliesDeltaAndMarker_secondCallIsNoOp() {
        UUID sessionId = UUID.randomUUID();
        ZonedDateTime now = ZonedDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
        short isoYear = (short) now.get(IsoFields.WEEK_BASED_YEAR);
        short isoWeek = (short) now.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);

        transactionTemplate.execute(status -> {
            snapshotRepository.upsertAddIdempotent(sessionId, TEST_PLAYER_ID, "PAC", isoYear, isoWeek, new BigDecimal("7.5000"));
            return null;
        });
        assertThat(snapshotTotal()).isEqualByComparingTo("7.5000");
        assertThat(markerCount(sessionId)).isEqualTo(1);

        // Same key again — marker INSERT hits ON CONFLICT DO NOTHING, WHERE EXISTS(ins) is false,
        // total_slu is untouched.
        transactionTemplate.execute(status -> {
            snapshotRepository.upsertAddIdempotent(sessionId, TEST_PLAYER_ID, "PAC", isoYear, isoWeek, new BigDecimal("7.5000"));
            return null;
        });
        assertThat(snapshotTotal()).isEqualByComparingTo("7.5000");
        assertThat(markerCount(sessionId)).isEqualTo(1);
    }

    @Test
    void onBookingCompleted_withStructuredSession_writesPlayerSkillStatRows() {
        UUID drillId   = insertDrill(DRILL_METADATA_JSON);
        UUID bookingId = UUID.randomUUID();
        UUID sessionId = insertSession(bookingId, drillId, "SAVED", 10, 1);

        publishEventInTransaction(bookingId, true);

        await().atMost(5, SECONDS).until(() -> countStats() > 0);

        List<PlayerSkillStat> stats = sluRepository.findByPlayerIdOrderByCalculatedAtDesc(TEST_PLAYER_ID);
        assertThat(stats).isNotEmpty();
        assertThat(stats).allMatch(s -> s.getSessionId().equals(sessionId));
        assertThat(stats).allMatch(s -> s.getCoachId().equals(TEST_COACH_ID));
        assertThat(stats).allMatch(s -> s.getPlayerId().equals(TEST_PLAYER_ID));
        assertThat(stats.stream().map(PlayerSkillStat::getSkillCode).toList())
            .containsAnyOf("PAC", "SHO");

        // The weekly snapshot write goes through SnapshotPersistenceRetrier.writeAllWithRetry now
        // (Deferred-84 AC2). Assert it actually landed rows — a silently no-opping retrier would
        // still leave the assertions above green. This is the first (and only) snapshot write for
        // the current ISO week in this test run, so the snapshot total mirrors the SLU rows exactly.
        // Poll: the snapshot commits in its own @Transactional tx, which can land after the SLU rows
        // (own tx) are already visible on the same @Async thread — a single read could see 0.
        BigDecimal statsTotal = stats.stream()
            .map(PlayerSkillStat::getSluValue)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        await().atMost(5, SECONDS).untilAsserted(() -> {
            BigDecimal snapshotTotal = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(total_slu), 0) FROM development.player_slu_weekly_snapshot WHERE player_id = ?",
                BigDecimal.class, TEST_PLAYER_ID);
            assertThat(snapshotTotal).isEqualByComparingTo(statsTotal);
        });

        cleanDrill(drillId);
        cleanSession(bookingId);
    }

    @Test
    void onBookingCompleted_completedSession_writesPlayerSkillStatRows() {
        UUID drillId   = insertDrill(DRILL_METADATA_JSON);
        UUID bookingId = UUID.randomUUID();
        UUID sessionId = insertSession(bookingId, drillId, "COMPLETED", 10, 1);

        publishEventInTransaction(bookingId, true);

        await().atMost(5, SECONDS).until(() -> countStats() > 0);

        List<PlayerSkillStat> stats = sluRepository.findByPlayerIdOrderByCalculatedAtDesc(TEST_PLAYER_ID);
        assertThat(stats).isNotEmpty();
        assertThat(stats).allMatch(s -> s.getSessionId().equals(sessionId));

        cleanDrill(drillId);
        cleanSession(bookingId);
    }

    @Test
    void onBookingCompleted_playerNotAttended_writesNoRows() throws Exception {
        UUID drillId   = insertDrill(DRILL_METADATA_JSON);
        UUID bookingId = UUID.randomUUID();
        insertSession(bookingId, drillId, "SAVED", 10, 1);

        publishEventInTransaction(bookingId, false);

        Thread.sleep(1000L);

        assertThat(countStats()).isZero();

        cleanDrill(drillId);
        cleanSession(bookingId);
    }

    @Test
    void onBookingCompleted_draftSession_writesNoRows() throws Exception {
        UUID drillId   = insertDrill(DRILL_METADATA_JSON);
        UUID bookingId = UUID.randomUUID();
        insertSession(bookingId, drillId, "DRAFT", 10, 1);

        publishEventInTransaction(bookingId, true);

        Thread.sleep(1000L);

        assertThat(countStats()).isZero();

        cleanDrill(drillId);
        cleanSession(bookingId);
    }

    @Test
    void onBookingCompleted_noSessionForBooking_writesNoRows() throws Exception {
        UUID bookingId = UUID.randomUUID(); // no session created for this booking

        publishEventInTransaction(bookingId, true);

        Thread.sleep(1000L);

        assertThat(countStats()).isZero();
    }

    @Test
    void onBookingCompleted_emptyBlocks_writesNoRows() throws Exception {
        UUID bookingId = UUID.randomUUID();
        // Insert session with empty blocks list
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO session.sessions (id, booking_id, coach_id, player_id, blocks, " +
                "equipment_list, development_focus, status, created_at, updated_at) " +
                "VALUES (gen_random_uuid(), ?, ?, ?, '[]'::jsonb, '[]'::jsonb, '[]'::jsonb, 'SAVED', NOW(), NOW())",
                bookingId, TEST_COACH_ID, TEST_PLAYER_ID
            );
            return null;
        });

        publishEventInTransaction(bookingId, true);

        Thread.sleep(1000L);

        assertThat(countStats()).isZero();

        cleanSession(bookingId);
    }

    @Test
    void onBookingCompleted_blocksWithEmptyDrillsList_writesNoRows() throws Exception {
        // AC 7 second clause: blocks exist but all drill lists are empty
        UUID bookingId = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO session.sessions (id, booking_id, coach_id, player_id, blocks, " +
                "equipment_list, development_focus, status, created_at, updated_at) " +
                "VALUES (gen_random_uuid(), ?, ?, ?, '[{\"blockType\":\"MAIN\",\"blockName\":\"Main\"," +
                "\"durationMinutes\":10,\"drills\":[]}]'::jsonb, '[]'::jsonb, '[]'::jsonb, 'SAVED', NOW(), NOW())",
                bookingId, TEST_COACH_ID, TEST_PLAYER_ID
            );
            return null;
        });

        publishEventInTransaction(bookingId, true);

        Thread.sleep(1000L);

        assertThat(countStats()).isZero();

        cleanSession(bookingId);
    }

    @Test
    void onBookingCompleted_allZeroWeights_writesNoRows() throws Exception {
        UUID drillId   = insertDrill(ZERO_WEIGHT_METADATA_JSON);
        UUID bookingId = UUID.randomUUID();
        insertSession(bookingId, drillId, "SAVED", 10, 1);

        publishEventInTransaction(bookingId, true);

        Thread.sleep(1000L);

        assertThat(countStats()).isZero();

        cleanDrill(drillId);
        cleanSession(bookingId);
    }

    @Test
    void onBookingCompleted_drillRepeatedInMultipleBlocks_accumulatesSluFromBothBlocks() {
        UUID drillId   = insertDrill(DRILL_METADATA_JSON);
        UUID bookingId = UUID.randomUUID();
        // Two blocks, each containing the same drill (5 min each)
        String blocksJson = String.format(
            "[{\"blockType\":\"WARM_UP\",\"blockName\":\"Block A\",\"durationMinutes\":5," +
            "\"drills\":[{\"drillId\":\"%s\",\"order\":0}]}," +
            "{\"blockType\":\"MAIN\",\"blockName\":\"Block B\",\"durationMinutes\":5," +
            "\"drills\":[{\"drillId\":\"%s\",\"order\":0}]}]",
            drillId, drillId
        );
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO session.sessions (id, booking_id, coach_id, player_id, blocks, " +
                "equipment_list, development_focus, status, created_at, updated_at) " +
                "VALUES (gen_random_uuid(), ?, ?, ?, ?::jsonb, '[]'::jsonb, '[]'::jsonb, 'SAVED', NOW(), NOW())",
                bookingId, TEST_COACH_ID, TEST_PLAYER_ID, blocksJson
            );
            return null;
        });

        publishEventInTransaction(bookingId, true);

        await().atMost(5, SECONDS).until(() -> countStats() > 0);

        // Single-block single-drill SLU for reference — repDensity=8, weight=5, intensity=7,
        // pressure=6, matchRealism=5, intensityScale=0.10, pressureScale=0.10, matchRealismScale=0.10
        // duration=5min per block → slu_PAC = 8×5×0.7×0.6×0.5×5 = 42.0 per block → 84.0 total
        List<PlayerSkillStat> stats = sluRepository.findByPlayerIdOrderByCalculatedAtDesc(TEST_PLAYER_ID);
        PlayerSkillStat pac = stats.stream()
            .filter(s -> "PAC".equals(s.getSkillCode()))
            .findFirst()
            .orElse(null);
        assertThat(pac).isNotNull();
        // Two block appearances × 42.0 each = 84.0
        assertThat(pac.getSluValue()).isEqualByComparingTo(new BigDecimal("84.0000"));

        cleanDrill(drillId);
        cleanSession(bookingId);
    }

    @Test
    void onBookingCompleted_missingDrill_skipsAndContinues() {
        UUID missingDrillId = UUID.randomUUID();
        UUID realDrillId    = insertDrill(DRILL_METADATA_JSON);
        UUID bookingId      = UUID.randomUUID();

        String blocksJson = String.format(
            "[{\"blockType\":\"MAIN\",\"blockName\":\"Main\",\"durationMinutes\":10," +
            "\"drills\":[{\"drillId\":\"%s\",\"order\":0},{\"drillId\":\"%s\",\"order\":1}]}]",
            missingDrillId, realDrillId
        );
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO session.sessions (id, booking_id, coach_id, player_id, blocks, " +
                "equipment_list, development_focus, status, created_at, updated_at) " +
                "VALUES (gen_random_uuid(), ?, ?, ?, ?::jsonb, '[]'::jsonb, '[]'::jsonb, 'SAVED', NOW(), NOW())",
                bookingId, TEST_COACH_ID, TEST_PLAYER_ID, blocksJson
            );
            return null;
        });

        publishEventInTransaction(bookingId, true);

        // Real drill should still produce rows even though the first drill was missing
        await().atMost(5, SECONDS).until(() -> countStats() > 0);

        assertThat(countStats()).isPositive();

        cleanDrill(realDrillId);
        cleanSession(bookingId);
    }

    @Test
    void onBookingCompleted_sluValuesAreImmutable_existingRowNotUpdated() {
        // Save an initial stat row with slu_value = 1.0
        PlayerSkillStat original = new PlayerSkillStat();
        original.setPlayerId(TEST_PLAYER_ID);
        original.setSessionId(null);
        original.setCoachId(TEST_COACH_ID);
        original.setSkillCode("PAC");
        original.setSluValue(new BigDecimal("1.0000"));
        original.setCalculatedAt(Instant.now());
        PlayerSkillStat saved = sluRepository.saveAndFlush(original);

        // Load and attempt to modify the immutable fields
        PlayerSkillStat loaded = sluRepository.findById(saved.getId()).orElseThrow();
        loaded.setSluValue(new BigDecimal("999.0000"));
        sluRepository.saveAndFlush(loaded);

        // Re-query from DB — updatable=false prevents the UPDATE SQL; DB value unchanged
        BigDecimal dbValue = jdbcTemplate.queryForObject(
            "SELECT slu_value FROM development.player_skill_stats WHERE id = ?",
            BigDecimal.class, saved.getId()
        );
        assertThat(dbValue).isEqualByComparingTo(new BigDecimal("1.0000"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UUID insertDrill(String metadataJson) {
        UUID drillId = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO session.drills (id, name, library_type, owner_coach_id, status, metadata, version) " +
                "VALUES (?, 'SLU Test Drill', 'PLATFORM', NULL, 'ACTIVE', ?::jsonb, 0)",
                drillId, metadataJson
            );
            return null;
        });
        return drillId;
    }

    private UUID insertSession(UUID bookingId, UUID drillId, String status, int blockDuration, int numDrillsInBlock) {
        UUID sessionId = UUID.randomUUID();
        StringBuilder drillsArr = new StringBuilder("[");
        for (int i = 0; i < numDrillsInBlock; i++) {
            if (i > 0) drillsArr.append(",");
            drillsArr.append(String.format("{\"drillId\":\"%s\",\"order\":%d}", drillId, i));
        }
        drillsArr.append("]");
        String blocksJson = String.format(
            "[{\"blockType\":\"MAIN\",\"blockName\":\"Main\",\"durationMinutes\":%d,\"drills\":%s}]",
            blockDuration, drillsArr
        );
        transactionTemplate.execute(txStatus -> {
            jdbcTemplate.update(
                "INSERT INTO session.sessions (id, booking_id, coach_id, player_id, blocks, " +
                "equipment_list, development_focus, status, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?::jsonb, '[]'::jsonb, '[]'::jsonb, ?, NOW(), NOW())",
                sessionId, bookingId, TEST_COACH_ID, TEST_PLAYER_ID, blocksJson, status
            );
            return null;
        });
        return sessionId;
    }

    private void publishEventInTransaction(UUID bookingId, boolean playerAttended) {
        transactionTemplate.execute(status -> {
            eventPublisher.publishEvent(new BookingCompletedEvent(
                this, bookingId, TEST_COACH_ID, TEST_PLAYER_ID,
                null, playerAttended, null, null, null, List.of()
            ));
            return null;
        });
    }

    private int countStats() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM development.player_skill_stats WHERE player_id = ?",
            Integer.class, TEST_PLAYER_ID
        );
        return count != null ? count : 0;
    }

    private int countStatsForSession(UUID sessionId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM development.player_skill_stats WHERE session_id = ?",
            Integer.class, sessionId
        );
        return count != null ? count : 0;
    }

    private void cleanDrill(UUID drillId) {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM session.drills WHERE id = ?", drillId);
            return null;
        });
    }

    private void cleanSession(UUID bookingId) {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM session.sessions WHERE booking_id = ?", bookingId);
            return null;
        });
    }
}
