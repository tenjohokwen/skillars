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
class DisputeSubmissionIT {

    private static final String LOGIN_ENDPOINT   = "/api/auth/login";
    private static final String DISPUTES_BASE    = "/api/disputes";
    private static final String CLIENT_ID        = "testClientId";
    private static final String TEST_PASSWORD    = "TestPass@123!";

    private static final long ADMIN_ID        = 9100_000_100L;
    private static final long PARENT_ID       = 9100_000_001L;
    private static final long PLAYER_ID       = 9100_000_002L;
    private static final long COACH_USER_ID   = 9100_000_010L;
    private static final long OTHER_PARENT_ID = 9100_000_003L;

    private static final String ADMIN_EMAIL        = "admin.disp9100@skillars-test.com";
    private static final String PARENT_EMAIL       = "parent.disp9100@skillars-test.com";
    private static final String PLAYER_EMAIL       = "player.disp9100@skillars-test.com";
    private static final String OTHER_PARENT_EMAIL = "parent2.disp9100@skillars-test.com";
    private static final String COACH_EMAIL        = "coach.disp9100@skillars-test.com";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private PasswordEncoder passwordEncoder;

    @LocalServerPort private int randomServerPort;

    private UUID coachProfileId;
    private UUID bookingId;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        coachProfileId = UUID.randomUUID();
        bookingId = UUID.randomUUID();

