package com.softropic.skillars.platform.reviews.api;

import com.softropic.skillars.config.AbstractIntegrationTest;

import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.reviews.service.ReviewSubmissionService;
import com.softropic.skillars.platform.security.SecurityIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class ReviewSubmissionIT extends AbstractIntegrationTest {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String REVIEWS_BASE   = "/api/reviews";
    private static final String CLIENT_ID      = "testClientId";
    private static final String TEST_PASSWORD  = "TestPass@123!";

    private static final long PARENT_ID     = 8000_000_001L;
    private static final long PLAYER_ID     = 8000_000_002L;
    private static final long COACH_USER_ID = 8000_000_010L;

    private static final String PARENT_EMAIL = "parent.rev@skillars-test.com";
    private static final String COACH_EMAIL  = "coach.rev@skillars-test.com";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ReviewSubmissionService reviewSubmissionService;

    @LocalServerPort private int randomServerPort;

    private UUID coachProfileId;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (8000, 'ROLE_PARENT', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (8001, 'ROLE_COACH', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));

            insertUser(PARENT_ID, PARENT_EMAIL, passwordHash, "PARENT");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_PARENT')) ON CONFLICT DO NOTHING",
                PARENT_ID);

            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Rev Player', ?, 'MIDFIELDER', 'ADULT', ?, true, ?, 'system')",
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
                "VALUES (?, ?, 'Rev Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
                coachProfileId, COACH_USER_ID);

            // COMPLETED booking within last 3 days — eligible for review
            insertCompletedBooking(Instant.now().minusSeconds(3 * 86400));

            return null;
        });
    }


    @Test
    void submitReview_validEligibility_returns201WithReviewId() {
        String parentCookies = loginAndGetCookies(PARENT_EMAIL);
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            reviewsUrl("/coaches/" + coachProfileId),
            HttpMethod.POST,
            Map.of("rating", 4, "body", "Great coach!"),
            authenticatedHeaders(parentCookies),
            Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsKey("reviewId");

        String reviewId = (String) response.getBody().get("reviewId");
        int count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM reviews.coach_reviews WHERE review_id = ?::uuid",
            Integer.class, reviewId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void submitReview_ratingOnly_returns201() {
        String parentCookies = loginAndGetCookies(PARENT_EMAIL);
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            reviewsUrl("/coaches/" + coachProfileId),
            HttpMethod.POST,
            Map.of("rating", 5),
            authenticatedHeaders(parentCookies),
            Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsKey("reviewId");
    }

    @Test
    void submitReview_noRecentSession_returns403WithCode() {
        // Move the booking's updatedAt to 30 days ago (outside 14-day window)
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "UPDATE booking.bookings SET updated_at = ? WHERE coach_id = ?",
                Timestamp.from(Instant.now().minusSeconds(30L * 86400)), coachProfileId);
            return null;
        });

        String parentCookies = loginAndGetCookies(PARENT_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            reviewsUrl("/coaches/" + coachProfileId),
            HttpMethod.POST,
            Map.of("rating", 4, "body", "Late review"),
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
    void submitReview_duplicate_returns409() {
        String parentCookies = loginAndGetCookies(PARENT_EMAIL);
        // First submission
        httpTestClient.makeHttpRequest(
            reviewsUrl("/coaches/" + coachProfileId),
            HttpMethod.POST,
            Map.of("rating", 3, "body", "First review"),
            authenticatedHeaders(parentCookies),
            Map.class);
        // Second submission — same author/coach pair
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            reviewsUrl("/coaches/" + coachProfileId),
            HttpMethod.POST,
            Map.of("rating", 5, "body", "Duplicate review"),
            authenticatedHeaders(parentCookies),
            Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(ex.getResponseBodyAsString()).contains("reviews.alreadySubmitted");
            });
    }

    @Test
    void submitReview_bodyTooLong_returns400() {
        String parentCookies = loginAndGetCookies(PARENT_EMAIL);
        String tooLong = "x".repeat(1001);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            reviewsUrl("/coaches/" + coachProfileId),
            HttpMethod.POST,
            Map.of("rating", 4, "body", tooLong),
            authenticatedHeaders(parentCookies),
            Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(ex.getResponseBodyAsString()).contains("reviews.bodyTooLong");
            });
    }

    @Test
    void submitReview_coachNotFound_returns404() {
        String parentCookies = loginAndGetCookies(PARENT_EMAIL);
        UUID unknownCoach = UUID.randomUUID();
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            reviewsUrl("/coaches/" + unknownCoach),
            HttpMethod.POST,
            Map.of("rating", 4, "body", "Test"),
            authenticatedHeaders(parentCookies),
            Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));
    }

    /**
     * AC1 (skillars-deferred-88): the moderation-epoch bump in {@code updateReview} must be applied
     * to the row's <em>fresh</em> state read under the {@code findByIdForUpdate} lock, not to the
     * stale instance already managed from the unlocked pre-check load. This is the same Hibernate
     * identity-map gotcha {@code MessagingService.softDeleteMessage} documents: a {@code @Lock} JPQL
     * query takes the DB lock but returns the existing managed instance without refreshing it, so
     * {@code updateReview} must call {@code entityManager.refresh(locked, PESSIMISTIC_WRITE)}.
     *
     * <p>Deterministic causality proof (mirrors {@code DrillUploadServiceConcurrencyIT}'s
     * externally-held-lock design): an external transaction holds a {@code SELECT ... FOR UPDATE} on
     * the review row, bumps {@code moderation_epoch} from 5 straight to 99, and commits. Only after
     * that release can {@code updateReview}'s locked read proceed; with the refresh it observes 99
     * and writes 100. Without the refresh it still holds the pre-lock snapshot (epoch 5) and writes
     * 6 — so the {@code isEqualTo(100)} assertion is a direct mutation check on the refresh line.
     */
    @Test
    @Timeout(30)
    void updateReview_epochBumpAppliesToFreshLockedState_notStaleInstance() throws Exception {
        UUID reviewId = UUID.randomUUID();
        Instant oldModified = Instant.now().minusSeconds(400L * 86400); // outside the 365-day re-edit rule
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO reviews.coach_reviews " +
                "(review_id, coach_id, author_id, author_role, rating, body, moderation_status, " +
                " created_at, last_modified_at, moderation_epoch) " +
                "VALUES (?, ?, ?, 'PARENT', 3, 'original body', 'APPROVED', ?, ?, 5)",
                reviewId, coachProfileId, PARENT_ID,
                Timestamp.from(oldModified), Timestamp.from(oldModified));
            return null;
        });

        CountDownLatch lockHeld = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = pool.submit(() -> transactionTemplate.execute(status -> {
                jdbcTemplate.query(
                    "SELECT review_id FROM reviews.coach_reviews WHERE review_id = ? FOR UPDATE",
                    rs -> { }, reviewId);
                jdbcTemplate.update(
                    "UPDATE reviews.coach_reviews SET moderation_epoch = 99 WHERE review_id = ?", reviewId);
                lockHeld.countDown();
                try {
                    Thread.sleep(1200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null; // commit -> releases the row lock, epoch now 99
            }));

            assertThat(lockHeld.await(5, TimeUnit.SECONDS))
                .as("external holder must take the row lock and bump the epoch first").isTrue();

            // Blocks on findByIdForUpdate until the holder above commits.
            reviewSubmissionService.updateReview(reviewId, PARENT_ID, 4, "edited body");
            holder.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        Long finalEpoch = jdbcTemplate.queryForObject(
            "SELECT moderation_epoch FROM reviews.coach_reviews WHERE review_id = ?", Long.class, reviewId);
        assertThat(finalEpoch)
            .as("updateReview must bump the epoch it read under the lock (99) -> 100, not the stale "
                + "pre-lock value (5) -> 6")
            .isEqualTo(100L);
    }

    // ── helpers ──

    private void insertCompletedBooking(Instant updatedAt) {
        jdbcTemplate.update(
            "INSERT INTO booking.bookings " +
            "(id, coach_id, parent_id, player_id, status, requested_start_time, requested_end_time, " +
            " version, created_at, updated_at, canonical_timezone) " +
            "VALUES (?, ?, ?, ?, 'COMPLETED', ?, ?, 0, ?, ?, 'Europe/Berlin')",
            UUID.randomUUID(), coachProfileId, PARENT_ID, PLAYER_ID,
            Timestamp.from(updatedAt.minusSeconds(3600)),
            Timestamp.from(updatedAt),
            Timestamp.from(Instant.now().minusSeconds(86400 * 7)),
            Timestamp.from(updatedAt));
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
