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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestConfig.class)
@TestPropertySource(properties = {
    "spring.cloud.compatibility-verifier.enabled=false",
    "rate.limiting.enabled=false",
    "allowed.clients=testClientId"
})
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class CoachEnforcementListIT {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String CLIENT_ID      = "testClientId";
    private static final String TEST_PASSWORD  = "TestPass@123!";

    private static final long ADMIN_ID         = 9080_000_100L;
    private static final long COACH_USER_ID_1  = 9080_000_010L;
    private static final long COACH_USER_ID_2  = 9080_000_020L;

    private static final String ADMIN_EMAIL   = "admin.enflist9080@skillars-test.com";
    private static final String COACH_EMAIL_1 = "coach1.enflist9080@skillars-test.com";
    private static final String COACH_EMAIL_2 = "coach2.enflist9080@skillars-test.com";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private PasswordEncoder passwordEncoder;

    @LocalServerPort private int randomServerPort;

    private UUID coachProfileId1;
    private UUID coachProfileId2;
    private UUID strikeId1;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        coachProfileId1 = UUID.randomUUID();
        coachProfileId2 = UUID.randomUUID();
        strikeId1 = UUID.randomUUID();

        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) VALUES (9080, 'ROLE_COACH', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) VALUES (9081, 'ROLE_ADMIN', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));

            insertUser(COACH_USER_ID_1, COACH_EMAIL_1, passwordHash, "COACH");
            grantAuthority(COACH_USER_ID_1, "ROLE_COACH");

            insertUser(COACH_USER_ID_2, COACH_EMAIL_2, passwordHash, "COACH");
            grantAuthority(COACH_USER_ID_2, "ROLE_COACH");

            insertUser(ADMIN_ID, ADMIN_EMAIL, passwordHash, "ADMIN");
            grantAuthority(ADMIN_ID, "ROLE_ADMIN");

            Instant earlier = Instant.now().minusSeconds(3600);
            Instant later   = Instant.now();

            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles (id, user_id, display_name, bio, city, languages, canonical_timezone, status, status_changed_at) " +
                "VALUES (?, ?, 'Enforcement Coach 1', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'PENDING_REVIEW', ?)",
                coachProfileId1, COACH_USER_ID_1, Timestamp.from(earlier));

            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles (id, user_id, display_name, bio, city, languages, canonical_timezone, status, status_changed_at) " +
                "VALUES (?, ?, 'Enforcement Coach 2', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'SUSPENDED', ?)",
                coachProfileId2, COACH_USER_ID_2, Timestamp.from(later));

            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_reliability_strikes (id, coach_id, booking_id, reason, created_at, acknowledged) VALUES (?, ?, gen_random_uuid(), 'COACH_NO_SHOW', ?, false)",
                strikeId1, coachProfileId1, Timestamp.from(Instant.now()));

            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM marketplace.coach_reliability_strikes WHERE coach_id IN (?, ?)", coachProfileId1, coachProfileId2);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id IN (?, ?)", coachProfileId1, coachProfileId2);
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?, ?)", COACH_USER_ID_1, COACH_USER_ID_2, ADMIN_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?, ?)", COACH_USER_ID_1, COACH_USER_ID_2, ADMIN_ID);
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id IN (9080, 9081)");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void listEnforcementCoaches_returnsOrderedByStatusChangedAt() {
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);

        ResponseEntity<Map> pendingResp = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/admin/coaches?status=PENDING_REVIEW",
            HttpMethod.GET, null, authenticatedHeaders(adminCookies), Map.class);
        assertThat(pendingResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pendingContent = (List<Map<String, Object>>) pendingResp.getBody().get("content");
        assertThat(pendingContent).hasSize(1);
        assertThat(pendingContent.get(0).get("coachId")).isEqualTo(coachProfileId1.toString());

        ResponseEntity<Map> suspendedResp = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/admin/coaches?status=SUSPENDED",
            HttpMethod.GET, null, authenticatedHeaders(adminCookies), Map.class);
        assertThat(suspendedResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> suspendedContent = (List<Map<String, Object>>) suspendedResp.getBody().get("content");
        assertThat(suspendedContent).hasSize(1);
        assertThat(suspendedContent.get(0).get("coachId")).isEqualTo(coachProfileId2.toString());

        ResponseEntity<Map> allResp = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/admin/coaches",
            HttpMethod.GET, null, authenticatedHeaders(adminCookies), Map.class);
        assertThat(allResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> allContent = (List<Map<String, Object>>) allResp.getBody().get("content");
        assertThat(allContent).hasSize(2);
        assertThat(allContent.get(0).get("coachId")).isEqualTo(coachProfileId1.toString());
        assertThat(allContent.get(1).get("coachId")).isEqualTo(coachProfileId2.toString());
    }

    @Test
    void getEnforcementProfile_returnsAllFields() {
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/admin/coaches/" + coachProfileId1 + "/enforcement",
            HttpMethod.GET, null, authenticatedHeaders(adminCookies), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = resp.getBody();
        assertThat(body.get("coachId")).isEqualTo(coachProfileId1.toString());
        assertThat(body.get("coachName")).isEqualTo("Enforcement Coach 1");
        assertThat(body.get("currentStatus")).isEqualTo("PENDING_REVIEW");
        assertThat(body.get("activeStrikes")).isEqualTo(1);
        assertThat(body).containsKey("strikeHistory");
        assertThat(body).containsKey("cancellationHistory");
        assertThat(body).containsKey("openAlerts");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> strikeHistory = (List<Map<String, Object>>) body.get("strikeHistory");
        assertThat(strikeHistory).hasSize(1);
        assertThat(strikeHistory.get(0).get("strikeId")).isEqualTo(strikeId1.toString());
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
            email, role, "908" + (id % 10000000), email, passwordHash, role);
    }

    private void grantAuthority(long userId, String roleName) {
        jdbcTemplate.update(
            "INSERT INTO main.user_authority (user_id, authority_id) VALUES (?, (SELECT id FROM main.authority WHERE name = ?)) ON CONFLICT DO NOTHING",
            userId, roleName);
    }
}
