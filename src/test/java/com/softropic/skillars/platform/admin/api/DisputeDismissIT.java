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
class DisputeDismissIT {

    private static final String LOGIN_ENDPOINT   = "/api/auth/login";
    private static final String ADMIN_DISP_BASE  = "/api/admin/disputes";
    private static final String CLIENT_ID        = "testClientId";
    private static final String TEST_PASSWORD    = "TestPass@123!";

    private static final long ADMIN_ID      = 9120_000_100L;
    private static final long PARENT_ID     = 9120_000_001L;
    private static final long PLAYER_ID     = 9120_000_002L;
    private static final long COACH_USER_ID = 9120_000_010L;

    private static final String ADMIN_EMAIL  = "admin.dismiss9120@skillars-test.com";
    private static final String PARENT_EMAIL = "parent.dismiss9120@skillars-test.com";
    private static final String COACH_EMAIL  = "coach.dismiss9120@skillars-test.com";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private PasswordEncoder passwordEncoder;

    @LocalServerPort private int randomServerPort;

    private UUID coachProfileId;
    private UUID bookingId;
    private UUID disputeId;
    private UUID alertId;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        coachProfileId = UUID.randomUUID();
        bookingId = UUID.randomUUID();
        disputeId = UUID.randomUUID();
        alertId = UUID.randomUUID();

