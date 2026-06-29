package com.softropic.skillars.platform.reviews.api;

import com.softropic.skillars.config.TestConfig;
import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.gemini.GeminiClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.SecurityIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
class PublicReviewListIT {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String REVIEWS_BASE   = "/api/reviews";
    private static final String CLIENT_ID      = "testClientId";
    private static final String TEST_PASSWORD  = "TestPass@123!";

    private static final long PARENT_ID     = 8040_000_001L;
    private static final long COACH_USER_ID = 8040_000_010L;

    private static final String PARENT_EMAIL = "parent.publist@skillars-test.com";
    private static final String COACH_EMAIL  = "coach.publist@skillars-test.com";

    @MockitoBean
    private GeminiClient geminiClient;

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private PasswordEncoder passwordEncoder;

    @LocalServerPort private int randomServerPort;

    private UUID coachProfileId;
    private UUID review1Id;
    private UUID review2Id;
    private UUID pendingReviewId;
    private UUID blockedReviewId;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        coachProfileId = UUID.randomUUID();
        review1Id      = UUID.randomUUID();
        review2Id      = UUID.randomUUID();
        pendingReviewId  = UUID.randomUUID();
        blockedReviewId  = UUID.randomUUID();

        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (8040, 'ROLE_PARENT', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (8041, 'ROLE_COACH', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));

            insertUser(PARENT_ID, PARENT_EMAIL, passwordHash, "PARENT");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_PARENT')) ON CONFLICT DO NOTHING",
                PARENT_ID);

            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_COACH')) ON CONFLICT DO NOTHING",
                COACH_USER_ID);

            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles " +
                "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, 'PubList Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
                coachProfileId, COACH_USER_ID);

            // 2 APPROVED reviews (different lastModifiedAt and ratings)
            Instant older = Instant.now().minusSeconds(3600 * 2);
            Instant newer = Instant.now().minusSeconds(3600);
            insertReview(review1Id, coachProfileId, PARENT_ID, "PARENT", 3, "First approved", "APPROVED", older);
            insertReview(review2Id, coachProfileId, PARENT_ID + 1, "PARENT", 5, "Second approved", "APPROVED", newer);
            // 1 PENDING and 1 BLOCKED review
            insertReview(pendingReviewId,  coachProfileId, PARENT_ID + 2, "PARENT", 2, "Pending body",  "PENDING",  Instant.now());
            insertReview(blockedReviewId, coachProfileId, PARENT_ID + 3, "PARENT", 1, "Blocked body", "BLOCKED", Instant.now());

            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM reviews.coach_reviews WHERE coach_id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id = ?", coachProfileId);
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?)", PARENT_ID, COACH_USER_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?)", PARENT_ID, COACH_USER_ID);
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id IN (8040, 8041)");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void listApprovedReviews_returnsOnlyApproved_noAuthorId() {
        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            reviewsUrl("/coaches/" + coachProfileId),
            HttpMethod.GET,
            null,
            clientHeaders(),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> reviews = (List<Map<String, Object>>) resp.getBody().get("reviews");
        assertThat(reviews).hasSize(2);
        reviews.forEach(r -> {
            assertThat(r).containsKeys("reviewId", "authorRole", "rating");
            assertThat(r).doesNotContainKey("authorId");
        });
    }

    @Test
    void listApprovedReviews_defaultSort_isNewest() {
        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            reviewsUrl("/coaches/" + coachProfileId),
            HttpMethod.GET,
            null,
            clientHeaders(),
            Map.class);

        List<Map<String, Object>> reviews = (List<Map<String, Object>>) resp.getBody().get("reviews");
        assertThat(reviews).hasSize(2);
        // review2 (newer lastModifiedAt) should be first
        assertThat(reviews.get(0).get("reviewId").toString()).isEqualTo(review2Id.toString());
        assertThat(reviews.get(1).get("reviewId").toString()).isEqualTo(review1Id.toString());
    }

    @Test
    void listApprovedReviews_sortHighest_byRatingDesc() {
        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            reviewsUrl("/coaches/" + coachProfileId + "?sort=highest"),
            HttpMethod.GET,
            null,
            clientHeaders(),
            Map.class);

        List<Map<String, Object>> reviews = (List<Map<String, Object>>) resp.getBody().get("reviews");
        assertThat(reviews).hasSize(2);
        // review2 has rating 5, review1 has rating 3 — highest first
        assertThat(((Number) reviews.get(0).get("rating")).intValue()).isEqualTo(5);
        assertThat(((Number) reviews.get(1).get("rating")).intValue()).isEqualTo(3);
    }

    @Test
    void coachSelfView_returnsAllStatuses() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);
        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            reviewsUrl("/coaches/me"),
            HttpMethod.GET,
            null,
            authenticatedHeaders(coachCookies),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> reviews = (List<Map<String, Object>>) resp.getBody().get("reviews");
        assertThat(reviews).hasSize(4);

        List<String> statuses = reviews.stream()
            .map(r -> (String) r.get("moderationStatus"))
            .toList();
        assertThat(statuses).contains("APPROVED", "PENDING", "BLOCKED");
        reviews.forEach(r -> assertThat(r).doesNotContainKey("authorId"));
    }

    @Test
    void publicList_pendingAndBlockedReviews_notReturned() {
        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            reviewsUrl("/coaches/" + coachProfileId),
            HttpMethod.GET,
            null,
            clientHeaders(),
            Map.class);

        List<Map<String, Object>> reviews = (List<Map<String, Object>>) resp.getBody().get("reviews");
        List<String> reviewIds = reviews.stream()
            .map(r -> r.get("reviewId").toString())
            .toList();

        assertThat(reviewIds).doesNotContain(pendingReviewId.toString());
        assertThat(reviewIds).doesNotContain(blockedReviewId.toString());
    }

    // ── helpers ──

    private void insertReview(UUID reviewId, UUID coachId, long authorId, String authorRole,
                               int rating, String body, String moderationStatus, Instant lastModifiedAt) {
        jdbcTemplate.update(
            "INSERT INTO reviews.coach_reviews " +
            "(review_id, coach_id, author_id, author_role, rating, body, moderation_status, created_at, last_modified_at) " +
            "VALUES (?::uuid, ?, ?, ?::varchar, ?, ?, ?::varchar, ?, ?)",
            reviewId.toString(), coachId, authorId, authorRole, rating, body, moderationStatus,
            Timestamp.from(Instant.now().minusSeconds(7200)),
            Timestamp.from(lastModifiedAt));
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

    private String reviewsUrl(String path) {
        return baseUrl() + REVIEWS_BASE + path;
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
            "true, false, ?, 'EMAIL', ?, false, " +
            "?, 'BASIC_VERIFIED')",
            id,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            email, role,
            "804" + (id % 10000000),
            email, passwordHash, role);
    }
}
