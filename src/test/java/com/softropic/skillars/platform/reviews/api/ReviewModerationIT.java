package com.softropic.skillars.platform.reviews.api;

import com.softropic.skillars.config.AbstractIntegrationTest;

import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.gemini.GeminiException;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.admin.service.AdminReviewService;
import com.softropic.skillars.platform.messaging.contract.ModerationVerdict;
import com.softropic.skillars.platform.security.SecurityIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class ReviewModerationIT extends AbstractIntegrationTest {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String REVIEWS_BASE   = "/api/reviews";
    private static final String CLIENT_ID      = "testClientId";
    private static final String TEST_PASSWORD  = "TestPass@123!";

    private static final long PARENT_ID     = 8030_000_001L;
    private static final long PLAYER_ID     = 8030_000_002L;
    private static final long COACH_USER_ID = 8030_000_010L;

    private static final String PARENT_EMAIL = "parent.moderation@skillars-test.com";
    private static final String COACH_EMAIL  = "coach.moderation@skillars-test.com";


    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AdminReviewService adminReviewService;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private PasswordEncoder passwordEncoder;

    @LocalServerPort private int randomServerPort;

    private UUID coachProfileId;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        coachProfileId = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (8030, 'ROLE_PARENT', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (8031, 'ROLE_COACH', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));

            insertUser(PARENT_ID, PARENT_EMAIL, passwordHash, "PARENT");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_PARENT')) ON CONFLICT DO NOTHING",
                PARENT_ID);

            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Mod Player', ?, 'MIDFIELDER', 'ADULT', ?, true, ?, 'system')",
                PLAYER_ID, Date.valueOf(LocalDate.now().minusYears(18)),
                PARENT_ID, Timestamp.from(Instant.now()));

            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_COACH')) ON CONFLICT DO NOTHING",
                COACH_USER_ID);

            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles " +
                "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, 'Moderation Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
                coachProfileId, COACH_USER_ID);

            jdbcTemplate.update(
                "INSERT INTO booking.bookings " +
                "(id, coach_id, parent_id, player_id, status, requested_start_time, requested_end_time, " +
                " version, created_at, updated_at, canonical_timezone) " +
                "VALUES (?, ?, ?, ?, 'COMPLETED', ?, ?, 0, ?, ?, 'Europe/Berlin')",
                UUID.randomUUID(), coachProfileId, PARENT_ID, PLAYER_ID,
                Timestamp.from(Instant.now().minusSeconds(7200)),
                Timestamp.from(Instant.now().minusSeconds(3600)),
                Timestamp.from(Instant.now().minusSeconds(86400 * 3)),
                Timestamp.from(Instant.now().minusSeconds(3600)));

            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "DELETE FROM reviews.review_moderation_log WHERE review_id IN " +
                "(SELECT review_id FROM reviews.coach_reviews WHERE coach_id = ?)", coachProfileId);
            jdbcTemplate.update("DELETE FROM reviews.coach_reviews WHERE coach_id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM booking.bookings WHERE coach_id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM main.player_profiles WHERE id = ?", PLAYER_ID);
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?)", PARENT_ID, COACH_USER_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?)", PARENT_ID, COACH_USER_ID);
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id IN (8030, 8031)");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void geminiReturnsSafe_statusApproved_ratingRecomputed() {
        when(geminiClient.evaluate(any())).thenReturn(ModerationVerdict.SAFE);

        String parentCookies = loginAndGetCookies(PARENT_EMAIL);
        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            reviewsUrl("/coaches/" + coachProfileId),
            HttpMethod.POST,
            Map.of("rating", 4, "body", "Excellent coaching session!"),
            authenticatedHeaders(parentCookies),
            Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String status = jdbcTemplate.queryForObject(
            "SELECT moderation_status FROM reviews.coach_reviews WHERE coach_id = ?",
            String.class, coachProfileId);
        assertThat(status).isEqualTo("APPROVED");

        Integer reviewCount = jdbcTemplate.queryForObject(
            "SELECT review_count FROM marketplace.coach_profiles WHERE id = ?",
            Integer.class, coachProfileId);
        assertThat(reviewCount).isEqualTo(1);

        Double avgRating = jdbcTemplate.queryForObject(
            "SELECT average_rating FROM marketplace.coach_profiles WHERE id = ?",
            Double.class, coachProfileId);
        assertThat(avgRating).isNotNull();
        assertThat(avgRating).isEqualTo(4.0);
    }

    @Test
    void geminiReturnsUnsafe_statusBlocked_ratingUnchanged() {
        when(geminiClient.evaluate(any())).thenReturn(ModerationVerdict.UNSAFE);

        String parentCookies = loginAndGetCookies(PARENT_EMAIL);
        httpTestClient.makeHttpRequest(
            reviewsUrl("/coaches/" + coachProfileId),
            HttpMethod.POST,
            Map.of("rating", 1, "body", "Harmful content here"),
            authenticatedHeaders(parentCookies),
            Map.class);

        String status = jdbcTemplate.queryForObject(
            "SELECT moderation_status FROM reviews.coach_reviews WHERE coach_id = ?",
            String.class, coachProfileId);
        assertThat(status).isEqualTo("BLOCKED");

        Integer reviewCount = jdbcTemplate.queryForObject(
            "SELECT review_count FROM marketplace.coach_profiles WHERE id = ?",
            Integer.class, coachProfileId);
        assertThat(reviewCount).isEqualTo(0);
    }

    @Test
    void nullBody_noGeminiCall_statusApproved() {
        String parentCookies = loginAndGetCookies(PARENT_EMAIL);
        httpTestClient.makeHttpRequest(
            reviewsUrl("/coaches/" + coachProfileId),
            HttpMethod.POST,
            Map.of("rating", 5),
            authenticatedHeaders(parentCookies),
            Map.class);

        verify(geminiClient, never()).evaluate(any());

        String status = jdbcTemplate.queryForObject(
            "SELECT moderation_status FROM reviews.coach_reviews WHERE coach_id = ?",
            String.class, coachProfileId);
        assertThat(status).isEqualTo("APPROVED");
    }

    @Test
    void geminiFailure_statusUnderReview_ratingUnchanged() {
        when(geminiClient.evaluate(any())).thenThrow(new GeminiException("simulated-timeout", null));

        String parentCookies = loginAndGetCookies(PARENT_EMAIL);
        httpTestClient.makeHttpRequest(
            reviewsUrl("/coaches/" + coachProfileId),
            HttpMethod.POST,
            Map.of("rating", 3, "body", "Some review text"),
            authenticatedHeaders(parentCookies),
            Map.class);

        String status = jdbcTemplate.queryForObject(
            "SELECT moderation_status FROM reviews.coach_reviews WHERE coach_id = ?",
            String.class, coachProfileId);
        assertThat(status).isEqualTo("UNDER_REVIEW");

        Integer reviewCount = jdbcTemplate.queryForObject(
            "SELECT review_count FROM marketplace.coach_profiles WHERE id = ?",
            Integer.class, coachProfileId);
        assertThat(reviewCount).isEqualTo(0);
    }

    /**
     * Deferred-14 AC2. Reproduces the race Deferred-13 D1 described: an admin blocks a PENDING
     * review while the Gemini call for that same review is still in flight. A SAFE verdict landing
     * afterwards must NOT revert the admin's BLOCK.
     *
     * <p>Ordering matters and is the whole test. The moderation listener is AFTER_COMMIT and not
     * {@code @Async}, so it runs on the request thread — which is why the other tests in this class
     * can assert final status straight after the POST returns. Here the POST therefore has to run on
     * a background thread: it parks inside the listener while the main thread performs and commits
     * the admin block. The Gemini call itself happens outside any transaction, so the admin's
     * {@code findByIdForUpdate} does not contend with the listener — which is exactly why the race is
     * reachable in production.
     *
     * <p>Mutation-verified: reverting the PENDING guard in ReviewModerationService makes this fail
     * with moderation_status = APPROVED. Asserting on observed final state rather than on timing is
     * deliberate — Deferred-13's review found that barrier tests which only prove "the second caller
     * waits" pass against both the fixed and the unfixed code.
     */
    @Test
    void adminBlocksWhileGeminiInFlight_blockSurvivesSafeVerdict() throws Exception {
        final long adminId = 8030_000_099L;
        CountDownLatch geminiEntered = new CountDownLatch(1);
        CountDownLatch releaseGemini = new CountDownLatch(1);

        when(geminiClient.evaluate(any())).thenAnswer(invocation -> {
            geminiEntered.countDown();
            assertThat(releaseGemini.await(20, TimeUnit.SECONDS)).isTrue();
            return ModerationVerdict.SAFE;
        });

        String parentCookies = loginAndGetCookies(PARENT_EMAIL);
        AtomicReference<Throwable> submitFailure = new AtomicReference<>();
        Thread submitter = new Thread(() -> {
            try {
                httpTestClient.makeHttpRequest(
                    reviewsUrl("/coaches/" + coachProfileId),
                    HttpMethod.POST,
                    Map.of("rating", 5, "body", "Excellent coaching session!"),
                    authenticatedHeaders(parentCookies),
                    Map.class);
            } catch (Throwable t) {
                submitFailure.set(t);
            }
        }, "review-submitter");
        submitter.start();

        // The listener is AFTER_COMMIT, so by the time Gemini is entered the review row is committed
        // and visible to this thread.
        assertThat(geminiEntered.await(20, TimeUnit.SECONDS)).isTrue();
        UUID reviewId = jdbcTemplate.queryForObject(
            "SELECT review_id FROM reviews.coach_reviews WHERE coach_id = ?", UUID.class, coachProfileId);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT moderation_status FROM reviews.coach_reviews WHERE review_id = ?",
            String.class, reviewId)).isEqualTo("PENDING");

        adminReviewService.blockReview(reviewId, "Admin decision during moderation", adminId);

        releaseGemini.countDown();
        submitter.join(30_000);
        assertThat(submitter.isAlive()).isFalse();
        assertThat(submitFailure.get()).isNull();

        assertThat(jdbcTemplate.queryForObject(
            "SELECT moderation_status FROM reviews.coach_reviews WHERE review_id = ?",
            String.class, reviewId))
            .as("SAFE verdict must not revert the admin's BLOCK")
            .isEqualTo("BLOCKED");

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM reviews.review_moderation_log WHERE review_id = ? AND action = 'BLOCKED'",
            Integer.class, reviewId))
            .as("exactly one admin BLOCKED audit row")
            .isEqualTo(1);

        // The blocked review must never have reached the public aggregate. blockReview itself only
        // recomputes when previousStatus == APPROVED (it was PENDING here), so a non-zero count could
        // only come from the listener writing APPROVED and recomputing.
        assertThat(jdbcTemplate.queryForObject(
            "SELECT review_count FROM marketplace.coach_profiles WHERE id = ?",
            Integer.class, coachProfileId))
            .as("listener must not have published the blocked review")
            .isEqualTo(0);
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
            "803" + (id % 10000000),
            email, passwordHash, role);
    }
}