        transactionTemplate.execute(status -> {
            insertAuthority(9100, "ROLE_PARENT");
            insertAuthority(9101, "ROLE_COACH");
            insertAuthority(9102, "ROLE_ADMIN");
            insertAuthority(9103, "ROLE_PLAYER");

            insertUser(PARENT_ID, PARENT_EMAIL, passwordHash, "PARENT");
            grantAuthority(PARENT_ID, "ROLE_PARENT");

            insertUser(PLAYER_ID, PLAYER_EMAIL, passwordHash, "PLAYER");
            grantAuthority(PLAYER_ID, "ROLE_PLAYER");

            insertUser(OTHER_PARENT_ID, OTHER_PARENT_EMAIL, passwordHash, "PARENT");
            grantAuthority(OTHER_PARENT_ID, "ROLE_PARENT");

            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH");
            grantAuthority(COACH_USER_ID, "ROLE_COACH");

            insertUser(ADMIN_ID, ADMIN_EMAIL, passwordHash, "ADMIN");
            grantAuthority(ADMIN_ID, "ROLE_ADMIN");

            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles (id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, 'Disp Coach', 'Bio', 'London', ARRAY['English']::varchar[], 'Europe/London', 'ACTIVE')",
                coachProfileId, COACH_USER_ID);

            // COMPLETED booking within submission window (2 days ago)
            insertBooking(bookingId, "COMPLETED", Instant.now().minusSeconds(2 * 86400));

            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM admin.disputes WHERE booking_id = ?", bookingId);
            jdbcTemplate.update("DELETE FROM admin.admin_alerts WHERE reference_id = ?", bookingId.toString());
            jdbcTemplate.update("DELETE FROM booking.bookings WHERE coach_id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id = ?", coachProfileId);
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?, ?, ?, ?)",
                PARENT_ID, PLAYER_ID, OTHER_PARENT_ID, COACH_USER_ID, ADMIN_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?, ?, ?, ?)",
                PARENT_ID, PLAYER_ID, OTHER_PARENT_ID, COACH_USER_ID, ADMIN_ID);
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id IN (9100, 9101, 9102, 9103)");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void raiseDispute_eligible_returns201WithDisputeId() {
        String cookies = loginAndGetCookies(PARENT_EMAIL);
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + DISPUTES_BASE,
            HttpMethod.POST,
            Map.of("bookingId", bookingId.toString(), "reason", "COACH_NO_SHOW", "details", "Coach did not show up"),
            authenticatedHeaders(cookies),
            Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsKey("disputeId");

        String disputeId = (String) response.getBody().get("disputeId");
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM admin.disputes WHERE id = ?::uuid AND status = 'OPEN'",
            Integer.class, disputeId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void raiseDispute_ineligible_notOwner_returns403() {
        String cookies = loginAndGetCookies(OTHER_PARENT_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + DISPUTES_BASE,
            HttpMethod.POST,
            Map.of("bookingId", bookingId.toString(), "reason", "OTHER", "details", "Not my booking"),
            authenticatedHeaders(cookies),
            Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(ex.getResponseBodyAsString()).contains("disputes.notEligible");
            });
    }

    @Test
    void raiseDispute_windowExpired_returns403() {
        // Set booking's updated_at to 15 days ago (outside 14-day window)
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "UPDATE booking.bookings SET updated_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(15 * 86400L)), bookingId);
            return null;
        });

        String cookies = loginAndGetCookies(PARENT_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + DISPUTES_BASE,
            HttpMethod.POST,
            Map.of("bookingId", bookingId.toString(), "reason", "COACH_NO_SHOW", "details", "Late dispute"),
            authenticatedHeaders(cookies),
            Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(ex.getResponseBodyAsString()).contains("disputes.windowExpired");
            });
    }

    @Test
    void raiseDispute_duplicateOpenDispute_returns409() {
        String cookies = loginAndGetCookies(PARENT_EMAIL);
        Map<String, Object> body = Map.of(
            "bookingId", bookingId.toString(), "reason", "SESSION_QUALITY", "details", "Bad session");

        httpTestClient.makeHttpRequest(
            baseUrl() + DISPUTES_BASE, HttpMethod.POST, body, authenticatedHeaders(cookies), Map.class);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + DISPUTES_BASE, HttpMethod.POST, body, authenticatedHeaders(cookies), Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void raiseDispute_bookingInWrongStatus_returns403() {
        UUID requestedBookingId = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            insertBooking(requestedBookingId, "REQUESTED", Instant.now().minusSeconds(86400));
            return null;
        });

        String cookies = loginAndGetCookies(PARENT_EMAIL);
        try {
            assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
                baseUrl() + DISPUTES_BASE,
                HttpMethod.POST,
                Map.of("bookingId", requestedBookingId.toString(), "reason", "OTHER", "details", "Wrong status"),
                authenticatedHeaders(cookies),
                Map.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> {
                    HttpClientErrorException ex = (HttpClientErrorException) e;
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ex.getResponseBodyAsString()).contains("disputes.notEligible");
                });
        } finally {
            jdbcTemplate.update("DELETE FROM booking.bookings WHERE id = ?", requestedBookingId);
        }
    }

    @Test
    void getDispute_ownDispute_returns200WithDetails() {
        String cookies = loginAndGetCookies(PARENT_EMAIL);
        ResponseEntity<Map> createResp = httpTestClient.makeHttpRequest(
            baseUrl() + DISPUTES_BASE,
            HttpMethod.POST,
            Map.of("bookingId", bookingId.toString(), "reason", "UNAUTHORISED_CHARGE", "details", "Charged twice"),
            authenticatedHeaders(cookies),
            Map.class);

        String disputeId = (String) createResp.getBody().get("disputeId");
        ResponseEntity<Map> getResp = httpTestClient.makeHttpRequest(
            baseUrl() + DISPUTES_BASE + "/" + disputeId,
            HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(getResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = getResp.getBody();
        assertThat(body.get("disputeId")).isEqualTo(disputeId);
        assertThat(body.get("reason")).isEqualTo("UNAUTHORISED_CHARGE");
        assertThat(body.get("status")).isEqualTo("OPEN");
        assertThat(body.keySet()).containsExactlyInAnyOrder(
            "disputeId", "bookingId", "reason", "details", "status", "resolution", "resolutionNote", "createdAt");
    }

    @Test
    void getDispute_otherUsersDispute_returns403() {
        String parentCookies = loginAndGetCookies(PARENT_EMAIL);
        ResponseEntity<Map> createResp = httpTestClient.makeHttpRequest(
            baseUrl() + DISPUTES_BASE,
            HttpMethod.POST,
            Map.of("bookingId", bookingId.toString(), "reason", "SAFETY_CONCERN", "details", "Unsafe"),
            authenticatedHeaders(parentCookies),
            Map.class);

        String disputeId = (String) createResp.getBody().get("disputeId");
        String otherCookies = loginAndGetCookies(OTHER_PARENT_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + DISPUTES_BASE + "/" + disputeId,
            HttpMethod.GET, null, authenticatedHeaders(otherCookies), Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void raiseDispute_player_eligible_returns201WithPlayerRole() {
        String playerCookies = loginAndGetCookies(PLAYER_EMAIL);
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + DISPUTES_BASE,
            HttpMethod.POST,
            Map.of("bookingId", bookingId.toString(), "reason", "COACH_NO_SHOW", "details", "Player: coach did not show"),
            authenticatedHeaders(playerCookies),
            Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsKey("disputeId");

        String disputeId = (String) response.getBody().get("disputeId");
        String raisedByRole = jdbcTemplate.queryForObject(
            "SELECT raised_by_role FROM admin.disputes WHERE id = ?::uuid", String.class, disputeId);
        assertThat(raisedByRole).isEqualTo("PLAYER");
    }

    @Test
    void getDispute_playerOwned_returns200() {
        String playerCookies = loginAndGetCookies(PLAYER_EMAIL);
        ResponseEntity<Map> createResp = httpTestClient.makeHttpRequest(
            baseUrl() + DISPUTES_BASE,
            HttpMethod.POST,
            Map.of("bookingId", bookingId.toString(), "reason", "SESSION_QUALITY", "details", "Player: session was poor"),
            authenticatedHeaders(playerCookies),
            Map.class);

        String disputeId = (String) createResp.getBody().get("disputeId");
        ResponseEntity<Map> getResp = httpTestClient.makeHttpRequest(
            baseUrl() + DISPUTES_BASE + "/" + disputeId,
            HttpMethod.GET, null, authenticatedHeaders(playerCookies), Map.class);

        assertThat(getResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResp.getBody().get("reason")).isEqualTo("SESSION_QUALITY");
        assertThat(getResp.getBody().get("status")).isEqualTo("OPEN");
    }

    @Test
    void raiseDispute_eligible_createsAdminAlert() {
        String cookies = loginAndGetCookies(PARENT_EMAIL);
        httpTestClient.makeHttpRequest(
            baseUrl() + DISPUTES_BASE,
            HttpMethod.POST,
            Map.of("bookingId", bookingId.toString(), "reason", "COACH_NO_SHOW", "details", "No show"),
            authenticatedHeaders(cookies),
            Map.class);

        Integer alertCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM admin.admin_alerts " +
            "WHERE type = 'DISPUTE_RAISED' AND reference_id = ? AND reference_type = 'BOOKING' AND status = 'OPEN'",
            Integer.class, bookingId.toString());
        assertThat(alertCount).isEqualTo(1);
    }

    // ── helpers ──

    private void insertBooking(UUID id, String status, Instant updatedAt) {
        jdbcTemplate.update(
            "INSERT INTO booking.bookings " +
            "(id, coach_id, parent_id, player_id, status, requested_start_time, requested_end_time, " +
            "version, created_at, updated_at, canonical_timezone) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?, 'Europe/London')",
            id, coachProfileId, PARENT_ID, PLAYER_ID, status,
            Timestamp.from(updatedAt.minusSeconds(3600)),
            Timestamp.from(updatedAt),
            Timestamp.from(updatedAt.minusSeconds(7200)),
            Timestamp.from(updatedAt));
    }

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
            "910" + (id % 10000000),
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