        transactionTemplate.execute(status -> {
            insertAuthority(9120, "ROLE_PARENT");
            insertAuthority(9121, "ROLE_COACH");
            insertAuthority(9122, "ROLE_ADMIN");

            insertUser(PARENT_ID, PARENT_EMAIL, passwordHash, "PARENT");
            grantAuthority(PARENT_ID, "ROLE_PARENT");

            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH");
            grantAuthority(COACH_USER_ID, "ROLE_COACH");

            insertUser(ADMIN_ID, ADMIN_EMAIL, passwordHash, "ADMIN");
            grantAuthority(ADMIN_ID, "ROLE_ADMIN");

            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles (id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, 'Dismiss Coach', 'Bio', 'Paris', ARRAY['French']::varchar[], 'Europe/Paris', 'ACTIVE')",
                coachProfileId, COACH_USER_ID);

            jdbcTemplate.update(
                "INSERT INTO booking.bookings " +
                "(id, coach_id, parent_id, player_id, status, requested_start_time, requested_end_time, " +
                "version, created_at, updated_at, canonical_timezone) " +
                "VALUES (?, ?, ?, ?, 'COMPLETED', ?, ?, 0, ?, ?, 'Europe/Paris')",
                bookingId, coachProfileId, PARENT_ID, PLAYER_ID,
                Timestamp.from(Instant.now().minusSeconds(7200)),
                Timestamp.from(Instant.now().minusSeconds(3600)),
                Timestamp.from(Instant.now().minusSeconds(86400)),
                Timestamp.from(Instant.now().minusSeconds(86400)));

            jdbcTemplate.update(
                "INSERT INTO admin.disputes (id, booking_id, raised_by, raised_by_role, reason, details, status, version) " +
                "VALUES (?, ?, ?, 'PARENT', 'OTHER', 'Frivolous claim', 'OPEN', 0)",
                disputeId, bookingId, PARENT_ID);

            jdbcTemplate.update(
                "INSERT INTO admin.admin_alerts (alert_id, type, reference_id, reference_type, status, created_at) " +
                "VALUES (?, 'DISPUTE_RAISED', ?, 'BOOKING', 'OPEN', ?)",
                alertId, bookingId.toString(), Timestamp.from(Instant.now()));

            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM admin.admin_action_log WHERE reference_id = ?", disputeId.toString());
            jdbcTemplate.update("DELETE FROM admin.admin_alerts WHERE alert_id = ?", alertId);
            jdbcTemplate.update("DELETE FROM admin.disputes WHERE id = ?", disputeId);
            jdbcTemplate.update("DELETE FROM payment.parent_credit_ledger WHERE reference_id = ?", bookingId);
            jdbcTemplate.update("DELETE FROM booking.bookings WHERE id = ?", bookingId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id = ?", coachProfileId);
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?, ?)",
                PARENT_ID, COACH_USER_ID, ADMIN_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?, ?)",
                PARENT_ID, COACH_USER_ID, ADMIN_ID);
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id IN (9120, 9121, 9122)");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void dismissDispute_setsDismissedAndResolvesAlert() {
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        ResponseEntity<Void> resp = httpTestClient.makeHttpRequest(
            baseUrl() + ADMIN_DISP_BASE + "/" + disputeId + "/dismiss",
            HttpMethod.POST,
            Map.of("reason", "Frivolous — no evidence provided"),
            authenticatedHeaders(adminCookies),
            Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        String disputeStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM admin.disputes WHERE id = ?", String.class, disputeId);
        assertThat(disputeStatus).isEqualTo("DISMISSED");

        Timestamp resolvedAt = jdbcTemplate.queryForObject(
            "SELECT resolved_at FROM admin.disputes WHERE id = ?", Timestamp.class, disputeId);
        assertThat(resolvedAt).isNotNull();

        Long resolvedBy = jdbcTemplate.queryForObject(
            "SELECT resolved_by FROM admin.disputes WHERE id = ?", Long.class, disputeId);
        assertThat(resolvedBy).isEqualTo(ADMIN_ID);

        String resolutionNote = jdbcTemplate.queryForObject(
            "SELECT resolution_note FROM admin.disputes WHERE id = ?", String.class, disputeId);
        assertThat(resolutionNote).isEqualTo("Frivolous — no evidence provided");

        String alertStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM admin.admin_alerts WHERE alert_id = ?", String.class, alertId);
        assertThat(alertStatus).isEqualTo("RESOLVED");

        Integer logCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM admin.admin_action_log WHERE reference_id = ? AND action_type = 'DISPUTE_RESOLVE'",
            Integer.class, disputeId.toString());
        assertThat(logCount).isEqualTo(1);

        Integer ledgerCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment.parent_credit_ledger WHERE reference_id = ?",
            Integer.class, bookingId);
        assertThat(ledgerCount).isEqualTo(0);
    }

    @Test
    void dismissNonAdminRole_returns403() {
        String parentCookies = loginAndGetCookies(PARENT_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + ADMIN_DISP_BASE + "/" + disputeId + "/dismiss",
            HttpMethod.POST,
            Map.of("reason", "Trying to dismiss my own dispute"),
            authenticatedHeaders(parentCookies),
            Void.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void dismissAlreadyDismissed_returns409() {
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        Map<String, Object> body = Map.of("reason", "Frivolous");

        httpTestClient.makeHttpRequest(
            baseUrl() + ADMIN_DISP_BASE + "/" + disputeId + "/dismiss",
            HttpMethod.POST, body, authenticatedHeaders(adminCookies), Void.class);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + ADMIN_DISP_BASE + "/" + disputeId + "/dismiss",
            HttpMethod.POST, body, authenticatedHeaders(adminCookies), Void.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT));
    }

    // ── helpers ──

    private void insertAuthority(int id, String name) {
        jdbcTemplate.update(
            "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
            "VALUES (?, ?, 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
            id, name, Timestamp.from(Instant.now()));
    }

    private void insertUser(long id, String email, String passwordHash, String role) {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" " +
            "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, " +
            "status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, " +
            "activated, locked, login, login_id_type, password_hash, otp_enabled, " +
            "skillars_role, verification_status) " +
            "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, " +
            "'ACTIVE', '1985-06-01', ?, 'Test', 'OTHER', 'en', ?, 'DE', ?, " +
            "true, false, ?, 'EMAIL', ?, false, ?, 'BASIC_VERIFIED')",
            id, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            email, role,
            "912" + (id % 10000000),
            email, passwordHash, role);
    }

    private void grantAuthority(long userId, String roleName) {
        jdbcTemplate.update(
            "INSERT INTO main.user_authority (user_id, authority_id) " +
            "VALUES (?, (SELECT id FROM main.authority WHERE name = ?)) ON CONFLICT DO NOTHING",
            userId, roleName);
    }

    private String loginAndGetCookies(String email) {
        ResponseEntity<Map> loginResponse = httpTestClient.makeHttpRequest(
            baseUrl() + LOGIN_ENDPOINT,
            HttpMethod.POST,
            Map.of("email", email, "password", TEST_PASSWORD),
            clientHeaders(),
            Map.class);
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

    private String baseUrl() {
        return "http://localhost:" + randomServerPort;
    }
}
