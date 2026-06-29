package com.softropic.skillars.platform.reviews.api;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;

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
class ReviewUpdateIT {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String REVIEWS_BASE   = "/api/reviews";
    private static final String CLIENT_ID      = "testClientId";
    private static final String TEST_PASSWORD  = "TestPass@123!";

    private static final long PARENT_ID      = 8010_000_001L;
    private static final long PLAYER_ID      = 8010_000_002L;
    private static final long COACH_USER_ID  = 8010_000_010L;
    private static final long PARENT_ID2     = 8010_000_003L;

    private static final String PARENT_EMAIL  = "parent.revupd@skillars-test.com";
    private static final String PARENT_EMAIL2 = "parent2.revupd@skillars-test.com";
    private static final String COACH_EMAIL   = "coach.revupd@skillars-test.com";

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
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (8010, 'ROLE_PARENT', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (8011, 'ROLE_COACH', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));

            insertUser(PARENT_ID, PARENT_EMAIL, passwordHash, "PARENT");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_PARENT')) ON CONFLICT DO NOTHING",
                PARENT_ID);

            insertUser(PARENT_ID2, PARENT_EMAIL2, passwordHash, "PARENT");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_PARENT')) ON CONFLICT DO NOTHING",
                PARENT_ID2);

            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Rev Update Player', ?, 'MIDFIELDER', 'ADULT', ?, true, ?, 'system')",
                PLAYER_ID, Date.valueOf(LocalDate.now().minusYears(18)),
                PARENT_ID, Timestamp.from(Instant.now()));

            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_COACH')) ON CONFLICT DO NOTHING",
                COACH_USER_ID);

            coachProfileId = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles " +
                "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, 'Rev Update Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
                coachProfileId, COACH_USER_ID);

            // COMPLETED booking within last 3 days
            jdbcTemplate.update(
                "INSERT INTO booking.bookings " +
                "(id, coach_id, parent_id, player_id, status, requested_start_time, requested_end_time, " +
                " version, created_at, updated_at, canonical_timezone) " +
                "VALUES (?, ?, ?, ?, 'COMPLETED', ?, ?, 0, ?, ?, 'Europe/Berlin')",
                UUID.randomUUID(), coachProfileId, PARENT_ID, PLAYER_ID,
                Timestamp.from(Instant.now().minusSeconds(86400 * 3 + 3600)),
                Timestamp.from(Instant.now().minusSeconds(86400 * 3)),
                Timestamp.from(Instant.now().minusSeconds(86400 * 7)),
                Timestamp.from(Instant.now().minusSeconds(86400 * 3)));

            // Existing APPROVED review — last_modified_at more than 365 days ago
            reviewId = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO reviews.coach_reviews " +
                "(review_id, coach_id, author_id, author_role, rating, body, moderation_status, last_modified_at, created_at) " +
                "VALUES (?, ?, ?, 'PARENT', 4, 'Original review', 'APPROVED', ?, ?)",
                reviewId, coachProfileId, PARENT_ID,
                Timestamp.from(Instant.now().minusSeconds(86400L * 400)),
                Timestamp.from(Instant.now().minusSeconds(86400L * 400)));

            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM reviews.coach_reviews WHERE coach_id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM booking.bookings WHERE coach_id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM main.player_profiles WHERE id = ?", PLAYER_ID);
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?, ?)",
                PARENT_ID, PARENT_ID2, COACH_USER_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?, ?)",
                PARENT_ID, PARENT_ID2, COACH_USER_ID);
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id IN (8010, 8011)");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void updateReview_afterOneYear_returns204() {
        String parentCookies = loginAndGetCookies(PARENT_EMAIL);
        httpTestClient.makeHttpRequest(
            reviewsUrl("/" + reviewId),
            HttpMethod.PATCH,
            Map.of("rating", 5, "body", "Updated review"),
            authenticatedHeaders(parentCookies),
            Void.class);

        String moderationStatus = jdbcTemplate.queryForObject(
            "SELECT moderation_status FROM reviews.coach_reviews WHERE review_id = ?::uuid",
            String.class, reviewId.toString());
        assertThat(moderationStatus).isEqualTo("PENDING");

        String coachResponseBody = jdbcTemplate.queryForObject(
            "SELECT coach_response_body FROM reviews.coach_reviews WHERE review_id = ?::uuid",
            String.class, reviewId.toString());
        assertThat(coachResponseBody).isNull();
    }

    @Test
    void updateReview_noRecentSession_returns403() {
        // Move booking to 30 days ago
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "UPDATE booking.bookings SET updated_at = ? WHERE coach_id = ?",
                Timestamp.from(Instant.now().minusSeconds(30L * 86400)), coachProfileId);
            return null;
        });

        String parentCookies = loginAndGetCookies(PARENT_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            reviewsUrl("/" + reviewId),
            HttpMethod.PATCH,
            Map.of("rating", 5, "body", "Stale update"),
            authenticatedHeaders(parentCookies),
            Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(ex.getResponseBodyAsString()).contains("reviews.noRecentSession");
            });
    }

    @Test
    void updateReview_tooSoon_returns403() {
        // Set last_modified_at to 100 days ago (within 365-day gate)
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "UPDATE reviews.coach_reviews SET last_modified_at = ? WHERE review_id = ?",
                Timestamp.from(Instant.now().minusSeconds(86400L * 100)), reviewId);
            return null;
        });

        String parentCookies = loginAndGetCookies(PARENT_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            reviewsUrl("/" + reviewId),
            HttpMethod.PATCH,
            Map.of("rating", 5, "body", "Too soon"),
            authenticatedHeaders(parentCookies),
            Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(ex.getResponseBodyAsString()).contains("reviews.updateTooSoon");
            });
    }

    @Test
    void updateReview_blockedStatus_returns403() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "UPDATE reviews.coach_reviews SET moderation_status = 'BLOCKED' WHERE review_id = ?",
                reviewId);
            return null;
        });

        String parentCookies = loginAndGetCookies(PARENT_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            reviewsUrl("/" + reviewId),
            HttpMethod.PATCH,
            Map.of("rating", 5, "body", "Blocked"),
            authenticatedHeaders(parentCookies),
            Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(ex.getResponseBodyAsString()).contains("reviews.editNotPermitted");
            });
    }

    @Test
    void updateReview_wrongAuthor_returns403() {
        String parent2Cookies = loginAndGetCookies(PARENT_EMAIL2);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            reviewsUrl("/" + reviewId),
            HttpMethod.PATCH,
            Map.of("rating", 1, "body", "Not mine"),
            authenticatedHeaders(parent2Cookies),
            Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(ex.getResponseBodyAsString()).contains("reviews.authorMismatch");
            });
    }

    // ── helpers ──

    private String loginAndGetCookies(String email) {
        var loginResponse = httpTestClient.makeHttpRequest(
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
            "80" + (id % 100000000),
            email, passwordHash, role);
    }
}
