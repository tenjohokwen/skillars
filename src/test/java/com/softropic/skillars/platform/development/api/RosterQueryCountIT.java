package com.softropic.skillars.platform.development.api;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.development.service.CoachRosterService;
import com.softropic.skillars.platform.security.SecurityIT;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-12-10 AC8 (D7, G23): the roster's per-player fan-out is BOUNDED by {@code development.roster.maxPlayers}
 * and costs a constant number of statements per player. Owner decision: keep the cap, no batching rewrite --
 * this IT is the regression lock. Same measurement approach as {@code CoachPublicProfileQueryCountIT}
 * (Hibernate statistics enabled programmatically so no property fork).
 *
 * <p>The cap is lowered to {@value #CAP} so the "cap bounds the fan-out" assertion is reachable (at the real
 * default of 150 the same property would need 160 seeded players). Every player is seeded identically, because
 * the resolver's third query is conditional and a heterogeneous seed would make {@code k} non-constant.
 * Formula: {@code statements = k * N + c} for {@code N <= CAP}, and constant beyond CAP. {@code k} depends on the
 * player's shape, so each shape has its own pinned budget: 4 (booked, nothing attended), 7 (attended), 2 (lapsed).
 */
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class RosterQueryCountIT extends AbstractIntegrationTest {

    private static final int CAP = 6;
    private static final String MAX_PLAYERS_KEY = "development.roster.maxPlayers";
    private static final long COACH_USER_ID = 9_350_000_010L;
    private static final long PARENT_USER_ID = 9_350_000_001L;
    private static final long FIRST_PLAYER_ID = 9_350_000_100L;

    @Autowired private CoachRosterService coachRosterService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private ConfigService configService;

    private UUID coachProfileId;
    private int seeded;
    private Statistics statistics;

    @BeforeEach
    void setUp() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        transactionTemplate.execute(status -> {
            insertAuthority(9350, "ROLE_COACH");
            insertAuthority(9351, "ROLE_PARENT");
            insertUser(COACH_USER_ID, "coach.rqc@skillars-test.com", "hash", "COACH");
            grantRole(COACH_USER_ID, "ROLE_COACH");
            coachProfileId = insertCoachProfile(COACH_USER_ID);
            insertUser(PARENT_USER_ID, "parent.rqc@skillars-test.com", "hash", "PARENT");
            grantRole(PARENT_USER_ID, "ROLE_PARENT");
            jdbcTemplate.update(
                "INSERT INTO main.platform_config (id, key, value, value_type) VALUES (9703, ?, ?, 'LONG') " +
                "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value", MAX_PLAYERS_KEY, String.valueOf(CAP));
            return null;
        });
        configService.invalidate();
        seeded = 0;
    }

    @AfterEach
    void tearDown() {
        statistics.setStatisticsEnabled(false);
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM main.platform_config WHERE key = ?", MAX_PLAYERS_KEY);
            return null;
        });
        configService.invalidate();
    }

    @Test
    void rosterStatementCount_isLinearInPlayersAndBoundedByTheCap() {
        seedUpTo(2);
        long at2 = measure(2);
        seedUpTo(4);
        long at4 = measure(4);
        seedUpTo(CAP);
        long atCap = measure(CAP);
        seedUpTo(CAP + 4);
        long beyondCap = measure(CAP);

        long perPlayer = (at4 - at2) / 2;
        assertThat(perPlayer).as("a constant, strictly positive number of statements per roster player").isPositive();
        assertThat(at4 - at2).as("k is constant between 2 and 4 players").isEqualTo(2 * perPlayer);
        assertThat(atCap - at4).as("and between 4 and the cap").isEqualTo((CAP - 4) * perPlayer);
        assertThat(beyondCap).as("seeding past the cap adds nothing: the cap bounds the fan-out").isEqualTo(atCap);
        assertThat(perPlayer).as("documented per-player budget: resolver 2 (SLU raw date, timeline top-1) + "
            + "neglected count + weekly snapshots; the resolver's end-time queries are skipped without an attended row")
            .isEqualTo(EXPECTED_STATEMENTS_PER_PLAYER);
    }

    /** Measured, then pinned -- raise deliberately (and re-justify the cap), never silently. */
    private static final long EXPECTED_STATEMENTS_PER_PLAYER = 4;

    // Code review 2026-10-23: the test above seeds no attended rows, so it pins only the CHEAPEST path. A real
    // attended player also runs the resolver's two session-END aggregates and (normal async ordering,
    // occurred_at < calculated_at) the recap booking-id lookup. Lapsed players skip neglected count + snapshots.
    private static final long EXPECTED_STATEMENTS_PER_ATTENDED_PLAYER = 7;
    private static final long EXPECTED_STATEMENTS_PER_LAPSED_PLAYER = 2;

    @Test
    void rosterStatementCount_attendedPlayers_haveTheirOwnPinnedBudget() {
        kind = Kind.ATTENDED;
        seedUpTo(2);
        long at2 = measure(2);
        seedUpTo(4);
        long at4 = measure(4);

        assertThat((at4 - at2) / 2).as("documented per-player budget for an attended player: resolver 5 (SLU raw "
            + "date, timeline top-1, SLU end, timeline end, recap booking id) + neglected count + weekly snapshots")
            .isEqualTo(EXPECTED_STATEMENTS_PER_ATTENDED_PLAYER);
        assertThat(at4 - at2).isEqualTo(2 * EXPECTED_STATEMENTS_PER_ATTENDED_PLAYER);
    }

    @Test
    void rosterStatementCount_lapsedPlayers_skipTheDetailQueries() {
        kind = Kind.LAPSED;
        seedUpTo(2);
        long at2 = measure(2);
        seedUpTo(4);
        long at4 = measure(4);

        assertThat((at4 - at2) / 2).as("a lapsed entry runs only the resolver's 2 raw lookups (no neglected count, "
            + "no snapshots, no attended rows)").isEqualTo(EXPECTED_STATEMENTS_PER_LAPSED_PLAYER);
        assertThat(at4 - at2).isEqualTo(2 * EXPECTED_STATEMENTS_PER_LAPSED_PLAYER);
    }

    /** How {@link #seedUpTo} shapes each player. Identical within one test, so {@code k} stays constant. */
    private enum Kind { BOOKED, ATTENDED, LAPSED }

    private Kind kind = Kind.BOOKED;

    private long measure(int expectedRosterSize) {
        // Warm-up: the first call also pays one-off config-cache loads, which would skew the first measurement.
        transactionTemplate.execute(status -> coachRosterService.getRoster(coachProfileId));
        statistics.clear();
        int size = transactionTemplate.execute(status -> coachRosterService.getRoster(coachProfileId).players().size());
        assertThat(size).isEqualTo(expectedRosterSize);
        return statistics.getPrepareStatementCount();
    }

    private void seedUpTo(int n) {
        transactionTemplate.execute(status -> {
            while (seeded < n) {
                long playerId = FIRST_PLAYER_ID + seeded;
                jdbcTemplate.update(
                    "INSERT INTO main.player_profiles " +
                    "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                    "VALUES (?, ?, ?, 'MIDFIELDER', 'AGE_10_12', ?, false, ?, 'system')",
                    playerId, "RQC Player " + seeded, Date.valueOf(LocalDate.now().minusYears(10)),
                    PARENT_USER_ID, Timestamp.from(Instant.now()));
                // Identical per player: one COMPLETED booking (5 days ago, or 100 days ago = outside the window for
                // LAPSED). A distinct day each is unnecessary -- the cap query orders by recency then player id, so
                // ties are deterministic.
                Instant now = Instant.now();
                Instant end = now.minus(Duration.ofDays(kind == Kind.LAPSED ? 100 : 5));
                UUID bookingId = UUID.randomUUID();
                jdbcTemplate.update(
                    "INSERT INTO booking.bookings " +
                    "(id, parent_id, player_id, coach_id, requested_start_time, requested_end_time, status, " +
                    " canonical_timezone, version, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, 'COMPLETED', 'Europe/Berlin', 0, ?, ?)",
                    bookingId, PARENT_USER_ID, playerId, coachProfileId,
                    Timestamp.from(end.minus(Duration.ofHours(1))), Timestamp.from(end),
                    Timestamp.from(now), Timestamp.from(now));
                if (kind == Kind.ATTENDED) {
                    // One attended session per player, both signals present, timeline stamped just before SLU
                    // (the normal async ordering) so the resolver's recap booking-id lookup fires for every player.
                    UUID sessionId = UUID.randomUUID();
                    jdbcTemplate.update(
                        "INSERT INTO session.sessions (id, booking_id, coach_id, player_id, status) " +
                        "VALUES (?, ?, ?, ?, 'COMPLETED')", sessionId, bookingId, coachProfileId, playerId);
                    jdbcTemplate.update(
                        "INSERT INTO development.player_skill_stats " +
                        "(id, player_id, session_id, coach_id, skill_code, slu_value, calculated_at) " +
                        "VALUES (gen_random_uuid(), ?, ?, ?, 'PAC', 10.0, ?)",
                        playerId, sessionId, coachProfileId, Timestamp.from(now));
                    jdbcTemplate.update(
                        "INSERT INTO development.player_timeline_events " +
                        "(id, player_id, event_type, reference_id, reference_module, occurred_at, coach_id) " +
                        "VALUES (?, ?, 'SESSION_COMPLETED', ?, 'booking', ?, ?)",
                        UUID.randomUUID(), playerId, bookingId, Timestamp.from(now.minusSeconds(1)), coachProfileId);
                }
                seeded++;
            }
            return null;
        });
    }

    private void insertAuthority(int id, String name) {
        jdbcTemplate.update(
            "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
            "VALUES (?, ?, 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
            id, name, Timestamp.from(Instant.now())
        );
    }

    private void insertUser(long id, String email, String passwordHash, String role) {
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
            "69" + (id % 100000000L),
            email, passwordHash, role
        );
    }

    private void grantRole(long userId, String roleName) {
        jdbcTemplate.update(
            "INSERT INTO main.user_authority (user_id, authority_id) " +
            "VALUES (?, (SELECT id FROM main.authority WHERE name = ?)) ON CONFLICT DO NOTHING",
            userId, roleName
        );
    }

    private UUID insertCoachProfile(long userId) {
        UUID profileId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO marketplace.coach_profiles " +
            "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
            "VALUES (?, ?, 'Test Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
            profileId, userId
        );
        return profileId;
    }
}
