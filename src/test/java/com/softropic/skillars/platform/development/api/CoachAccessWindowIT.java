package com.softropic.skillars.platform.development.api;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.SecurityIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * skillars-12-10 AC1/AC3/AC4/AC6/AC7: the single coach-access window across the HTTP surface, plus the
 * preferences relationship check (G19), the session-time clock (G21) and the roster {@code accessExpired}
 * flag. Boundaries use a 30-minute margin around the 90-day window (the server reads the system clock
 * while this test seeds with one {@code Instant} base, so seconds-level margins would be flaky).
 */
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class CoachAccessWindowIT extends AbstractIntegrationTest {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String CLIENT_ID      = "testClientId";
    private static final String TEST_PASSWORD  = "TestPass@123!";

    private static final long COACH_USER_ID  = 9_340_000_010L;
    private static final long OTHER_COACH_USER_ID = 9_340_000_011L;
    private static final long PARENT_USER_ID = 9_340_000_001L;
    private static final long PLAYER_ID      = 9_340_000_020L;
    private static final long SECOND_PLAYER_ID = 9_340_000_021L;

    private static final String COACH_EMAIL  = "coach.window@skillars-test.com";
    private static final String OTHER_COACH_EMAIL = "othercoach.window@skillars-test.com";
    private static final String PARENT_EMAIL = "parent.window@skillars-test.com";

    private static final Duration WINDOW = Duration.ofDays(90);
    private static final Duration MARGIN = Duration.ofMinutes(30);

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private RestTemplate restTemplate;
    @Autowired private PasswordEncoder passwordEncoder;
    @LocalServerPort private int randomServerPort;

    private UUID coachProfileId;
    private UUID otherCoachProfileId;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        transactionTemplate.execute(status -> {
            insertAuthority(9340, "ROLE_COACH");
            insertAuthority(9341, "ROLE_PARENT");
            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH");
            grantRole(COACH_USER_ID, "ROLE_COACH");
            coachProfileId = insertCoachProfile(COACH_USER_ID);
            insertUser(OTHER_COACH_USER_ID, OTHER_COACH_EMAIL, passwordHash, "COACH");
            grantRole(OTHER_COACH_USER_ID, "ROLE_COACH");
            otherCoachProfileId = insertCoachProfile(OTHER_COACH_USER_ID);
            insertUser(PARENT_USER_ID, PARENT_EMAIL, passwordHash, "PARENT");
            grantRole(PARENT_USER_ID, "ROLE_PARENT");
            insertPlayer(PLAYER_ID, "Window Player");
            insertPlayer(SECOND_PLAYER_ID, "Second Player");
            return null;
        });
    }

    private void insertPlayer(long id, String name) {
        jdbcTemplate.update(
            "INSERT INTO main.player_profiles " +
            "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
            "VALUES (?, ?, ?, 'MIDFIELDER', 'AGE_10_12', ?, false, ?, 'system')",
            id, name, Date.valueOf(LocalDate.now().minusYears(10)), PARENT_USER_ID, Timestamp.from(Instant.now()));
    }

    // ── AC3/AC6: reads ──────────────────────────────────────────────────────────────────────────

    private static final List<String> READ_PATHS = List.of(
        "/exposure", "/narrative", "/neglected-skills", "/slu/coach-contributions", "/slu/skill-trends",
        "/radar/display", "/radar/preferences", "/targets", "/reports");

    @Test
    void everyRead_bookingEndJustInsideWindow_succeeds_justOutside_is403() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        Instant now = Instant.now();

        UUID booking = insertBooking(coachProfileId, PLAYER_ID, "COMPLETED", now.minus(WINDOW).plus(MARGIN));
        for (String path : READ_PATHS) {
            assertThat(get(path, cookies).getStatusCode()).as(path + " inside window").isEqualTo(HttpStatus.OK);
        }

        setBookingEnd(booking, now.minus(WINDOW).minus(MARGIN));
        for (String path : READ_PATHS) {
            assertStatus(path, cookies, HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void everyRead_futureDatedBooking_withNoAttendedSession_staysOpen() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        insertBooking(coachProfileId, PLAYER_ID, "CONFIRMED", Instant.now().plus(Duration.ofDays(3)));

        for (String path : READ_PATHS) {
            assertThat(get(path, cookies).getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
        }
    }

    // AC2.6 / review F2: the wrap-up-to-completion gap -- the booking ended minutes ago and no attended signal
    // exists yet (BookingCompletedEvent is published later for a non-LIVE wrap-up).
    @Test
    void everyRead_bookingEndedMinutesAgoWithNoAttendedSignalYet_isOpen() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        insertBooking(coachProfileId, PLAYER_ID, "COMPLETED_PENDING_CONFIRMATION", Instant.now().minus(Duration.ofMinutes(5)));

        for (String path : READ_PATHS) {
            assertThat(get(path, cookies).getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void everyRead_noRelationship_is403() {
        String cookies = loginAndGetCookies(OTHER_COACH_EMAIL);
        insertBooking(coachProfileId, PLAYER_ID, "COMPLETED", Instant.now().minus(Duration.ofHours(2)));

        for (String path : READ_PATHS) {
            assertStatus(path, cookies, HttpStatus.FORBIDDEN);
        }
    }

    // Code review 2026-10-23: every read path a parent may call (/radar/preferences and /targets are
    // coach-only), plus the timeline, which a parent always sees un-expired.
    private static final List<String> PARENT_READ_PATHS = List.of(
        "/exposure", "/narrative", "/neglected-skills", "/slu/coach-contributions", "/slu/skill-trends",
        "/radar/display", "/reports");

    @Test
    void parentReads_neverWindowed() {
        String cookies = loginAndGetCookies(PARENT_EMAIL);
        insertBooking(coachProfileId, PLAYER_ID, "COMPLETED", Instant.now().minus(Duration.ofDays(400)));

        for (String path : PARENT_READ_PATHS) {
            assertThat(get(path, cookies).getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
        }
        assertThat(timelineExpired(cookies)).isFalse();
    }

    // AC6: the two tier-gated reads. ACADEMY so the tier gate passes and only the window decides.
    @Test
    void radarEntriesAndCorrelation_bookingEndJustInsideWindow_succeed_justOutside_are403() {
        insertSubscription(coachProfileId, "ACADEMY");
        String cookies = loginAndGetCookies(COACH_EMAIL);
        Instant now = Instant.now();

        UUID booking = insertBooking(coachProfileId, PLAYER_ID, "COMPLETED", now.minus(WINDOW).plus(MARGIN));
        for (String path : List.of("/radar/entries", "/radar/correlation")) {
            assertThat(get(path, cookies).getStatusCode()).as(path + " inside window").isEqualTo(HttpStatus.OK);
        }

        setBookingEnd(booking, now.minus(WINDOW).minus(MARGIN));
        for (String path : List.of("/radar/entries", "/radar/correlation")) {
            assertStatus(path, cookies, HttpStatus.FORBIDDEN);
        }
    }

    // ── AC6 / D12.10-A: writes ──────────────────────────────────────────────────────────────────

    @Test
    void writes_bookingEndJustInsideWindow_succeed_justOutside_are403() {
        insertSubscription(coachProfileId, "INSTRUCTOR");
        String cookies = loginAndGetCookies(COACH_EMAIL);
        Instant now = Instant.now();

        UUID booking = insertBooking(coachProfileId, PLAYER_ID, "COMPLETED", now.minus(WINDOW).plus(MARGIN));
        assertThat(send(HttpMethod.PUT, "/targets", cookies, targetsBody()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(send(HttpMethod.POST, "/radar/entries", cookies, assessmentBody()).getStatusCode())
            .isEqualTo(HttpStatus.NO_CONTENT);

        setBookingEnd(booking, now.minus(WINDOW).minus(MARGIN));
        assertSendStatus(HttpMethod.PUT, "/targets", cookies, targetsBody(), HttpStatus.FORBIDDEN);
        assertSendStatus(HttpMethod.POST, "/radar/entries", cookies, assessmentBody(), HttpStatus.FORBIDDEN);
    }

    // Code review 2026-10-23 (owner decision): the write window is measured over the narrow write statuses only.
    // A recent DISPUTED booking opens reads but must not authorise writes.
    @Test
    void writes_onlyAReadOnlyStatusIsRecent_are403_whileReadsSucceed() {
        insertSubscription(coachProfileId, "INSTRUCTOR");
        String cookies = loginAndGetCookies(COACH_EMAIL);
        Instant now = Instant.now();
        insertBooking(coachProfileId, PLAYER_ID, "COMPLETED", now.minus(Duration.ofDays(400)));
        insertBooking(coachProfileId, PLAYER_ID, "DISPUTED", now.minus(Duration.ofDays(1)));

        assertThat(get("/exposure", cookies).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertSendStatus(HttpMethod.PUT, "/targets", cookies, targetsBody(), HttpStatus.FORBIDDEN);
        assertSendStatus(HttpMethod.POST, "/radar/entries", cookies, assessmentBody(), HttpStatus.FORBIDDEN);
    }

    // ── AC1: preferences ────────────────────────────────────────────────────────────────────────

    @Test
    void preferences_unrelatedCoach_get403_put403_andNoRowIsCreated() {
        String cookies = loginAndGetCookies(OTHER_COACH_EMAIL);
        insertBooking(coachProfileId, PLAYER_ID, "COMPLETED", Instant.now().minus(Duration.ofHours(2)));

        assertStatus("/radar/preferences", cookies, HttpStatus.FORBIDDEN);
        assertThatThrownBy(() -> put("/radar/preferences", cookies, Map.of("selectedSkillCodes", List.of())))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

        Integer rows = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM development.coach_radar_preferences WHERE player_id = ?", Integer.class, PLAYER_ID);
        assertThat(rows).isZero();
    }

    @Test
    void preferences_relatedCoach_putSucceeds_andRowIsCreated() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        insertBooking(coachProfileId, PLAYER_ID, "COMPLETED", Instant.now().minus(Duration.ofHours(2)));

        ResponseEntity<Void> put = put("/radar/preferences", cookies, Map.of("selectedSkillCodes", List.of()));

        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        Integer rows = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM development.coach_radar_preferences WHERE player_id = ?", Integer.class, PLAYER_ID);
        assertThat(rows).isEqualTo(1);
    }

    // ── AC4/AC5: attended-session clock (timeline) ──────────────────────────────────────────────

    @Test
    void timeline_expiryIsMeasuredFromSessionEnd_notFromProcessingTime() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        Instant now = Instant.now();
        // Processed just now (occurred_at = now) but for a session that ended 100 days ago.
        UUID staleBooking = insertBooking(coachProfileId, PLAYER_ID, "COMPLETED", now.minus(Duration.ofDays(100)));
        insertSessionCompletedEvent(PLAYER_ID, coachProfileId, staleBooking, now);

        assertThat(timelineExpired(cookies)).isTrue();
    }

    @Test
    void timeline_aDelayedOlderSessionDoesNotShadowANewerOne() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        Instant now = Instant.now();
        UUID newer = insertBooking(coachProfileId, PLAYER_ID, "COMPLETED", now.minus(Duration.ofDays(10)));
        UUID older = insertBooking(coachProfileId, PLAYER_ID, "COMPLETED", now.minus(Duration.ofDays(100)));
        insertSessionCompletedEvent(PLAYER_ID, coachProfileId, newer, now.minus(Duration.ofDays(10)));
        // The OLDER session was processed LAST (delayed write) -- latest-processed would wrongly expire the coach.
        insertSessionCompletedEvent(PLAYER_ID, coachProfileId, older, now);

        assertThat(timelineExpired(cookies)).isFalse();
    }

    @Test
    void timeline_rowWrittenSecondsAfterARecentSession_isOpen() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        Instant now = Instant.now();
        UUID recent = insertBooking(coachProfileId, PLAYER_ID, "COMPLETED", now.minus(Duration.ofHours(1)));
        insertSessionCompletedEvent(PLAYER_ID, coachProfileId, recent, now);

        assertThat(timelineExpired(cookies)).isFalse();
    }

    @Test
    void timeline_sessionEndJustInsideWindow_isOpen_justOutside_isExpired() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        Instant now = Instant.now();
        UUID booking = insertBooking(coachProfileId, PLAYER_ID, "COMPLETED", now.minus(WINDOW).plus(MARGIN));
        insertSessionCompletedEvent(PLAYER_ID, coachProfileId, booking, now);

        assertThat(timelineExpired(cookies)).isFalse();

        setBookingEnd(booking, now.minus(WINDOW).minus(MARGIN));
        assertThat(timelineExpired(cookies)).isTrue();
    }

    // AC4, SLU side: SluRepository.findLastSessionEnd's join player_skill_stats -> session.sessions ->
    // booking.bookings, against Postgres. No timeline row, so the SLU clock alone decides.
    @Test
    void timeline_sluOnly_delayedWriteForAnOldSession_isExpired() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        Instant now = Instant.now();
        UUID staleBooking = insertBooking(coachProfileId, PLAYER_ID, "COMPLETED", now.minus(Duration.ofDays(100)));
        insertSluRow(PLAYER_ID, coachProfileId, insertSession(staleBooking, PLAYER_ID, coachProfileId), now);

        assertThat(timelineExpired(cookies)).isTrue();
    }

    @Test
    void timeline_sluOnly_rowWrittenSecondsAfterARecentSession_isOpen() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        Instant now = Instant.now();
        UUID recent = insertBooking(coachProfileId, PLAYER_ID, "COMPLETED", now.minus(Duration.ofHours(1)));
        insertSluRow(PLAYER_ID, coachProfileId, insertSession(recent, PLAYER_ID, coachProfileId), now);

        assertThat(timelineExpired(cookies)).isFalse();
    }

    // session_id is nullable: an unresolvable SLU row falls back to its own calculated_at.
    @Test
    void timeline_sluRowWithoutSession_fallsBackToCalculatedAt() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        Instant now = Instant.now();
        insertBooking(coachProfileId, PLAYER_ID, "COMPLETED", now.minus(Duration.ofDays(100)));
        insertSluRow(PLAYER_ID, coachProfileId, null, now.minus(Duration.ofDays(100)));
        assertThat(timelineExpired(cookies)).as("calculated_at 100 days ago").isTrue();

        insertSluRow(PLAYER_ID, coachProfileId, null, now);
        assertThat(timelineExpired(cookies)).as("calculated_at now").isFalse();
    }

    @Test
    void timeline_bookedButNeverAttended_isExpired_evenWithFutureBooking() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        insertBooking(coachProfileId, PLAYER_ID, "CONFIRMED", Instant.now().plus(Duration.ofDays(3)));

        // D12.10-D: reads open on a booking, the timeline stays attended-session-only.
        assertThat(timelineExpired(cookies)).isTrue();
        assertThat(get("/exposure", cookies).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── AC7: roster ─────────────────────────────────────────────────────────────────────────────

    @Test
    void roster_flagsLapsedPlayers_andDropsBeyondTheFloor() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        Instant now = Instant.now();
        insertBooking(coachProfileId, PLAYER_ID, "COMPLETED", now.minus(Duration.ofDays(100)));
        insertBooking(coachProfileId, SECOND_PLAYER_ID, "COMPLETED", now.minus(Duration.ofDays(5)));

        Map<?, ?> body = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/development/coaches/me/roster", HttpMethod.GET, null,
            authenticatedHeaders(cookies), Map.class).getBody();
        List<Map<String, Object>> players = (List<Map<String, Object>>) body.get("players");

        assertThat(players).hasSize(2);
        // open first, then the lapsed group
        assertThat(players.get(0).get("playerName")).isEqualTo("Second Player");
        assertThat(players.get(0).get("accessExpired")).isEqualTo(false);
        assertThat(players.get(1).get("playerName")).isEqualTo("Window Player");
        assertThat(players.get(1).get("accessExpired")).isEqualTo(true);
        assertThat(((Number) players.get(1).get("openNeglectedSkillCount")).intValue()).isZero();
    }

    @Test
    void roster_accessExpired_bookingEndJustInsideWindow_isFalse_justOutside_isTrue() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        Instant now = Instant.now();
        UUID booking = insertBooking(coachProfileId, PLAYER_ID, "COMPLETED", now.minus(WINDOW).plus(MARGIN));

        assertThat(rosterPlayers(cookies).get(0).get("accessExpired")).isEqualTo(false);

        setBookingEnd(booking, now.minus(WINDOW).minus(MARGIN));
        assertThat(rosterPlayers(cookies).get(0).get("accessExpired")).isEqualTo(true);
    }

    @Test
    void roster_playerBeyondTheRecencyFloor_isNotListed() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        insertBooking(coachProfileId, PLAYER_ID, "COMPLETED", Instant.now().minus(Duration.ofDays(800)));

        Map<?, ?> body = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/development/coaches/me/roster", HttpMethod.GET, null,
            authenticatedHeaders(cookies), Map.class).getBody();

        assertThat((List<?>) body.get("players")).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    // String body: several of these endpoints return JSON arrays, not objects.
    private ResponseEntity<String> get(String path, String cookies) {
        return httpTestClient.makeHttpRequest(
            baseUrl() + "/api/development/players/" + PLAYER_ID + path,
            HttpMethod.GET, null, authenticatedHeaders(cookies), String.class);
    }

    private ResponseEntity<Void> put(String path, String cookies, Map<String, Object> body) {
        return httpTestClient.makeHttpRequest(
            baseUrl() + "/api/development/players/" + PLAYER_ID + path,
            HttpMethod.PUT, body, authenticatedHeaders(cookies), Void.class);
    }

    // RestTemplate directly (as SkillExposureResourceIT.putTargets does): HttpTestClient only takes Map bodies,
    // and PUT /targets takes a JSON array.
    private ResponseEntity<String> send(HttpMethod method, String path, String cookies, Object body) {
        return restTemplate.exchange(
            baseUrl() + "/api/development/players/" + PLAYER_ID + path,
            method, new HttpEntity<>(body, authenticatedHeaders(cookies)), String.class);
    }

    private void assertSendStatus(HttpMethod method, String path, String cookies, Object body, HttpStatus expected) {
        assertThatThrownBy(() -> send(method, path, cookies, body))
            .as(method + " " + path)
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode()).isEqualTo(expected));
    }

    private static List<Map<String, Object>> targetsBody() {
        return List.of(Map.of("skillCode", "PAC", "weeklyTargetSlu", 5));
    }

    // A fresh group id per call: (group, coach, skill) is unique, so reusing one would 409 on the second submit.
    private static Map<String, Object> assessmentBody() {
        return Map.of(
            "assessmentGroupId", UUID.randomUUID().toString(),
            "assessmentDate", LocalDate.now().minusDays(1).toString(),
            "assessmentType", "COACH_EVALUATION",
            "entries", List.of(Map.of("skillCode", "PAC", "score", 70)));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rosterPlayers(String cookies) {
        Map<?, ?> body = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/development/coaches/me/roster", HttpMethod.GET, null,
            authenticatedHeaders(cookies), Map.class).getBody();
        return (List<Map<String, Object>>) body.get("players");
    }

    private void assertStatus(String path, String cookies, HttpStatus expected) {
        assertThatThrownBy(() -> get(path, cookies))
            .as(path)
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode()).isEqualTo(expected));
    }

    private boolean timelineExpired(String cookies) {
        Map<?, ?> body = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/development/players/" + PLAYER_ID + "/timeline",
            HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class).getBody();
        return Boolean.TRUE.equals(body.get("accessExpired"));
    }

    /** One Instant base for start AND end (chk_bkg_end_after_start): start = end - 1h. */
    private UUID insertBooking(UUID coachId, Long playerId, String status, Instant end) {
        UUID bookingId = UUID.randomUUID();
        transactionTemplate.execute(txStatus -> {
            jdbcTemplate.update(
                "INSERT INTO booking.bookings " +
                "(id, parent_id, player_id, coach_id, requested_start_time, requested_end_time, status, " +
                " canonical_timezone, version, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, 'Europe/Berlin', 0, ?, ?)",
                bookingId, PARENT_USER_ID, playerId, coachId,
                Timestamp.from(end.minus(Duration.ofHours(1))), Timestamp.from(end),
                status, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
            return null;
        });
        return bookingId;
    }

    private void setBookingEnd(UUID bookingId, Instant end) {
        transactionTemplate.execute(txStatus -> {
            jdbcTemplate.update(
                "UPDATE booking.bookings SET requested_start_time = ?, requested_end_time = ? WHERE id = ?",
                Timestamp.from(end.minus(Duration.ofHours(1))), Timestamp.from(end), bookingId);
            return null;
        });
    }

    private void insertSessionCompletedEvent(long playerId, UUID coachId, UUID bookingId, Instant occurredAt) {
        transactionTemplate.execute(txStatus -> {
            jdbcTemplate.update(
                "INSERT INTO development.player_timeline_events " +
                "(id, player_id, event_type, reference_id, reference_module, occurred_at, coach_id) " +
                "VALUES (?, ?, 'SESSION_COMPLETED', ?, 'booking', ?, ?)",
                UUID.randomUUID(), playerId, bookingId, Timestamp.from(occurredAt), coachId);
            return null;
        });
    }

    private UUID insertSession(UUID bookingId, long playerId, UUID coachId) {
        UUID sessionId = UUID.randomUUID();
        transactionTemplate.execute(txStatus -> {
            jdbcTemplate.update(
                "INSERT INTO session.sessions (id, booking_id, coach_id, player_id, status) " +
                "VALUES (?, ?, ?, ?, 'COMPLETED')",
                sessionId, bookingId, coachId, playerId);
            return null;
        });
        return sessionId;
    }

    private void insertSluRow(long playerId, UUID coachId, UUID sessionIdOrNull, Instant calculatedAt) {
        transactionTemplate.execute(txStatus -> {
            jdbcTemplate.update(
                "INSERT INTO development.player_skill_stats " +
                "(id, player_id, session_id, coach_id, skill_code, slu_value, calculated_at) " +
                "VALUES (gen_random_uuid(), ?, ?, ?, 'PAC', 10.0, ?)",
                playerId, sessionIdOrNull, coachId, Timestamp.from(calculatedAt));
            return null;
        });
    }

    private void insertSubscription(UUID coachId, String tier) {
        transactionTemplate.execute(txStatus -> {
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_subscriptions (coach_id, tier, active_since) VALUES (?, ?, ?)",
                coachId, tier, Timestamp.from(Instant.now()));
            return null;
        });
    }

    private String loginAndGetCookies(String email) {
        ResponseEntity<Map> loginResponse = httpTestClient.makeHttpRequest(
            baseUrl() + LOGIN_ENDPOINT,
            HttpMethod.POST,
            Map.of("email", email, "password", TEST_PASSWORD),
            clientHeaders(),
            Map.class
        );
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> setCookies = loginResponse.getHeaders().get("Set-Cookie");
        assertThat(setCookies).isNotNull();
        return setCookies.stream()
            .map(c -> c.split(";")[0])
            .reduce((a, b) -> a + "; " + b)
            .orElseThrow();
    }

    private HttpHeaders clientHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(SecurityConstants.API_KEY_HEADER, CLIENT_ID);
        return headers;
    }

    private HttpHeaders authenticatedHeaders(String cookieValue) {
        HttpHeaders headers = clientHeaders();
        headers.add(HttpHeaders.COOKIE, cookieValue);
        return headers;
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
