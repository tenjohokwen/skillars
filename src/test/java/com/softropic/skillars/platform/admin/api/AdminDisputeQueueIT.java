package com.softropic.skillars.platform.admin.api;

import com.softropic.skillars.config.AbstractIntegrationTest;

import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.SecurityIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * skillars-deferred-165 AC1: {@code GET /api/admin/disputes/queue}, mirroring
 * {@code AdminReviewQueueIT}'s shape for {@code AdminReviewResource.getQueue}. The {@code admin.disputes}
 * table has no FK on {@code booking_id} (V138 baseline), so these rows do not need a real booking.
 */
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class AdminDisputeQueueIT extends AbstractIntegrationTest {

    private static final String LOGIN_ENDPOINT  = "/api/auth/login";
    private static final String ADMIN_QUEUE_URL = "/api/admin/disputes/queue";
    private static final String CLIENT_ID       = "testClientId";
    private static final String TEST_PASSWORD   = "TestPass@123!";

    private static final long ADMIN_ID  = 9165_000_100L;
    private static final long PARENT_ID = 9165_000_001L;

    private static final String ADMIN_EMAIL  = "admin.dispqueue9165@skillars-test.com";
    private static final String PARENT_EMAIL = "parent.dispqueue9165@skillars-test.com";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private PasswordEncoder passwordEncoder;

    @LocalServerPort private int randomServerPort;

    private final List<UUID> disputeIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);

        transactionTemplate.execute(status -> {
            insertAuthority(9165, "ROLE_PARENT");
            insertAuthority(9166, "ROLE_ADMIN");

            insertUser(PARENT_ID, PARENT_EMAIL, passwordHash, "PARENT");
            grantAuthority(PARENT_ID, "ROLE_PARENT");

            insertUser(ADMIN_ID, ADMIN_EMAIL, passwordHash, "ADMIN");
            grantAuthority(ADMIN_ID, "ROLE_ADMIN");

            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            for (UUID disputeId : disputeIds) {
                jdbcTemplate.update("DELETE FROM admin.disputes WHERE id = ?", disputeId);
            }
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?)", PARENT_ID, ADMIN_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?)", PARENT_ID, ADMIN_ID);
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id IN (9165, 9166)");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void emptyQueue_returnsEmptyPage() {
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            baseUrl() + ADMIN_QUEUE_URL, HttpMethod.GET, null, authenticatedHeaders(adminCookies), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) resp.getBody().get("content");
        assertThat(content).isEmpty();
    }

    @Test
    void oneOpenDispute_appearsInQueue() {
        UUID disputeId = insertDispute("OPEN", "COACH_NO_SHOW", Instant.now().minusSeconds(60));

        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            baseUrl() + ADMIN_QUEUE_URL, HttpMethod.GET, null, authenticatedHeaders(adminCookies), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) resp.getBody().get("content");
        assertThat(content).hasSize(1);
        Map<String, Object> entry = content.get(0);
        assertThat(entry.get("disputeId")).isEqualTo(disputeId.toString());
        assertThat(entry.get("raisedByRole")).isEqualTo("PARENT");
        assertThat(entry.get("reason")).isEqualTo("COACH_NO_SHOW");
        assertThat(entry.get("status")).isEqualTo("OPEN");
    }

    @Test
    void resolvedOrDismissedDispute_doesNotAppearInQueue() {
        insertDispute("RESOLVED", "SESSION_QUALITY", Instant.now().minusSeconds(60));
        insertDispute("DISMISSED", "SAFETY_CONCERN", Instant.now().minusSeconds(30));

        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            baseUrl() + ADMIN_QUEUE_URL, HttpMethod.GET, null, authenticatedHeaders(adminCookies), Map.class);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) resp.getBody().get("content");
        assertThat(content).isEmpty();
    }

    @Test
    void pagination_splitsAcrossMultiplePages() {
        for (int i = 0; i < 21; i++) {
            insertDispute("OPEN", "OTHER", Instant.now().minusSeconds(3600 - i));
        }

        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);

        ResponseEntity<Map> page0 = httpTestClient.makeHttpRequest(
            baseUrl() + ADMIN_QUEUE_URL + "?page=0",
            HttpMethod.GET, null, authenticatedHeaders(adminCookies), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content0 = (List<Map<String, Object>>) page0.getBody().get("content");
        assertThat(content0).hasSize(20);
        assertThat(((Number) page0.getBody().get("totalElements")).longValue()).isEqualTo(21);

        ResponseEntity<Map> page1 = httpTestClient.makeHttpRequest(
            baseUrl() + ADMIN_QUEUE_URL + "?page=1",
            HttpMethod.GET, null, authenticatedHeaders(adminCookies), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content1 = (List<Map<String, Object>>) page1.getBody().get("content");
        assertThat(content1).hasSize(1);
    }

    @Test
    void nonAdminCannotViewQueue() {
        String cookies = loginAndGetCookies(PARENT_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + ADMIN_QUEUE_URL, HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void nonOpenStatus_returns400() {
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + ADMIN_QUEUE_URL + "?status=RESOLVED",
            HttpMethod.GET, null, authenticatedHeaders(adminCookies), Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ── helpers ──

    private UUID insertDispute(String status, String reason, Instant createdAt) {
        UUID disputeId = UUID.randomUUID();
        transactionTemplate.execute(txStatus -> {
            jdbcTemplate.update(
                "INSERT INTO admin.disputes (id, booking_id, raised_by, raised_by_role, reason, details, status, created_at, version) " +
                "VALUES (?, ?, ?, 'PARENT', ?, 'Queue test dispute', ?, ?, 0)",
                disputeId, UUID.randomUUID(), PARENT_ID, reason, status, Timestamp.from(createdAt));
            return null;
        });
        disputeIds.add(disputeId);
        return disputeId;
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
            "916" + (id % 10000000),
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
}
