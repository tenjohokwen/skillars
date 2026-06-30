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
class ReviewFlagIT {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String REVIEWS_BASE   = "/api/reviews";
    private static final String CLIENT_ID      = "testClientId";
    private static final String TEST_PASSWORD  = "TestPass@123!";

    private static final long PARENT_ID       = 8050_000_001L;
    private static final long FLAGGING_USER_ID = 8050_000_002L;
    private static final long COACH_USER_ID    = 8050_000_010L;

    private static final String PARENT_EMAIL       = "parent.flag@skillars-test.com";
    private static final String FLAGGING_EMAIL     = "flagger.flag@skillars-test.com";
    private static final String COACH_EMAIL        = "coach.flag@skillars-test.com";

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
                "VALUES (8050, 'ROLE_PARENT', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (8051, 'ROLE_COACH', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));

            insertUser(PARENT_ID, PARENT_EMAIL, passwordHash, "PARENT");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_PARENT')) ON CONFLICT DO NOTHING",
                PARENT_ID);

            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (8050000003, 'Flag Player', ?, 'MIDFIELDER', 'ADULT', ?, true, ?, 'system')",
                Date.valueOf(LocalDate.now().minusYears(18)),
                PARENT_ID, Timestamp.from(Instant.now()));

            insertUser(FLAGGING_USER_ID, FLAGGING_EMAIL, passwordHash, "PARENT");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_PARENT')) ON CONFLICT DO NOTHING",
                FLAGGING_USER_ID);

            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (8050000004, 'Flag Player 2', ?, 'MIDFIELDER', 'ADULT', ?, true, ?, 'system')",
                Date.valueOf(LocalDate.now().minusYears(18)),
                FLAGGING_USER_ID, Timestamp.from(Instant.now()));

            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_COACH')) ON CONFLICT DO NOTHING",
                COACH_USER_ID);

            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles " +
                "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, 'Flag Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
                coachProfileId, COACH_USER_ID);

            jdbcTemplate.update(
                "INSERT INTO booking.bookings " +
                "(id, coach_id, parent_id, player_id, status, requested_start_time, requested_end_time, " +
                " version, created_at, updated_at, canonical_timezone) " +
                "VALUES (?, ?, ?, 8050000003, 'COMPLETED', ?, ?, 0, ?, ?, 'Europe/Berlin')",
                UUID.randomUUID(), coachProfileId, PARENT_ID,
                Timestamp.from(Instant.now().minusSeconds(7200)),
                Timestamp.from(Instant.now().minusSeconds(3600)),
                Timestamp.from(Instant.now().minusSeconds(86400 * 3)),
                Timestamp.from(Instant.now().minusSeconds(3600)));

            // Insert an APPROVED review directly (bypass Gemini)
            jdbcTemplate.update(
                "INSERT INTO reviews.coach_reviews " +
                "(review_id, coach_id, author_id, author_role, rating, body, moderation_status, created_at, last_modified_at) " +
                "VALUES (?, ?, ?, 'PARENT', 4, 'Great coach!', 'APPROVED', ?, ?)",
                reviewId, coachProfileId, PARENT_ID,
                Timestamp.from(Instant.now().minusSeconds(3600)),
                Timestamp.from(Instant.now().minusSeconds(3600)));

            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM reviews.review_flags WHERE review_id = ?", reviewId);
            jdbcTemplate.update("DELETE FROM reviews.coach_reviews WHERE review_id = ?", reviewId);
            jdbcTemplate.update("DELETE FROM booking.bookings WHERE coach_id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM main.player_profiles WHERE id IN (8050000003, 8050000004)");
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            // Extra users seeded by flagThresholdReached test — cleaned here in case the test fails mid-run
            for (int i = 0; i < 2; i++) {
                long userId = 8050_000_020L + i;
                jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id = ?", userId);
                jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id = ?", userId);
            }
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?, ?)",
                PARENT_ID, FLAGGING_USER_ID, COACH_USER_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?, ?)",
                PARENT_ID, FLAGGING_USER_ID, COACH_USER_ID);
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id IN (8050, 8051)");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void flagSubmitted_createsFlag_returns201() {
        String cookies = loginAndGetCookies(FLAGGING_EMAIL);
        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            reviewsUrl("/" + reviewId + "/flag"),
            HttpMethod.POST,
            Map.of("reason", "FAKE_REVIEW", "details", "Looks fabricated"),
            authenticatedHeaders(cookies),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).containsKey("flagId");

        Integer flagCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM reviews.review_flags WHERE review_id = ?",
            Integer.class, reviewId);
        assertThat(flagCount).isEqualTo(1);
    }

    @Test
    void flagOwnReview_returns403WithCannotFlagOwnReview() {
        String cookies = loginAndGetCookies(PARENT_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            reviewsUrl("/" + reviewId + "/flag"),
            HttpMethod.POST,
            Map.of("reason", "OFFENSIVE_CONTENT"),
            authenticatedHeaders(cookies),
            Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(ex.getResponseBodyAsString()).contains("cannotFlagOwnReview");
            });
    }

    @Test
    void flagSameReviewTwice_returns409WithAlreadyFlagged() {
        String cookies = loginAndGetCookies(FLAGGING_EMAIL);
        Map<String, Object> body = Map.of("reason", "FAKE_REVIEW");

        httpTestClient.makeHttpRequest(reviewsUrl("/" + reviewId + "/flag"),
            HttpMethod.POST, body, authenticatedHeaders(cookies), Map.class);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            reviewsUrl("/" + reviewId + "/flag"),
            HttpMethod.POST, body, authenticatedHeaders(cookies), Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(ex.getResponseBodyAsString()).contains("alreadyFlagged");
            });
    }

    @Test
    void flagThresholdReached_reviewSetToUnderReview() {
        // Insert 2 more users and flags directly to reach threshold of 3
        transactionTemplate.execute(status -> {
            String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
            for (int i = 0; i < 2; i++) {
                long userId = 8050_000_020L + i;
                String email = "extra.flagger" + i + "@skillars-test.com";
                insertUser(userId, email, passwordHash, "PARENT");
                jdbcTemplate.update(
                    "INSERT INTO main.user_authority (user_id, authority_id) " +
                    "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_PARENT')) ON CONFLICT DO NOTHING",
                    userId);
                jdbcTemplate.update(
                    "INSERT INTO reviews.review_flags (review_id, flagged_by, reason, created_at) " +
                    "VALUES (?, ?, 'FAKE_REVIEW', ?)",
                    reviewId, userId, Timestamp.from(Instant.now()));
            }
            return null;
        });

        // Third flag via API (should trigger auto-hold)
        String cookies = loginAndGetCookies(FLAGGING_EMAIL);
        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            reviewsUrl("/" + reviewId + "/flag"),
            HttpMethod.POST,
            Map.of("reason", "CONFLICT_OF_INTEREST"),
            authenticatedHeaders(cookies),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String moderationStatus = jdbcTemplate.queryForObject(
            "SELECT moderation_status FROM reviews.coach_reviews WHERE review_id = ?",
            String.class, reviewId);
        assertThat(moderationStatus).isEqualTo("UNDER_REVIEW");

        String heldReason = jdbcTemplate.queryForObject(
            "SELECT held_reason FROM reviews.coach_reviews WHERE review_id = ?",
            String.class, reviewId);
        assertThat(heldReason).isEqualTo("FLAG_THRESHOLD");

        // Cleanup extra users (also covered by tearDown via the range below, but explicit here for clarity)
        transactionTemplate.execute(status -> {
            for (int i = 0; i < 2; i++) {
                long userId = 8050_000_020L + i;
                jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id = ?", userId);
                jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id = ?", userId);
            }
            return null;
        });
    }

    @Test
    void flagByReviewedCoach_returns403WithCannotFlagOwnCoachedReview() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            reviewsUrl("/" + reviewId + "/flag"),
            HttpMethod.POST,
            Map.of("reason", "FAKE_REVIEW"),
            authenticatedHeaders(cookies),
            Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(ex.getResponseBodyAsString()).contains("cannotFlagOwnCoachedReview");
            });
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
            "805" + (id % 10000000),
            email, passwordHash, role);
    }
}
