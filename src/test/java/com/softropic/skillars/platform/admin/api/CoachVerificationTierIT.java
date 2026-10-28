package com.softropic.skillars.platform.admin.api;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.SecurityIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpStatusCodeException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * feat/admin-moderation-and-enforcement D6: {@code PATCH /api/admin/coaches/{id}/verification-tier}
 * sets the marketplace verification tier, audits it (who / when / OLD -> NEW), and the enforcement
 * profile + list expose the tier. Same seeding / login shape as {@link ReinstateIT}; no mocks.
 */
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class CoachVerificationTierIT extends AbstractIntegrationTest {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String CLIENT_ID = "testClientId";
    private static final String TEST_PASSWORD = "TestPass@123!";

    private static final long ADMIN_ID = 9173_000_100L;
    private static final long COACH_USER_ID = 9173_000_010L;
    private static final String ADMIN_EMAIL = "admin.tier9173@skillars-test.com";
    private static final String COACH_EMAIL = "coach.tier9173@skillars-test.com";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private PasswordEncoder passwordEncoder;

    private UUID coachProfileId;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        coachProfileId = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) VALUES (9173, 'ROLE_COACH', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) VALUES (9174, 'ROLE_ADMIN', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));
            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH");
            grantAuthority(COACH_USER_ID, "ROLE_COACH");
            insertUser(ADMIN_ID, ADMIN_EMAIL, passwordHash, "ADMIN");
            grantAuthority(ADMIN_ID, "ROLE_ADMIN");
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles (id, user_id, display_name, bio, city, languages, canonical_timezone, status, status_changed_at) " +
                "VALUES (?, ?, 'Tier Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'SUSPENDED', ?)",
                coachProfileId, COACH_USER_ID, Timestamp.from(Instant.now()));
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM admin.admin_action_log WHERE reference_id = ?", coachProfileId.toString());
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id = ?", coachProfileId);
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?)", COACH_USER_ID, ADMIN_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?)", COACH_USER_ID, ADMIN_ID);
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id IN (9173, 9174)");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void setTier_updatesProfile_andAuditsWhoAndTransition() {
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);

        ResponseEntity<Void> resp = setTier(adminCookies, "TRUSTED", "Background check passed");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT verification_tier FROM marketplace.coach_profiles WHERE id = ?", String.class, coachProfileId))
            .isEqualTo("TRUSTED");
        Map<String, Object> log = jdbcTemplate.queryForMap(
            "SELECT admin_id, reason, created_at FROM admin.admin_action_log WHERE reference_id = ? AND action_type = 'COACH_TIER_CHANGE'",
            coachProfileId.toString());
        assertThat(((Number) log.get("admin_id")).longValue()).isEqualTo(ADMIN_ID);
        assertThat(log.get("reason")).isEqualTo("BASIC -> TRUSTED: Background check passed");
        assertThat(log.get("created_at")).isNotNull();

        ResponseEntity<Map> profile = call(
            baseUrl() + "/api/admin/coaches/" + coachProfileId + "/enforcement", HttpMethod.GET, null,
            authenticatedHeaders(adminCookies), Map.class);
        assertThat(profile.getBody().get("verificationTier")).isEqualTo("TRUSTED");

        ResponseEntity<Map> list = call(
            baseUrl() + "/api/admin/coaches?status=SUSPENDED", HttpMethod.GET, null,
            authenticatedHeaders(adminCookies), Map.class);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) list.getBody().get("content");
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.get("coachId")).isEqualTo(coachProfileId.toString());
            assertThat(r.get("verificationTier")).isEqualTo("TRUSTED");
        });
    }

    @Test
    void setTier_toCurrentValue_isNoOpWithoutAuditRow() {
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        assertThat(setTier(adminCookies, "BASIC", null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM admin.admin_action_log WHERE reference_id = ? AND action_type = 'COACH_TIER_CHANGE'",
            Long.class, coachProfileId.toString())).isZero();
    }

    @Test
    void setTier_invalidValue_isRejected_andNonAdminIsForbidden() {
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        assertThat(setTier(adminCookies, "PLATINUM", null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        String coachCookies = loginAndGetCookies(COACH_EMAIL);
        assertThat(setTier(coachCookies, "FEATURED", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT verification_tier FROM marketplace.coach_profiles WHERE id = ?", String.class, coachProfileId))
            .isEqualTo("BASIC");
    }

    private ResponseEntity<Void> setTier(String cookies, String tier, String reason) {
        Map<String, Object> body = new HashMap<>();
        body.put("tier", tier);
        body.put("reason", reason);
        return call(
            baseUrl() + "/api/admin/coaches/" + coachProfileId + "/verification-tier", HttpMethod.PATCH,
            body, authenticatedHeaders(cookies), Void.class);
    }

    private String loginAndGetCookies(String email) {
        ResponseEntity<Map> loginResponse = call(
            baseUrl() + LOGIN_ENDPOINT, HttpMethod.POST,
            Map.of("email", email, "password", TEST_PASSWORD), clientHeaders(), Map.class);
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

    private void insertUser(long id, String email, String passwordHash, String role) {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" (id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, activated, locked, login, login_id_type, password_hash, otp_enabled, skillars_role, verification_status) VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, 'ACTIVE', '1985-06-01', ?, 'Test', 'OTHER', 'en', ?, 'DE', ?, true, false, ?, 'EMAIL', ?, false, ?, 'BASIC_VERIFIED')",
            id, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            email, role, "918" + (id % 10000000), email, passwordHash, role);
    }

    private void grantAuthority(long userId, String roleName) {
        jdbcTemplate.update(
            "INSERT INTO main.user_authority (user_id, authority_id) VALUES (?, (SELECT id FROM main.authority WHERE name = ?)) ON CONFLICT DO NOTHING",
            userId, roleName);
    }

    /**
     * {@link HttpTestClient} throws on any 4xx/5xx; this keeps the status so refusal paths can be
     * asserted on directly. Error bodies are dropped (assertions on them read the DB instead).
     */
    private <T> ResponseEntity<T> call(String url, HttpMethod method, Map<String, Object> body, HttpHeaders headers, Class<T> type) {
        try {
            return httpTestClient.makeHttpRequest(url, method, body, headers, type);
        } catch (HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode()).build();
        }
    }
}
