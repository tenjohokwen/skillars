package com.softropic.skillars.platform.admin.api;

import com.softropic.skillars.config.TestConfig;
import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.SecurityIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestConfig.class)
@TestPropertySource(properties = {
    "spring.cloud.compatibility-verifier.enabled=false",
    "rate.limiting.enabled=false",
    "allowed.clients=testClientId"
})
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class CoachSuspensionIT {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String CLIENT_ID      = "testClientId";
    private static final String TEST_PASSWORD  = "TestPass@123!";

    private static final long ADMIN_ID       = 9040_000_100L;
    private static final long PARENT_ID      = 9040_000_001L;
    private static final long PLAYER_ID      = 9040_000_002L;
    private static final long COACH_USER_ID  = 9040_000_010L;

    private static final String ADMIN_EMAIL  = "admin.suspension9040@skillars-test.com";
    private static final String PARENT_EMAIL = "parent.suspension9040@skillars-test.com";
    private static final String COACH_EMAIL  = "coach.suspension9040@skillars-test.com";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private PasswordEncoder passwordEncoder;

    @LocalServerPort private int randomServerPort;

    private UUID coachProfileId;
    private UUID bookingId1;
    private UUID bookingId2;
    private UUID acceptedBookingId;
    private UUID strikeAlertId;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        coachProfileId = UUID.randomUUID();
        bookingId1 = UUID.randomUUID();
        bookingId2 = UUID.randomUUID();
        acceptedBookingId = UUID.randomUUID();
        strikeAlertId = UUID.randomUUID();

        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) VALUES (9040, 'ROLE_PARENT', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) VALUES (9041, 'ROLE_COACH', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) VALUES (9042, 'ROLE_ADMIN', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));

            insertUser(PARENT_ID, PARENT_EMAIL, passwordHash, "PARENT");
            grantAuthority(PARENT_ID, "ROLE_PARENT");

            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH");
            grantAuthority(COACH_USER_ID, "ROLE_COACH");

            insertUser(ADMIN_ID, ADMIN_EMAIL, passwordHash, "ADMIN");
            grantAuthority(ADMIN_ID, "ROLE_ADMIN");

            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles (id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, 'Suspension Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
                coachProfileId, COACH_USER_ID);

            Instant futureStart = Instant.now().plusSeconds(3600);
            Instant futureEnd = futureStart.plusSeconds(3600);

            jdbcTemplate.update(
                "INSERT INTO booking.bookings (id, parent_id, player_id, coach_id, requested_start_time, requested_end_time, status, canonical_timezone, version, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, 'REQUESTED', 'Europe/Berlin', 0, ?, ?)",
                bookingId1, PARENT_ID, PLAYER_ID, coachProfileId,
                Timestamp.from(futureStart), Timestamp.from(futureEnd),
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));

            jdbcTemplate.update(
                "INSERT INTO booking.bookings (id, parent_id, player_id, coach_id, requested_start_time, requested_end_time, status, canonical_timezone, version, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, 'REQUESTED', 'Europe/Berlin', 0, ?, ?)",
                bookingId2, PARENT_ID, PLAYER_ID, coachProfileId,
                Timestamp.from(futureStart.plusSeconds(7200)), Timestamp.from(futureEnd.plusSeconds(7200)),
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));

            jdbcTemplate.update(
                "INSERT INTO booking.bookings (id, parent_id, player_id, coach_id, requested_start_time, requested_end_time, status, canonical_timezone, version, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, 'ACCEPTED', 'Europe/Berlin', 0, ?, ?)",
                acceptedBookingId, PARENT_ID, PLAYER_ID, coachProfileId,
                Timestamp.from(futureStart.plusSeconds(14400)), Timestamp.from(futureEnd.plusSeconds(14400)),
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));

            jdbcTemplate.update(
                "INSERT INTO admin.admin_alerts (alert_id, type, reference_id, reference_type, status, created_at) " +
                "VALUES (?, 'STRIKE_THRESHOLD', ?, 'COACH', 'OPEN', ?)",
                strikeAlertId, coachProfileId.toString(), Timestamp.from(Instant.now()));

            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM admin.admin_action_log WHERE reference_id = ?", coachProfileId.toString());
            jdbcTemplate.update("DELETE FROM admin.admin_alerts WHERE alert_id = ?", strikeAlertId);
            jdbcTemplate.update("DELETE FROM booking.bookings WHERE coach_id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id = ?", coachProfileId);
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?, ?)", PARENT_ID, COACH_USER_ID, ADMIN_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?, ?)", PARENT_ID, COACH_USER_ID, ADMIN_ID);
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id IN (9040, 9041, 9042)");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void suspendCoach_setsStatusAndCancelsRequestedBookings() {
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        ResponseEntity<Void> resp = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/admin/coaches/" + coachProfileId + "/suspend",
            HttpMethod.POST,
            Map.of("reason", "Repeated misconduct", "notifyCoach", false),
            authenticatedHeaders(adminCookies), Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM marketplace.coach_profiles WHERE id = ?", String.class, coachProfileId);
        assertThat(status).isEqualTo("SUSPENDED");

        Instant statusChangedAt = jdbcTemplate.queryForObject(
            "SELECT status_changed_at FROM marketplace.coach_profiles WHERE id = ?", Instant.class, coachProfileId);
        assertThat(statusChangedAt).isNotNull();

        List<String> cancelledStatuses = jdbcTemplate.queryForList(
            "SELECT status FROM booking.bookings WHERE coach_id = ? AND id IN (?, ?)",
            String.class, coachProfileId, bookingId1, bookingId2);
        assertThat(cancelledStatuses).containsOnly("CANCELLED");

        List<String> cancelReasons = jdbcTemplate.queryForList(
            "SELECT cancel_reason FROM booking.bookings WHERE coach_id = ? AND id IN (?, ?)",
            String.class, coachProfileId, bookingId1, bookingId2);
        assertThat(cancelReasons).containsOnly("COACH_SUSPENDED_BY_ADMIN");

        String acceptedStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM booking.bookings WHERE id = ?", String.class, acceptedBookingId);
        assertThat(acceptedStatus).isEqualTo("ACCEPTED");

        Long logCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM admin.admin_action_log WHERE reference_id = ? AND action_type = 'COACH_SUSPEND'",
            Long.class, coachProfileId.toString());
        assertThat(logCount).isEqualTo(1L);
    }

    @Test
    void suspendedCoachPublicProfile_returns404() {
        transactionTemplate.execute(status ->
            jdbcTemplate.update("UPDATE marketplace.coach_profiles SET status = 'SUSPENDED' WHERE id = ?", coachProfileId));

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/marketplace/coaches/" + coachProfileId,
            HttpMethod.GET, null, clientHeaders(), Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void nonAdminCannotSuspend_returns403() {
        String parentCookies = loginAndGetCookies(PARENT_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/admin/coaches/" + coachProfileId + "/suspend",
            HttpMethod.POST,
            Map.of("reason", "Test", "notifyCoach", false),
            authenticatedHeaders(parentCookies), Void.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void suspendIdempotent_doubleCallDoesNotDuplicateLog() {
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        String suspendUrl = baseUrl() + "/api/admin/coaches/" + coachProfileId + "/suspend";
        Map<String, Object> body = Map.of("reason", "Test suspend", "notifyCoach", false);

        httpTestClient.makeHttpRequest(suspendUrl, HttpMethod.POST, body, authenticatedHeaders(adminCookies), Void.class);
        httpTestClient.makeHttpRequest(suspendUrl, HttpMethod.POST, body, authenticatedHeaders(adminCookies), Void.class);

        Long logCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM admin.admin_action_log WHERE reference_id = ? AND action_type = 'COACH_SUSPEND'",
            Long.class, coachProfileId.toString());
        assertThat(logCount).isEqualTo(1L);
    }

    // ── helpers ──

    private String loginAndGetCookies(String email) {
        ResponseEntity<Map> loginResponse = httpTestClient.makeHttpRequest(
            baseUrl() + LOGIN_ENDPOINT,
            HttpMethod.POST,
            Map.of("email", email, "password", TEST_PASSWORD),
            clientHeaders(), Map.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> setCookies = loginResponse.getHeaders().get("Set-Cookie");
        assertThat(setCookies).isNotNull();
        return setCookies.stream().map(c -> c.split(";")[0]).reduce((a, b) -> a + "; " + b).orElseThrow();
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

    private String baseUrl() {
        return "http://localhost:" + randomServerPort;
    }

    private void insertUser(long id, String email, String passwordHash, String role) {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" (id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, activated, locked, login, login_id_type, password_hash, otp_enabled, skillars_role, verification_status) VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, 'ACTIVE', '1985-06-01', ?, 'Test', 'OTHER', 'en', ?, 'DE', ?, true, false, ?, 'EMAIL', ?, false, ?, 'BASIC_VERIFIED')",
            id, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            email, role, "904" + (id % 10000000), email, passwordHash, role);
    }

    private void grantAuthority(long userId, String roleName) {
        jdbcTemplate.update(
            "INSERT INTO main.user_authority (user_id, authority_id) VALUES (?, (SELECT id FROM main.authority WHERE name = ?)) ON CONFLICT DO NOTHING",
            userId, roleName);
    }
}
