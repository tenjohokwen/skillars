package com.softropic.skillars.platform.admin.api;

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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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
class AdminReviewQueueIT {

    private static final String LOGIN_ENDPOINT  = "/api/auth/login";
    private static final String ADMIN_QUEUE_URL = "/api/admin/reviews/queue";
    private static final String CLIENT_ID       = "testClientId";
    private static final String TEST_PASSWORD   = "TestPass@123!";

    private static final long ADMIN_ID        = 8060_000_100L;
    private static final long PARENT_ID       = 8060_000_001L;
    private static final long COACH_USER_ID   = 8060_000_010L;

    private static final String ADMIN_EMAIL  = "admin.queue@skillars-test.com";
    private static final String PARENT_EMAIL = "parent.queue@skillars-test.com";
    private static final String COACH_EMAIL  = "coach.queue@skillars-test.com";

    @MockitoBean
    private GeminiClient geminiClient;

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private PasswordEncoder passwordEncoder;

    @LocalServerPort private int randomServerPort;

    private UUID coachProfileId;
    private UUID reviewId;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        coachProfileId = UUID.randomUUID();
        reviewId = UUID.randomUUID();

        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (8060, 'ROLE_PARENT', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (8061, 'ROLE_COACH', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (8062, 'ROLE_ADMIN', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));

            insertUser(PARENT_ID, PARENT_EMAIL, passwordHash, "PARENT");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_PARENT')) ON CONFLICT DO NOTHING",
                PARENT_ID);

            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (8060000002, 'Queue Player', ?, 'MIDFIELDER', 'ADULT', ?, true, ?, 'system')",
                Date.valueOf(LocalDate.now().minusYears(18)),
                PARENT_ID, Timestamp.from(Instant.now()));

            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_COACH')) ON CONFLICT DO NOTHING",
                COACH_USER_ID);

            insertUser(ADMIN_ID, ADMIN_EMAIL, passwordHash, "ADMIN");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_ADMIN')) ON CONFLICT DO NOTHING",
                ADMIN_ID);

            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles " +
                "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, 'Queue Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
                coachProfileId, COACH_USER_ID);

            jdbcTemplate.update(
                "INSERT INTO booking.bookings " +
                "(id, coach_id, parent_id, player_id, status, requested_start_time, requested_end_time, " +
                " version, created_at, updated_at, canonical_timezone) " +
                "VALUES (?, ?, ?, 8060000002, 'COMPLETED', ?, ?, 0, ?, ?, 'Europe/Berlin')",
                UUID.randomUUID(), coachProfileId, PARENT_ID,
                Timestamp.from(Instant.now().minusSeconds(7200)),
                Timestamp.from(Instant.now().minusSeconds(3600)),
                Timestamp.from(Instant.now().minusSeconds(86400 * 3)),
                Timestamp.from(Instant.now().minusSeconds(3600)));

            // Insert an UNDER_REVIEW review with FLAG_THRESHOLD held reason
            jdbcTemplate.update(
                "INSERT INTO reviews.coach_reviews " +
                "(review_id, coach_id, author_id, author_role, rating, body, moderation_status, held_reason, created_at, last_modified_at) " +
                "VALUES (?, ?, ?, 'PARENT', 3, 'Suspicious review', 'UNDER_REVIEW', 'FLAG_THRESHOLD', ?, ?)",
                reviewId, coachProfileId, PARENT_ID,
                Timestamp.from(Instant.now().minusSeconds(3600)),
                Timestamp.from(Instant.now().minusSeconds(3600)));

            // Insert a couple of flags
            jdbcTemplate.update(
                "INSERT INTO reviews.review_flags (review_id, flagged_by, reason, created_at) VALUES (?, ?, 'FAKE_REVIEW', ?)",
                reviewId, 8060_000_099L, Timestamp.from(Instant.now().minusSeconds(1000)));
            jdbcTemplate.update(
                "INSERT INTO reviews.review_flags (review_id, flagged_by, reason, created_at) VALUES (?, ?, 'CONFLICT_OF_INTEREST', ?)",
                reviewId, 8060_000_098L, Timestamp.from(Instant.now().minusSeconds(500)));

            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM reviews.review_moderation_log WHERE review_id = ?", reviewId);
            jdbcTemplate.update("DELETE FROM reviews.review_flags WHERE review_id = ?", reviewId);
            jdbcTemplate.update("DELETE FROM reviews.coach_reviews WHERE review_id = ?", reviewId);
            jdbcTemplate.update("DELETE FROM booking.bookings WHERE coach_id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM main.player_profiles WHERE id = 8060000002");
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?, ?)",
                PARENT_ID, COACH_USER_ID, ADMIN_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?, ?)",
                PARENT_ID, COACH_USER_ID, ADMIN_ID);
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id IN (8060, 8061, 8062)");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void adminCanViewQueue() {
        String cookies = loginAndGetCookies(ADMIN_EMAIL);
        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            baseUrl() + ADMIN_QUEUE_URL,
            HttpMethod.GET,
            null,
            authenticatedHeaders(cookies),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) resp.getBody().get("content");
        assertThat(content).isNotEmpty();

        Map<String, Object> entry = content.stream()
            .filter(e -> reviewId.toString().equals(e.get("reviewId")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Review not found in queue"));

        assertThat(entry.get("heldReason")).isEqualTo("FLAG_THRESHOLD");
        assertThat(((Number) entry.get("flagCount")).longValue()).isEqualTo(2L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> flags = (List<Map<String, Object>>) entry.get("flags");
        assertThat(flags).hasSize(2);
        // Verify flaggedBy is NOT exposed
        flags.forEach(f -> assertThat(f).doesNotContainKey("flaggedBy"));
    }

    @Test
    void nonAdminCannotViewQueue() {
        String cookies = loginAndGetCookies(PARENT_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + ADMIN_QUEUE_URL,
            HttpMethod.GET,
            null,
            authenticatedHeaders(cookies),
            Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void approveReview_setsApprovedAndRecomputesRating() {
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        ResponseEntity<Void> resp = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/admin/reviews/" + reviewId + "/approve",
            HttpMethod.POST,
            null,
            authenticatedHeaders(adminCookies),
            Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        String moderationStatus = jdbcTemplate.queryForObject(
            "SELECT moderation_status FROM reviews.coach_reviews WHERE review_id = ?",
            String.class, reviewId);
        assertThat(moderationStatus).isEqualTo("APPROVED");

        Integer resolvedFlags = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM reviews.review_flags WHERE review_id = ? AND resolved_at IS NOT NULL",
            Integer.class, reviewId);
        assertThat(resolvedFlags).isEqualTo(2);

        Integer reviewCount = jdbcTemplate.queryForObject(
            "SELECT review_count FROM marketplace.coach_profiles WHERE id = ?",
            Integer.class, coachProfileId);
        assertThat(reviewCount).isEqualTo(1);
    }

    @Test
    void blockReview_setsBlockedAndResolvesFlags() {
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        ResponseEntity<Void> resp = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/admin/reviews/" + reviewId + "/block",
            HttpMethod.POST,
            Map.of("reason", "Clearly fake review"),
            authenticatedHeaders(adminCookies),
            Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        String moderationStatus = jdbcTemplate.queryForObject(
            "SELECT moderation_status FROM reviews.coach_reviews WHERE review_id = ?",
            String.class, reviewId);
        assertThat(moderationStatus).isEqualTo("BLOCKED");

        Integer resolvedFlags = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM reviews.review_flags WHERE review_id = ? AND resolved_at IS NOT NULL",
            Integer.class, reviewId);
        assertThat(resolvedFlags).isEqualTo(2);
    }

    @Test
    void blockPreviouslyApprovedReview_recomputesRatingDown() {
        // Promote the setUp review to APPROVED so blocking it triggers a rating recompute
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "UPDATE reviews.coach_reviews SET moderation_status = 'APPROVED', rating = 5 WHERE review_id = ?",
                reviewId);
            jdbcTemplate.update(
                "UPDATE marketplace.coach_profiles SET review_count = 1, average_rating = 5.0 WHERE id = ?",
                coachProfileId);
            return null;
        });

        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        httpTestClient.makeHttpRequest(
            baseUrl() + "/api/admin/reviews/" + reviewId + "/block",
            HttpMethod.POST,
            Map.of("reason", "Violates policy"),
            authenticatedHeaders(adminCookies),
            Void.class);

        Integer reviewCount = jdbcTemplate.queryForObject(
            "SELECT review_count FROM marketplace.coach_profiles WHERE id = ?",
            Integer.class, coachProfileId);
        assertThat(reviewCount).isEqualTo(0);
    }

    // ── helpers ──

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
            "806" + (id % 10000000),
            email, passwordHash, role);
    }
}
