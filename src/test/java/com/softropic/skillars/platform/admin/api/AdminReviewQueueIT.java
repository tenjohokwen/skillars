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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class AdminReviewQueueIT extends AbstractIntegrationTest {

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


    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;
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
    void approveReview_notFound_returns404WithConsistentErrorShape() {
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        UUID missingReviewId = UUID.randomUUID();

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/admin/reviews/" + missingReviewId + "/approve",
            HttpMethod.POST,
            null,
            authenticatedHeaders(adminCookies),
            Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException hcee = (HttpClientErrorException) e;
                assertThat(hcee.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                assertThat(hcee.getResponseBodyAsString()).contains("\"errorKey\":\"RESOURCE_NOT_FOUND\"");
            });
    }

    @Test
    void approveReview_calledTwice_secondReturns409AndWritesNoDuplicateLog() {
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        String approveUrl = baseUrl() + "/api/admin/reviews/" + reviewId + "/approve";

        ResponseEntity<Void> first = httpTestClient.makeHttpRequest(
            approveUrl, HttpMethod.POST, null, authenticatedHeaders(adminCookies), Void.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            approveUrl, HttpMethod.POST, null, authenticatedHeaders(adminCookies), Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException hcee = (HttpClientErrorException) e;
                assertThat(hcee.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(hcee.getResponseBodyAsString())
                    .contains("\"errorKey\":\"reviews.alreadyApproved\"");
            });

        Integer logRows = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM reviews.review_moderation_log WHERE review_id = ? AND action = 'APPROVED'",
            Integer.class, reviewId);
        assertThat(logRows).isEqualTo(1);
    }

    /**
     * AC1's stated guarantee is that the guard holds under <em>concurrent</em> calls — the admin
     * double-click — not merely sequential ones. The sequential test above passes unchanged against
     * a plain {@code findById}; this one releases two requests simultaneously and requires exactly
     * one to win.
     * <p>
     * <strong>Scope of the evidence (corrected by code review 2026-08-05):</strong> this does
     * <em>not</em> prove the pessimistic read. The barrier synchronises the two threads before the
     * HTTP call; everything after it — connect, auth filter, dispatch, transaction begin — is
     * unsynchronised and can be wider than the read-to-commit window. If thread A commits before B
     * issues its {@code SELECT}, a plain {@code findById} also observes the resolved status and
     * returns 409, so this test would pass against the very mutation it appears to catch. It is a
     * cheap regression net for the guard, nothing more. The lock itself is pinned by
     * {@code blockReview_whenAConcurrentBlockCommitsFirst_readsFreshStateAndRefuses}.
     */
    @Test
    void approveReview_concurrentDoubleClick_onlyOneSucceedsAndOneLogRowIsWritten() throws Exception {
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        String approveUrl = baseUrl() + "/api/admin/reviews/" + reviewId + "/approve";

        CyclicBarrier bothReady = new CyclicBarrier(2);
        AtomicInteger okCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();

        Callable<Void> approve = () -> {
            bothReady.await(10, TimeUnit.SECONDS);
            try {
                httpTestClient.makeHttpRequest(
                    approveUrl, HttpMethod.POST, null, authenticatedHeaders(adminCookies), Void.class);
                okCount.incrementAndGet();
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode() == HttpStatus.CONFLICT
                        && e.getResponseBodyAsString().contains("\"errorKey\":\"reviews.alreadyApproved\"")) {
                    conflictCount.incrementAndGet();
                } else {
                    throw e;
                }
            }
            return null;
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Void> a = executor.submit(approve);
            Future<Void> b = executor.submit(approve);
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        } finally {
            // shutdownNow in a finally: if either get() throws, a still-running request would
            // otherwise outlive @AfterEach and INSERT a moderation-log row keyed to a deleted review.
            executor.shutdownNow();
            executor.awaitTermination(30, TimeUnit.SECONDS);
        }

        assertThat(okCount.get()).as("exactly one concurrent approve may succeed").isEqualTo(1);
        assertThat(conflictCount.get()).as("the loser must get the 409, not a duplicate success").isEqualTo(1);

        Integer logRows = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM reviews.review_moderation_log WHERE review_id = ? AND action = 'APPROVED'",
            Integer.class, reviewId);
        assertThat(logRows)
            .as("the pessimistic read must stop the second caller before it writes a second log row")
            .isEqualTo(1);
    }

    /**
     * Pins the validation-error contract of POST /api/admin/reviews/{id}/block, which changes as a
     * side effect of routing AdminReviewResource through ReviewApiAdvice (assignableTypes). The
     * request body field is `reason`, not `body`, so it misses ReviewApiAdvice#handleValidation's
     * size-on-body branch and lands in the generic branch. Pre-change the global ApiAdvice answered
     * with a generated help code as errorKey plus an `invalid.reason` field error.
     */
    @Test
    void blockReview_blankReason_returns400WithReviewShapedValidationError() {
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/admin/reviews/" + reviewId + "/block",
            HttpMethod.POST,
            Map.of("reason", "  "),
            authenticatedHeaders(adminCookies),
            Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException hcee = (HttpClientErrorException) e;
                assertThat(hcee.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(hcee.getResponseBodyAsString())
                    .contains("\"errorKey\":\"reviews.validationError\"")
                    .contains("reason");
            });

        String moderationStatus = jdbcTemplate.queryForObject(
            "SELECT moderation_status FROM reviews.coach_reviews WHERE review_id = ?",
            String.class, reviewId);
        assertThat(moderationStatus).isEqualTo("UNDER_REVIEW");
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

    @Test
    void blockReview_calledTwice_secondReturns409AndWritesNoDuplicateLog() {
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        String blockUrl = baseUrl() + "/api/admin/reviews/" + reviewId + "/block";

        ResponseEntity<Void> first = httpTestClient.makeHttpRequest(
            blockUrl, HttpMethod.POST, Map.of("reason", "Clearly fake review"),
            authenticatedHeaders(adminCookies), Void.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            blockUrl, HttpMethod.POST, Map.of("reason", "Clearly fake review"),
            authenticatedHeaders(adminCookies), Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException hcee = (HttpClientErrorException) e;
                assertThat(hcee.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(hcee.getResponseBodyAsString())
                    .contains("\"errorKey\":\"reviews.alreadyBlocked\"");
            });

        Integer logRows = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM reviews.review_moderation_log WHERE review_id = ? AND action = 'BLOCKED'",
            Integer.class, reviewId);
        assertThat(logRows).isEqualTo(1);

        // AC3 requires the surviving row to carry the reason, not merely to exist.
        String loggedReason = jdbcTemplate.queryForObject(
            "SELECT reason FROM reviews.review_moderation_log WHERE review_id = ? AND action = 'BLOCKED'",
            String.class, reviewId);
        assertThat(loggedReason)
            .as("the single log row must carry the reason from the first (winning) call")
            .isEqualTo("Clearly fake review");
    }

    /**
     * AC1 closes with "ResourceNotFoundException on a missing review is unchanged (still 404,
     * errorKey = RESOURCE_NOT_FOUND)", but Task 3 never asked for the test and only the /approve
     * path had one. Added by code review 2026-08-05. The block path matters independently: it
     * carries a request body, so a regression could plausibly surface as a validation error instead.
     */
    @Test
    void blockReview_notFound_returns404WithConsistentErrorShape() {
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        UUID missingReviewId = UUID.randomUUID();

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/admin/reviews/" + missingReviewId + "/block",
            HttpMethod.POST,
            Map.of("reason", "Clearly fake review"),
            authenticatedHeaders(adminCookies),
            Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException hcee = (HttpClientErrorException) e;
                assertThat(hcee.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                assertThat(hcee.getResponseBodyAsString()).contains("\"errorKey\":\"RESOURCE_NOT_FOUND\"");
            });
    }

    /**
     * AC1's stated guarantee is that the guard holds under <em>concurrent</em> calls — the admin
     * double-click — not merely sequential ones. Mirrors
     * {@code approveReview_concurrentDoubleClick_onlyOneSucceedsAndOneLogRowIsWritten}: two requests
     * are released simultaneously and exactly one must win.
     * <p>
     * <strong>Scope of the evidence (corrected by code review 2026-08-05):</strong> this does
     * <em>not</em> prove the pessimistic read, and the story's Task 3 claim that it does was wrong.
     * The barrier synchronises the threads before the HTTP call; if thread A commits before B issues
     * its {@code SELECT}, a plain {@code findById} also observes {@code BLOCKED} and returns 409 —
     * green against the exact mutation this appears to catch. Kept as a cheap regression net for the
     * guard. The lock is pinned deterministically by
     * {@code blockReview_whenAConcurrentBlockCommitsFirst_readsFreshStateAndRefuses} below.
     */
    @Test
    void blockReview_concurrentDoubleClick_onlyOneSucceedsAndOneLogRowIsWritten() throws Exception {
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        String blockUrl = baseUrl() + "/api/admin/reviews/" + reviewId + "/block";

        CyclicBarrier bothReady = new CyclicBarrier(2);
        AtomicInteger okCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();

        Callable<Void> block = () -> {
            bothReady.await(10, TimeUnit.SECONDS);
            try {
                httpTestClient.makeHttpRequest(
                    blockUrl, HttpMethod.POST, Map.of("reason", "Clearly fake review"),
                    authenticatedHeaders(adminCookies), Void.class);
                okCount.incrementAndGet();
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode() == HttpStatus.CONFLICT
                        && e.getResponseBodyAsString().contains("\"errorKey\":\"reviews.alreadyBlocked\"")) {
                    conflictCount.incrementAndGet();
                } else {
                    throw e;
                }
            }
            return null;
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Void> a = executor.submit(block);
            Future<Void> b = executor.submit(block);
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        } finally {
            // shutdownNow in a finally: if either get() throws, a still-running request would
            // otherwise outlive @AfterEach and INSERT a moderation-log row keyed to a deleted review.
            executor.shutdownNow();
            executor.awaitTermination(30, TimeUnit.SECONDS);
        }

        assertThat(okCount.get()).as("exactly one concurrent block may succeed").isEqualTo(1);
        assertThat(conflictCount.get()).as("the loser must get the 409, not a duplicate success").isEqualTo(1);

        Integer logRows = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM reviews.review_moderation_log WHERE review_id = ? AND action = 'BLOCKED'",
            Integer.class, reviewId);
        assertThat(logRows)
            .as("the guard must stop the second caller before it writes a second log row")
            .isEqualTo(1);
    }

    /**
     * The deterministic proof of AC1's pessimistic read, added by code review 2026-08-05 after both
     * barrier-based tests were found to pass unchanged against a plain {@code findById}. Verified by
     * mutation: reverting {@code blockReview}'s {@code findByIdForUpdate} to {@code findById} makes
     * this test fail every run.
     * <p>
     * Simply holding {@code SELECT … FOR UPDATE} is <em>not</em> enough to tell the two apart — a
     * plain {@code findById} still blocks later, at the {@code UPDATE}, so the request waits either
     * way. The discriminator is what the request <em>observes</em> once it is unblocked. Here a
     * concurrent transaction blocks the review and commits while the request is in flight:
     * <ul>
     *   <li>with {@code findByIdForUpdate} the read itself waits, so after the commit it observes
     *       the fresh {@code BLOCKED} status, the guard fires, and the caller gets 409 — no second
     *       audit row;</li>
     *   <li>with a plain {@code findById} the read completes immediately against the pre-commit
     *       READ COMMITTED snapshot, observes a stale {@code UNDER_REVIEW}, sails through the guard,
     *       then blocks at the {@code UPDATE} — and on release writes a duplicate log row and
     *       answers 200. That is exactly the forged-audit-row defect AC1 exists to prevent.</li>
     * </ul>
     * No {@code Thread.sleep}: the wait is a {@code Future.get} timeout and the release is an
     * explicit commit.
     */
    @Test
    void blockReview_whenAConcurrentBlockCommitsFirst_readsFreshStateAndRefuses() throws Exception {
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        String blockUrl = baseUrl() + "/api/admin/reviews/" + reviewId + "/block";

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection firstAdmin = dataSource.getConnection()) {
            firstAdmin.setAutoCommit(false);

            // Stand in for an admin whose block has written but not yet committed: this takes the
            // row lock and changes the status the guard reads.
            try (PreparedStatement ps = firstAdmin.prepareStatement(
                    "UPDATE reviews.coach_reviews SET moderation_status = 'BLOCKED', held_reason = NULL "
                    + "WHERE review_id = ?")) {
                ps.setObject(1, reviewId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = firstAdmin.prepareStatement(
                    "INSERT INTO reviews.review_moderation_log (log_id, review_id, admin_id, action, reason, created_at) "
                    + "VALUES (gen_random_uuid(), ?, ?, 'BLOCKED', 'First admin wins', NOW())")) {
                ps.setObject(1, reviewId);
                ps.setLong(2, ADMIN_ID);
                ps.executeUpdate();
            }

            Future<Integer> second = executor.submit(() -> {
                try {
                    ResponseEntity<Void> resp = httpTestClient.makeHttpRequest(
                        blockUrl, HttpMethod.POST, Map.of("reason", "Second admin loses"),
                        authenticatedHeaders(adminCookies), Void.class);
                    return resp.getStatusCode().value();
                } catch (HttpClientErrorException e) {
                    return e.getStatusCode().value();
                }
            });

            assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS))
                .as("the second block must be held up by the first admin's uncommitted row lock")
                .isInstanceOf(TimeoutException.class);

            firstAdmin.commit();

            assertThat(second.get(30, TimeUnit.SECONDS))
                .as("after the lock releases, a locked read observes the fresh BLOCKED status and "
                    + "409s; a plain findById would still hold the stale UNDER_REVIEW snapshot and "
                    + "answer 200")
                .isEqualTo(HttpStatus.CONFLICT.value());
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(30, TimeUnit.SECONDS);
        }

        Integer logRows = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM reviews.review_moderation_log WHERE review_id = ? AND action = 'BLOCKED'",
            Integer.class, reviewId);
        assertThat(logRows)
            .as("only the first admin's audit row may exist — the whole point of AC1")
            .isEqualTo(1);
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
