package com.softropic.skillars.platform.admin.api;

import com.softropic.skillars.config.TestConfig;
import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.gemini.GeminiClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.filestorage.service.FileStorageService;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
class GdprErasureIT {

    private static final String LOGIN_ENDPOINT  = "/api/auth/login";
    private static final String ERASURE_URL     = "/api/gdpr/erasure";
    private static final String CLIENT_ID       = "testClientId";
    private static final String TEST_PASSWORD   = "TestPass@123!";

    private static final long PARENT_ID       = 9210_000_001L;
    private static final long COACH_USER_ID   = 9210_000_002L;
    private static final long PLAYER_ID       = 9210_000_003L;

    private static final String PARENT_EMAIL  = "gdpr.erasure.parent.9210@skillars-test.com";
    private static final String COACH_EMAIL   = "gdpr.erasure.coach.9210@skillars-test.com";
    private static final String PLAYER_EMAIL  = "gdpr.erasure.player.9210@skillars-test.com";

    @MockitoBean
    private GeminiClient geminiClient;

    @MockitoBean
    private FileStorageService fileStorageService;

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private PasswordEncoder passwordEncoder;

    @LocalServerPort private int randomServerPort;

    private UUID coachProfileId;

    @BeforeEach
    void setUp() {
        coachProfileId = UUID.randomUUID();
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) VALUES (9210, 'ROLE_PARENT', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) VALUES (9211, 'ROLE_COACH', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) VALUES (9212, 'ROLE_PLAYER', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));

            insertUser(PARENT_ID, PARENT_EMAIL, passwordHash, "PARENT");
            grantAuthority(PARENT_ID, "ROLE_PARENT");

            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH");
            grantAuthority(COACH_USER_ID, "ROLE_COACH");
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles (id, user_id, display_name, bio, city, languages, canonical_timezone, status) VALUES (?, ?, 'GDPR Coach', 'Some bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
                coachProfileId, COACH_USER_ID);

            insertUser(PLAYER_ID, PLAYER_EMAIL, passwordHash, "PLAYER");
            grantAuthority(PLAYER_ID, "ROLE_PLAYER");

            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM admin.gdpr_requests WHERE user_id IN (?, ?, ?)", PARENT_ID, COACH_USER_ID, PLAYER_ID);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id = ?", coachProfileId);
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?, ?)", PARENT_ID, COACH_USER_ID, PLAYER_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?, ?)", PARENT_ID, COACH_USER_ID, PLAYER_ID);
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id IN (9210, 9211, 9212)");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void requestErasure_parentUser_returns202WithRequestId() {
        String cookies = loginAndGetCookies(PARENT_EMAIL);
        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            baseUrl() + ERASURE_URL, HttpMethod.POST, null, authenticatedHeaders(cookies), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(resp.getBody()).containsKey("requestId");
        UUID requestId = UUID.fromString((String) resp.getBody().get("requestId"));
        assertThat(requestId).isNotNull();
    }

    @Test
    void requestErasure_withPendingExport_returns409() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO admin.gdpr_requests (id, user_id, request_type, status, created_at) VALUES (?, ?, 'EXPORT', 'PROCESSING', ?)",
                UUID.randomUUID(), PARENT_ID, Timestamp.from(Instant.now()));
            return null;
        });

        String cookies = loginAndGetCookies(PARENT_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + ERASURE_URL, HttpMethod.POST, null, authenticatedHeaders(cookies), Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void requestErasure_duplicateErasure_returns409() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO admin.gdpr_requests (id, user_id, request_type, status, created_at) VALUES (?, ?, 'ERASURE', 'PENDING', ?)",
                UUID.randomUUID(), PARENT_ID, Timestamp.from(Instant.now()));
            jdbcTemplate.update(
                "INSERT INTO admin.gdpr_requests (id, user_id, request_type, status, created_at) VALUES (?, ?, 'ERASURE', 'PENDING', ?)",
                UUID.randomUUID(), COACH_USER_ID, Timestamp.from(Instant.now()));
            return null;
        });

        String cookies = loginAndGetCookies(COACH_EMAIL);
        // Second erasure request should conflict via partial unique index or service check
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + ERASURE_URL, HttpMethod.POST, null, authenticatedHeaders(cookies), Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void requestErasure_anonymisesUserProfile() {
        String cookies = loginAndGetCookies(PARENT_EMAIL);
        httpTestClient.makeHttpRequest(
            baseUrl() + ERASURE_URL, HttpMethod.POST, null, authenticatedHeaders(cookies), Map.class);

        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT email, first_name, last_name, activated, locked FROM main.\"user\" WHERE id = ?", PARENT_ID);

        assertThat((String) row.get("email")).startsWith("deleted.");
        assertThat(row.get("first_name")).isEqualTo("Deleted");
        assertThat(row.get("last_name")).isEqualTo("User");
        assertThat(row.get("activated")).isEqualTo(false);
        assertThat(row.get("locked")).isEqualTo(true);
    }

    @Test
    void requestErasure_coachUser_anonymisesCoachProfile() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        httpTestClient.makeHttpRequest(
            baseUrl() + ERASURE_URL, HttpMethod.POST, null, authenticatedHeaders(cookies), Map.class);

        Map<String, Object> cpRow = jdbcTemplate.queryForMap(
            "SELECT bio, city FROM marketplace.coach_profiles WHERE id = ?", coachProfileId);
        assertThat(cpRow.get("bio")).isNull();
        assertThat(cpRow.get("city")).isNull();
    }

    @Test
    void requestErasure_playerUser_erasureCompletesSuccessfully() {
        String cookies = loginAndGetCookies(PLAYER_EMAIL);
        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            baseUrl() + ERASURE_URL, HttpMethod.POST, null, authenticatedHeaders(cookies), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID requestId = UUID.fromString((String) resp.getBody().get("requestId"));

        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT email, activated FROM main.\"user\" WHERE id = ?", PLAYER_ID);
        assertThat((String) row.get("email")).startsWith("deleted.");
        assertThat(row.get("activated")).isEqualTo(false);
    }

    @Test
    void requestErasure_marksFinalStatusCompleted() {
        String cookies = loginAndGetCookies(PARENT_EMAIL);
        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            baseUrl() + ERASURE_URL, HttpMethod.POST, null, authenticatedHeaders(cookies), Map.class);

        UUID requestId = UUID.fromString((String) resp.getBody().get("requestId"));
        Map<String, Object> gdprRow = jdbcTemplate.queryForMap(
            "SELECT status FROM admin.gdpr_requests WHERE id = ?", requestId);
        assertThat(gdprRow.get("status")).isEqualTo("COMPLETED");
    }

    @Test
    void requestErasure_unauthenticated_returns401() {
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + ERASURE_URL, HttpMethod.POST, null, clientHeaders(), Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void erase_deletesNonApprovedReviews_anonymisesApproved() {
        UUID approvedReviewId  = UUID.randomUUID();
        UUID pendingReviewId   = UUID.randomUUID();
        UUID secondCoachId     = UUID.randomUUID(); // distinct coach — avoids uq_coach_reviews_author_coach
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO reviews.coach_reviews (review_id, coach_id, author_id, author_role, rating, body, moderation_status, created_at, last_modified_at) VALUES (?, ?, ?, 'PARENT', 5, 'Great coach', 'APPROVED', ?, ?)",
                approvedReviewId, coachProfileId, PARENT_ID,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
            jdbcTemplate.update(
                "INSERT INTO reviews.coach_reviews (review_id, coach_id, author_id, author_role, rating, body, moderation_status, created_at, last_modified_at) VALUES (?, ?, ?, 'PARENT', 2, 'Bad coach', 'PENDING', ?, ?)",
                pendingReviewId, secondCoachId, PARENT_ID,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
            return null;
        });

        String cookies = loginAndGetCookies(PARENT_EMAIL);
        httpTestClient.makeHttpRequest(
            baseUrl() + ERASURE_URL, HttpMethod.POST, null, authenticatedHeaders(cookies), Map.class);

        // APPROVED review must be anonymised (author_id = 0), not deleted
        Map<String, Object> approved = jdbcTemplate.queryForMap(
            "SELECT author_id FROM reviews.coach_reviews WHERE review_id = ?", approvedReviewId);
        assertThat(((Number) approved.get("author_id")).longValue()).isZero();

        // PENDING review must be hard-deleted
        int pendingCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM reviews.coach_reviews WHERE review_id = ?",
            Integer.class, pendingReviewId);
        assertThat(pendingCount).isZero();

        // cleanup — approvedReviewId is anonymised (not deleted) by erasure; pendingReviewId is deleted by erasure
        jdbcTemplate.update("DELETE FROM reviews.coach_reviews WHERE review_id IN (?, ?)", approvedReviewId, pendingReviewId);
    }

    @Test
    void erase_deletesMessages() {
        long conversationId = 9210_900_001L;
        long messageId      = 9210_900_002L;
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO messaging.conversations (id, coach_id, player_id, parent_id, status, created_at, last_message_at) VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)",
                conversationId, coachProfileId, PLAYER_ID, PARENT_ID,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
            jdbcTemplate.update(
                "INSERT INTO messaging.messages (id, conversation_id, sender_id, sender_role, content, moderation_status, created_at) VALUES (?, ?, ?, 'PARENT', 'Hello', 'APPROVED', ?)",
                messageId, conversationId, PARENT_ID, Timestamp.from(Instant.now()));
            return null;
        });

        String cookies = loginAndGetCookies(PARENT_EMAIL);
        httpTestClient.makeHttpRequest(
            baseUrl() + ERASURE_URL, HttpMethod.POST, null, authenticatedHeaders(cookies), Map.class);

        int count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM messaging.messages WHERE id = ?", Integer.class, messageId);
        assertThat(count).isZero();

        // cleanup
        jdbcTemplate.update("DELETE FROM messaging.conversations WHERE id = ?", conversationId);
    }

    @Test
    void erase_retainsFinancialRecords() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO payment.parent_credit_ledger (parent_id, amount, type, description) VALUES (?, 50.00, 'BOOKING_REFUND', 'Test refund')",
                PARENT_ID);
            return null;
        });

        String cookies = loginAndGetCookies(PARENT_EMAIL);
        httpTestClient.makeHttpRequest(
            baseUrl() + ERASURE_URL, HttpMethod.POST, null, authenticatedHeaders(cookies), Map.class);

        int count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment.parent_credit_ledger WHERE parent_id = ?", Integer.class, PARENT_ID);
        assertThat(count).isGreaterThan(0);
    }

    @Test
    void erase_deactivatesUser_oldSessionRejected() {
        // Obtain a valid session before erasure
        String cookiesBeforeErasure = loginAndGetCookies(PARENT_EMAIL);

        // Erasure runs synchronously via AFTER_COMMIT listener — user is deactivated before 202 returns
        httpTestClient.makeHttpRequest(
            baseUrl() + ERASURE_URL, HttpMethod.POST, null,
            authenticatedHeaders(cookiesBeforeErasure), Map.class);

        // Old session cookies must now be rejected (activated=false, locked=true)
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + ERASURE_URL, HttpMethod.POST, null,
            authenticatedHeaders(cookiesBeforeErasure), Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED));
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
            email, role, "921" + (id % 10000000), email, passwordHash, role);
    }

    private void grantAuthority(long userId, String roleName) {
        jdbcTemplate.update(
            "INSERT INTO main.user_authority (user_id, authority_id) VALUES (?, (SELECT id FROM main.authority WHERE name = ?)) ON CONFLICT DO NOTHING",
            userId, roleName);
    }
}
