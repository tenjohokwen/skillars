package com.softropic.skillars.platform.admin.api;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.SecurityIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.web.client.HttpClientErrorException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-ADM-004 appeals end to end: submission rules (owner only, still enforced, 14-day window, one per
 * enforcement action), the admin queue, and decisions — including the race-safe conditional decide and
 * the OVERTURNED reversal for each subject type. No mocks: shares the plain base context.
 */
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class AppealIT extends AbstractIntegrationTest {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String CLIENT_ID = "testClientId";
    private static final String TEST_PASSWORD = "TestPass@123!";

    private static final long ADMIN_ID = 9848_000_100L;
    private static final long COACH_USER_ID = 9848_000_010L;
    private static final long PARENT_ID = 9848_000_001L;
    private static final String ADMIN_EMAIL = "appeal.admin.9848@skillars-test.com";
    private static final String COACH_EMAIL = "appeal.coach.9848@skillars-test.com";
    private static final String PARENT_EMAIL = "appeal.parent.9848@skillars-test.com";

    @Autowired private HttpTestClient httpTestClient;
    @Autowired private PasswordEncoder passwordEncoder;

    private UUID coachProfileId;
    private UUID strikeId;
    private UUID reviewId;
    private UUID disputeId;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        coachProfileId = UUID.randomUUID();
        strikeId = UUID.randomUUID();
        reviewId = UUID.randomUUID();
        disputeId = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            insertAuthority(9848, "ROLE_PARENT");
            insertAuthority(9849, "ROLE_COACH");
            insertAuthority(9850, "ROLE_ADMIN");
            insertUser(ADMIN_ID, ADMIN_EMAIL, passwordHash, "ADMIN");
            grantAuthority(ADMIN_ID, "ROLE_ADMIN");
            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH");
            grantAuthority(COACH_USER_ID, "ROLE_COACH");
            insertUser(PARENT_ID, PARENT_EMAIL, passwordHash, "PARENT");
            grantAuthority(PARENT_ID, "ROLE_PARENT");

            jdbcTemplate.update("INSERT INTO marketplace.coach_profiles (id, user_id, display_name, bio, city, "
                    + "languages, canonical_timezone, status) VALUES (?, ?, 'Appeal Coach', 'Bio', 'Berlin', "
                    + "ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
                coachProfileId, COACH_USER_ID);
            insertStrike(strikeId, Instant.now().minus(2, ChronoUnit.DAYS));

            jdbcTemplate.update("INSERT INTO reviews.coach_reviews (review_id, coach_id, author_id, author_role, "
                    + "rating, body, moderation_status, created_at, last_modified_at) "
                    + "VALUES (?, ?, ?, 'PARENT', 2, 'Honest but blocked', 'BLOCKED', ?, ?)",
                reviewId, coachProfileId, PARENT_ID, Timestamp.from(Instant.now().minus(3, ChronoUnit.DAYS)),
                Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS)));

            jdbcTemplate.update("INSERT INTO admin.disputes (id, booking_id, raised_by, raised_by_role, reason, "
                    + "details, status, resolution, resolution_note, resolved_at, resolved_by, version) "
                    + "VALUES (?, ?, ?, 'PARENT', 'COACH_NO_SHOW', 'Coach absent', 'RESOLVED', 'NO_ACTION', "
                    + "'Insufficient evidence', ?, ?, 0)",
                disputeId, UUID.randomUUID(), PARENT_ID, Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS)),
                ADMIN_ID);
            return null;
        });
    }

    // ── submission ──

    @Test
    void coachAppealsOwnStrike_thenListsIt_andCannotAppealTwice() {
        String coach = login(COACH_EMAIL);
        ResponseEntity<Map> created = submit(coach, "RELIABILITY_STRIKE", strikeId, "The parent cancelled, not me.");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("status")).isEqualTo("OPEN");

        ResponseEntity<List> mine = httpTestClient.makeHttpRequest(baseUrl() + "/api/appeals/me",
            HttpMethod.GET, null, headers(coach), List.class);
        assertThat(mine.getBody()).hasSize(1);

        HttpClientErrorException dup = catchHttp(() -> submit(coach, "RELIABILITY_STRIKE", strikeId, "again"));
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(dup.getResponseBodyAsString()).contains("appeals.alreadyAppealed");
    }

    @Test
    void someoneElsesStrike_returns404() {
        HttpClientErrorException ex = catchHttp(
            () -> submit(login(PARENT_EMAIL), "RELIABILITY_STRIKE", strikeId, "not mine"));
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(countAppeals()).isZero();
    }

    @Test
    void strikeOlderThan14Days_windowExpired() {
        UUID oldStrike = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            insertStrike(oldStrike, Instant.now().minus(15, ChronoUnit.DAYS));
            return null;
        });
        HttpClientErrorException ex = catchHttp(
            () -> submit(login(COACH_EMAIL), "RELIABILITY_STRIKE", oldStrike, "late"));
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getResponseBodyAsString()).contains("appeals.windowExpired");
    }

    @Test
    void suspensionAppeal_whenNotSuspended_notEnforced() {
        HttpClientErrorException ex = catchHttp(
            () -> submit(login(COACH_EMAIL), "COACH_SUSPENSION", coachProfileId, "I am not suspended"));
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getResponseBodyAsString()).contains("appeals.notEnforced");
    }

    @Test
    void nonAdmin_cannotSeeQueue() {
        HttpClientErrorException ex = catchHttp(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/admin/appeals/queue", HttpMethod.GET, null, headers(login(COACH_EMAIL)), Map.class));
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── decisions ──

    @Test
    void overturnedStrikeAppeal_deletesStrike_andDecisionIsFinal() {
        UUID appealId = appealId(submit(login(COACH_EMAIL), "RELIABILITY_STRIKE", strikeId, "Wrongful strike"));
        String admin = login(ADMIN_EMAIL);

        ResponseEntity<Map> queue = httpTestClient.makeHttpRequest(baseUrl() + "/api/admin/appeals/queue",
            HttpMethod.GET, null, headers(admin), Map.class);
        assertThat((List<?>) queue.getBody().get("content")).hasSize(1);

        ResponseEntity<Map> decided = decide(admin, appealId, "OVERTURNED", "Parent confirmed it was their cancel");
        assertThat(decided.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(decided.getBody().get("status")).isEqualTo("OVERTURNED");
        assertThat(decided.getBody().get("reversalApplied")).isEqualTo(true);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM marketplace.coach_reliability_strikes WHERE id = ?", Integer.class, strikeId))
            .isZero();

        HttpClientErrorException again = catchHttp(() -> decide(admin, appealId, "UPHELD", "changed my mind"));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getResponseBodyAsString()).contains("appeals.alreadyDecided");

        // Decision is final: no fresh appeal for the same strike, even after the decision.
        assertThat(catchHttp(() -> submit(login(COACH_EMAIL), "RELIABILITY_STRIKE", strikeId, "x"))
            .getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.CONFLICT);
    }

    @Test
    void overturnedSuspensionAppeal_reinstatesCoach() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("UPDATE marketplace.coach_profiles SET status = 'SUSPENDED', status_changed_at = ? "
                + "WHERE id = ?", Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS)), coachProfileId);
            return null;
        });
        UUID appealId = appealId(submit(login(COACH_EMAIL), "COACH_SUSPENSION", coachProfileId, "Please review"));

        ResponseEntity<Map> decided = decide(login(ADMIN_EMAIL), appealId, "OVERTURNED", "Suspension lifted");
        assertThat(decided.getBody().get("reversalApplied")).isEqualTo(true);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM marketplace.coach_profiles WHERE id = ?",
            String.class, coachProfileId)).isNotEqualTo("SUSPENDED");
    }

    @Test
    void upheldReviewBlockAppeal_leavesReviewBlocked() {
        UUID appealId = appealId(submit(login(PARENT_EMAIL), "REVIEW_BLOCK", reviewId, "My review was fair"));

        ResponseEntity<Map> decided = decide(login(ADMIN_EMAIL), appealId, "UPHELD", "Contains personal data");
        assertThat(decided.getBody().get("status")).isEqualTo("UPHELD");
        assertThat(decided.getBody().get("reversalApplied")).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT moderation_status FROM reviews.coach_reviews "
            + "WHERE review_id = ?", String.class, reviewId)).isEqualTo("BLOCKED");
    }

    @Test
    void overturnedReviewBlockAppeal_approvesReview() {
        UUID appealId = appealId(submit(login(PARENT_EMAIL), "REVIEW_BLOCK", reviewId, "My review was fair"));
        ResponseEntity<Map> decided = decide(login(ADMIN_EMAIL), appealId, "OVERTURNED", "Fair review");
        assertThat(decided.getBody().get("reversalApplied")).isEqualTo(true);
        assertThat(jdbcTemplate.queryForObject("SELECT moderation_status FROM reviews.coach_reviews "
            + "WHERE review_id = ?", String.class, reviewId)).isEqualTo("APPROVED");
    }

    @Test
    void overturnedDisputeAppeal_recordsDecisionWithoutAutomaticReversal() {
        UUID appealId = appealId(submit(login(PARENT_EMAIL), "DISPUTE_RESOLUTION", disputeId, "New evidence"));
        String admin = login(ADMIN_EMAIL);

        ResponseEntity<Map> detail = httpTestClient.makeHttpRequest(baseUrl() + "/api/admin/appeals/" + appealId,
            HttpMethod.GET, null, headers(admin), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> subject = (Map<String, Object>) detail.getBody().get("subject");
        assertThat(subject).containsEntry("disputeResolution", "NO_ACTION");

        ResponseEntity<Map> decided = decide(admin, appealId, "OVERTURNED", "Credit issued manually");
        assertThat(decided.getBody().get("status")).isEqualTo("OVERTURNED");
        assertThat(decided.getBody().get("reversalApplied")).isEqualTo(false);
    }

    @Test
    void concurrentDecisions_exactlyOneWins() throws Exception {
        UUID appealId = appealId(submit(login(PARENT_EMAIL), "DISPUTE_RESOLUTION", disputeId, "New evidence"));
        String admin = login(ADMIN_EMAIL);
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String outcome = i % 2 == 0 ? "UPHELD" : "OVERTURNED";
                results.add(pool.submit(() -> {
                    go.await();
                    try {
                        return decide(admin, appealId, outcome, "race").getStatusCode().value();
                    } catch (HttpClientErrorException e) {
                        return e.getStatusCode().value();
                    }
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get(60, TimeUnit.SECONDS));
            }
            assertThat(statuses).filteredOn(s -> s == 200).hasSize(1);
            assertThat(statuses).filteredOn(s -> s == 409).hasSize(threads - 1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ── helpers ──

    private ResponseEntity<Map> submit(String cookies, String type, UUID subjectId, String reason) {
        return httpTestClient.makeHttpRequest(baseUrl() + "/api/appeals", HttpMethod.POST,
            Map.of("subjectType", type, "subjectId", subjectId.toString(), "reason", reason), headers(cookies),
            Map.class);
    }

    private ResponseEntity<Map> decide(String cookies, UUID appealId, String outcome, String note) {
        return httpTestClient.makeHttpRequest(baseUrl() + "/api/admin/appeals/" + appealId + "/decide",
            HttpMethod.POST, Map.of("outcome", outcome, "note", note), headers(cookies), Map.class);
    }

    private static UUID appealId(ResponseEntity<Map> created) {
        return UUID.fromString((String) created.getBody().get("appealId"));
    }

    private int countAppeals() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM admin.appeals WHERE appellant_user_id IN (?, ?)",
            Integer.class, PARENT_ID, COACH_USER_ID);
    }

    private void insertStrike(UUID id, Instant createdAt) {
        jdbcTemplate.update("INSERT INTO marketplace.coach_reliability_strikes "
                + "(id, coach_id, booking_id, reason, acknowledged, created_at) "
                + "VALUES (?, ?, ?, 'COACH_NO_SHOW', false, ?)",
            id, coachProfileId, UUID.randomUUID(), Timestamp.from(createdAt));
    }

    private HttpClientErrorException catchHttp(Runnable call) {
        try {
            call.run();
        } catch (HttpClientErrorException e) {
            return e;
        }
        throw new AssertionError("expected an HTTP client error");
    }

    private String login(String email) {
        ResponseEntity<Map> loginResponse = httpTestClient.makeHttpRequest(baseUrl() + LOGIN_ENDPOINT,
            HttpMethod.POST, Map.of("email", email, "password", TEST_PASSWORD), clientHeaders(), Map.class);
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

    private HttpHeaders headers(String cookies) {
        HttpHeaders headers = clientHeaders();
        headers.add(HttpHeaders.COOKIE, cookies);
        return headers;
    }

    private void insertAuthority(int id, String name) {
        jdbcTemplate.update("INSERT INTO main.authority (id, name, status, created_by, created_date) "
            + "VALUES (?, ?, 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING", id, name, Timestamp.from(Instant.now()));
    }

    private void insertUser(long id, String email, String passwordHash, String role) {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" (id, created_by, created_date, last_modified_by, last_modified_date, request_id, "
                + "session_id, status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, "
                + "activated, locked, login, login_id_type, password_hash, otp_enabled, skillars_role, "
                + "verification_status) VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, 'ACTIVE', '1985-06-01', "
                + "?, 'Test', 'OTHER', 'en', ?, 'DE', ?, true, false, ?, 'EMAIL', ?, false, ?, 'BASIC_VERIFIED')",
            id, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            email, role, "985" + (id % 10000000), email, passwordHash, role);
    }

    private void grantAuthority(long userId, String roleName) {
        jdbcTemplate.update("INSERT INTO main.user_authority (user_id, authority_id) "
            + "VALUES (?, (SELECT id FROM main.authority WHERE name = ?)) ON CONFLICT DO NOTHING", userId, roleName);
    }
}
