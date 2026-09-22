package com.softropic.skillars.platform.admin.api;

import com.softropic.skillars.config.AbstractIntegrationTest;

import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.admin.service.GdprErasureService;
import com.softropic.skillars.platform.filestorage.service.FileStorageService;
import com.softropic.skillars.platform.security.SecurityIT;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class GdprErasureIT extends AbstractIntegrationTest {

    private static final String LOGIN_ENDPOINT  = "/api/auth/login";
    private static final String ERASURE_URL     = "/api/gdpr/erasure";
    private static final String CLIENT_ID       = "testClientId";
    private static final String TEST_PASSWORD   = "TestPass@123!";

    private static final long PARENT_ID       = 9210_000_001L;
    private static final long COACH_USER_ID   = 9210_000_002L;
    private static final long PLAYER_ID       = 9210_000_003L;
    // skillars-deferred-127 AC1: a self-registered PLAYER user WITH a linked player_profiles row —
    // the existing PLAYER_ID above deliberately has none (it already covers the no-profile-row case).
    // The profile id below is TSID-shaped and deliberately distinct from SELF_PLAYER_USER_ID — if the
    // fixture instead reused the user id as the profile id, these tests would stay green against the
    // pre-fix (B1) code too, since player_profiles.id is a TSID unrelated to main.user.id in production.
    private static final long SELF_PLAYER_USER_ID    = 9210_000_004L;
    private static final long SELF_PLAYER_PROFILE_ID = 9210_777_951_331L;

    private static final String PARENT_EMAIL      = "gdpr.erasure.parent.9210@skillars-test.com";
    private static final String COACH_EMAIL       = "gdpr.erasure.coach.9210@skillars-test.com";
    private static final String PLAYER_EMAIL      = "gdpr.erasure.player.9210@skillars-test.com";
    private static final String SELF_PLAYER_EMAIL = "gdpr.erasure.selfplayer.9210@skillars-test.com";


    @MockitoBean
    private FileStorageService fileStorageService;

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private com.softropic.skillars.platform.outbox.service.OutboxService outboxService;
    @Autowired private GdprErasureService gdprErasureService;

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

            insertUser(SELF_PLAYER_USER_ID, SELF_PLAYER_EMAIL, passwordHash, "PLAYER");
            grantAuthority(SELF_PLAYER_USER_ID, "ROLE_PLAYER");
            // skillars-deferred-127 AC1 Test note: mirrors RadarCompositeCalculationServiceConcurrencyIT's
            // own player_profiles fixture — the id is TSID-shaped (SELF_PLAYER_PROFILE_ID) and linked
            // to its owning account via user_id, NOT via a shared value with the user id itself.
            jdbcTemplate.update(
                "INSERT INTO main.player_profiles "
                    + "(id, name, date_of_birth, position, age_tier, user_id, independent_account_allowed, created_at, created_by) "
                    + "VALUES (?, 'Self-Registered Player', ?, 'MIDFIELDER', 'ADULT', ?, true, ?, 'system')",
                SELF_PLAYER_PROFILE_ID, Date.valueOf(LocalDate.now().minusYears(19)),
                SELF_PLAYER_USER_ID, Timestamp.from(Instant.now()));

            // skillars-deferred-100 AC6: the blob-deletion outbox is now the shared generic
            // platform.outbox. Scope the reset to this test family's aggregate_type so a leftover
            // BLOB_DELETION row from a prior test cannot be drained (and its mock key deleted) here.
            jdbcTemplate.update("DELETE FROM main.outbox_messages WHERE aggregate_type = 'BLOB_DELETION'");

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

    // skillars-deferred-127 AC1 (story-review.md B1): moved from PLAYER_ID/PLAYER_EMAIL to
    // SELF_PLAYER_PROFILE_ID/SELF_PLAYER_EMAIL. Before this AC, the PLAYER branch passed the raw
    // user id straight to deletePlayerDevelopmentData, so seeding this fixture with player_id =
    // PLAYER_ID (a main.user.id) happened to match what the (buggy) production code used at call
    // time — not a production-realistic shape. Now that deletePlayerDevelopmentData requires a
    // genuine player_profiles.id (resolved via findByUserId), these tests must use an account that
    // actually has a linked profile — PLAYER_ID deliberately has none (it covers the no-profile-row
    // case elsewhere) — or the erasure correctly skips deletion and these assertions would fail.
    @Test
    void erase_playerUser_deletesPerformanceReportFromS3() {
        UUID reportId = UUID.randomUUID();
        String storageKey = "reports/" + reportId + "/report.pdf";
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO development.performance_reports "
                    + "(id, coach_id, player_id, generated_at, storage_key, next_steps) "
                    + "VALUES (?, ?, ?, ?, ?, 'Keep working on first touch')",
                reportId, coachProfileId, SELF_PLAYER_PROFILE_ID, Timestamp.from(Instant.now()), storageKey);
            return null;
        });

        String cookies = loginAndGetCookies(SELF_PLAYER_EMAIL);
        httpTestClient.makeHttpRequest(
            baseUrl() + ERASURE_URL, HttpMethod.POST, null, authenticatedHeaders(cookies), Map.class);

        // The erasure's requestDrainAfterCommit() fires an @Async drain; call drain() synchronously
        // too so the assertion does not race it (both are SKIP-LOCKED, so the row is handled once).
        outboxService.drain();

        verify(fileStorageService).deleteRawBytes(storageKey);
        int count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM development.performance_reports WHERE id = ?", Integer.class, reportId);
        assertThat(count).isZero();
    }

    // ── skillars-deferred-100 AC6: storage-key deletions ride the generic platform.outbox ──────────

    @Test
    void erase_playerUser_reportKeyGoesThroughOutbox_thenDrainClearsIt() {
        UUID reportId = UUID.randomUUID();
        String storageKey = "reports/" + reportId + "/report.pdf";
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO development.performance_reports "
                    + "(id, coach_id, player_id, generated_at, storage_key, next_steps) "
                    + "VALUES (?, ?, ?, ?, ?, 'x')",
                reportId, coachProfileId, SELF_PLAYER_PROFILE_ID, Timestamp.from(Instant.now()), storageKey);
            return null;
        });

        String cookies = loginAndGetCookies(SELF_PLAYER_EMAIL);
        httpTestClient.makeHttpRequest(
            baseUrl() + ERASURE_URL, HttpMethod.POST, null, authenticatedHeaders(cookies), Map.class);

        outboxService.drain();

        // The drain ran the S3 delete for the enqueued key and, on success, removed its outbox row.
        verify(fileStorageService).deleteRawBytes(storageKey);
        assertThat(blobOutboxRowCount(storageKey)).isZero();
    }

    @Test
    void erase_playerUser_s3DeleteFails_leavesOutboxRow_reDrivableOnceBackoffExpires() {
        UUID reportId = UUID.randomUUID();
        String storageKey = "reports/" + reportId + "/report.pdf";
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO development.performance_reports "
                    + "(id, coach_id, player_id, generated_at, storage_key, next_steps) "
                    + "VALUES (?, ?, ?, ?, ?, 'x')",
                reportId, coachProfileId, SELF_PLAYER_PROFILE_ID, Timestamp.from(Instant.now()), storageKey);
            return null;
        });
        doThrow(new RuntimeException("simulated S3 failure")).when(fileStorageService).deleteRawBytes(storageKey);

        String cookies = loginAndGetCookies(SELF_PLAYER_EMAIL);
        httpTestClient.makeHttpRequest(
            baseUrl() + ERASURE_URL, HttpMethod.POST, null, authenticatedHeaders(cookies), Map.class);

        outboxService.drain();

        // Erasure still completed (DB row gone) …
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM development.performance_reports WHERE id = ?", Integer.class, reportId)).isZero();
        // … and the failed S3 delete left its outbox row behind with attempts incremented and a
        // future next_attempt_at (the generic outbox's backoff — deferred-91 review D6).
        Integer attempts = jdbcTemplate.queryForObject(
            "SELECT attempts FROM main.outbox_messages WHERE aggregate_type = 'BLOB_DELETION' AND payload->>'storageKey' = ?",
            Integer.class, storageKey);
        assertThat(attempts).isEqualTo(1);

        // Re-drivable: with S3 healthy again and the backoff wound back, a plain drain empties it.
        reset(fileStorageService);
        transactionTemplate.execute(s -> jdbcTemplate.update(
            "UPDATE main.outbox_messages SET next_attempt_at = now() - interval '1 minute' "
                + "WHERE aggregate_type = 'BLOB_DELETION' AND payload->>'storageKey' = ?",
            storageKey));
        outboxService.drain();
        assertThat(blobOutboxRowCount(storageKey)).isZero();
    }

    private int blobOutboxRowCount(String storageKey) {
        Integer c = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.outbox_messages WHERE aggregate_type = 'BLOB_DELETION' AND payload->>'storageKey' = ?",
            Integer.class, storageKey);
        return c != null ? c : -1;
    }

    @Test
    void erase_playerUser_s3DeleteFails_erasureStillCompletes() {
        UUID reportId = UUID.randomUUID();
        String storageKey = "reports/" + reportId + "/report.pdf";
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO development.performance_reports "
                    + "(id, coach_id, player_id, generated_at, storage_key, next_steps) "
                    + "VALUES (?, ?, ?, ?, ?, 'Keep working on first touch')",
                reportId, coachProfileId, SELF_PLAYER_PROFILE_ID, Timestamp.from(Instant.now()), storageKey);
            return null;
        });
        doThrow(new RuntimeException("simulated S3 failure"))
            .when(fileStorageService).deleteRawBytes(storageKey);

        String cookies = loginAndGetCookies(SELF_PLAYER_EMAIL);
        httpTestClient.makeHttpRequest(
            baseUrl() + ERASURE_URL, HttpMethod.POST, null, authenticatedHeaders(cookies), Map.class);

        int count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM development.performance_reports WHERE id = ?", Integer.class, reportId);
        assertThat(count).isZero();
    }

    // ── skillars-deferred-91 AC13: the account-deletion video cascade runs with a transaction ──────

    @Test
    void erase_playerUser_videoCascade_purgesVideos_resetsQuota_cancelsPendingApprovals() {
        String ownerId = String.valueOf(PLAYER_ID);
        UUID videoId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.video_quotas (user_id, storage_used_bytes, bandwidth_used_bytes) VALUES (?, 5000, 0)",
                ownerId);
            jdbcTemplate.update(
                "INSERT INTO main.videos (id, owner_id, provider, provider_asset_id, operational_state, "
                    + "access_state, title, storage_bytes, visibility, created_at, updated_at) "
                    + "VALUES (?, ?, 'bunny', ?, 'READY', 'ACTIVE', 'GDPR Video', 5000, 'PRIVATE', ?, ?)",
                videoId, ownerId, "asset-" + videoId,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
            jdbcTemplate.update(
                "INSERT INTO main.video_approval_requests (id, video_id, player_id, parent_id, status, created_at) "
                    + "VALUES (?, ?, ?, ?, 'PENDING', ?)",
                approvalId, videoId, PLAYER_ID, PARENT_ID, Timestamp.from(Instant.now()));
            return null;
        });

        String cookies = loginAndGetCookies(PLAYER_EMAIL);
        httpTestClient.makeHttpRequest(
            baseUrl() + ERASURE_URL, HttpMethod.POST, null, authenticatedHeaders(cookies), Map.class);

        // Before the fix the @Modifying quota-reset UPDATE threw TransactionRequiredException on this
        // AFTER_COMMIT path (caught + logged upstream), so the cascade silently stopped there: the
        // video stayed READY and the quota row kept its 5000 bytes. All three now complete.
        Map<String, Object> video = jdbcTemplate.queryForMap(
            "SELECT operational_state, storage_bytes FROM main.videos WHERE id = ?", videoId);
        assertThat(video.get("operational_state")).isEqualTo("PURGED");
        assertThat(((Number) video.get("storage_bytes")).longValue()).isZero();

        Long quotaUsed = jdbcTemplate.queryForObject(
            "SELECT storage_used_bytes FROM main.video_quotas WHERE user_id = ?", Long.class, ownerId);
        assertThat(quotaUsed).isZero();

        String approvalStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM main.video_approval_requests WHERE id = ?", String.class, approvalId);
        assertThat(approvalStatus).isEqualTo("CANCELLED");
    }

    // ── skillars-deferred-127 AC1: shared player_profiles lock + PLAYER-branch id-resolution fix ──

    /**
     * story-review.md M4, point 3: this is the regression test proving the pre-existing PLAYER-path
     * no-op bug (B1's secondary finding) is closed — a self-registered PLAYER account's development
     * data is now genuinely deleted. Against pre-fix code this would fail for a DIFFERENT reason than
     * every other test in this file: not an exception, a silent no-op (the deletes ran against
     * {@code userId}, which essentially never equals {@code player_profiles.id}).
     */
    @Test
    void erase_selfRegisteredPlayer_withProfile_deletesPlayerDevelopmentData() {
        UUID reportId = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO development.performance_reports "
                    + "(id, coach_id, player_id, generated_at, storage_key, next_steps) "
                    + "VALUES (?, ?, ?, ?, ?, 'Keep working on first touch')",
                reportId, coachProfileId, SELF_PLAYER_PROFILE_ID, Timestamp.from(Instant.now()),
                "reports/" + reportId + "/report.pdf");
            return null;
        });

        erase(SELF_PLAYER_USER_ID);

        int count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM development.performance_reports WHERE player_id = ?",
            Integer.class, SELF_PLAYER_PROFILE_ID);
        assertThat(count).isZero();
    }

    /** story-review.md M4, point 4th bullet: proves the orElse-skip decision — no exception, COMPLETED. */
    @Test
    void erase_playerWithNoProfileRow_completesSuccessfullyWithoutError() {
        UUID requestId = erase(PLAYER_ID);

        String finalStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM admin.gdpr_requests WHERE id = ?", String.class, requestId);
        assertThat(finalStatus).isEqualTo("COMPLETED");
    }

    /**
     * story-review.md M4, point 1: a concurrency test mirroring
     * {@code RadarCompositeCalculationServiceConcurrencyIT}'s own locker-thread pattern (real
     * Testcontainers Postgres, real threads, full latch control) — starts {@code erase()} (called
     * directly) for a player whose {@code player_profiles} row lock is already held by a concurrent,
     * {@code recalculateComposite}-style caller (a raw {@code SELECT ... FOR UPDATE}, the exact lock
     * type {@code findByIdForUpdate} takes). Empirically observed: the lock is released well within
     * {@link com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer}'s ~3.2s bounded
     * retry budget, so {@code erase()} waits and then succeeds — it does not fail fast.
     *
     * <p>Code review 2026-09-21: the elapsed-time assertion below is load-bearing, not decorative —
     * without it, this test would pass identically whether or not {@code deletePlayerDevelopmentData}
     * takes any lock at all (the terminal assertions alone don't distinguish "waited then succeeded"
     * from "never contended"). Also uses this file's own {@code await(CountDownLatch)} helper rather
     * than a bare {@code lockHeld.await(...)} with its boolean result discarded, so a latch timeout
     * fails loudly instead of letting the eraser run against an unlocked row silently.
     */
    @Test
    void erase_blockedByCompetingPlayerProfileLock_waitsThenSucceeds() throws Exception {
        long lockHoldMillis = 1200;
        CountDownLatch lockHeld = new CountDownLatch(1);
        AtomicReference<Throwable> lockerFailure = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<?> locker = executor.submit(() -> {
            try {
                transactionTemplate.execute(status -> {
                    jdbcTemplate.queryForObject(
                        "SELECT id FROM main.player_profiles WHERE id = ? FOR UPDATE",
                        Long.class, SELF_PLAYER_PROFILE_ID);
                    lockHeld.countDown();
                    try {
                        Thread.sleep(lockHoldMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("Interrupted while holding the player_profiles lock", e);
                    }
                    return null;
                });
            } catch (Throwable t) {
                lockerFailure.set(t);
            }
        });

        AtomicReference<Throwable> eraseFailure = new AtomicReference<>();
        AtomicReference<Duration> eraseElapsed = new AtomicReference<>();
        Future<?> eraser = executor.submit(() -> {
            try {
                await(lockHeld);
                Instant start = Instant.now();
                erase(SELF_PLAYER_USER_ID);
                eraseElapsed.set(Duration.between(start, Instant.now()));
            } catch (Throwable t) {
                eraseFailure.set(t);
            }
        });

        try {
            locker.get(10, TimeUnit.SECONDS);
            eraser.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }

        assertThat(lockerFailure.get()).isNull();
        assertThat(eraseFailure.get()).isNull();
        assertThat(eraseElapsed.get())
            .as("erase() must have genuinely waited on the held player_profiles lock, not run "
                + "against an unlocked row — a floor safely below the %dms hold but well above "
                + "near-instant discriminates a real wait from no contention at all", lockHoldMillis)
            .isGreaterThanOrEqualTo(Duration.ofMillis(900));

        Boolean activated = jdbcTemplate.queryForObject(
            "SELECT activated FROM main.\"user\" WHERE id = ?", Boolean.class, SELF_PLAYER_USER_ID);
        assertThat(activated).isFalse();
    }

    /**
     * story-review.md M4, point 2: the resurrection regression test. Seeds a committed
     * {@code player_radar_composites}/{@code player_radar_baselines} pair (simulating a composite
     * already computed by a prior {@code recalculateComposite} run) and drives a
     * {@code recalculateComposite}-shaped writer (raw JDBC, mirroring
     * {@code RadarCompositeCalculationServiceConcurrencyIT}'s own deadlock test technique, since
     * production {@code recalculateComposite} has no injection point between its lock acquisition and
     * its upsert to synchronize on from outside) concurrently against a real {@code erase()} call.
     *
     * <p>The writer takes the SAME {@code player_profiles FOR UPDATE} lock {@code
     * findByIdForUpdate} takes, then holds it for a fixed, known duration ({@code LOCK_HOLD_MILLIS}
     * — mirroring {@code RadarCompositeCalculationServiceConcurrencyIT}'s own locker-thread pattern)
     * before performing its upsert and committing — representing data it "read" before acquiring the
     * lock, exactly as {@code recalculateComposite} reads its aggregates only after acquiring the
     * lock but could, on a slower path, still be mid-upsert when a concurrent {@code erase()} arrives.
     * <strong>The eraser thread only starts calling {@code erase()} once the writer has confirmed
     * lock acquisition ({@code writerLockAcquired}) — critically, it does NOT itself trigger the
     * writer's release</strong> (an earlier draft of this test had the eraser thread release the
     * writer's latch immediately before calling {@code erase()}, which let the writer's fast INSERTs
     * land and commit before {@code erase()} even began contending for the lock — the test passed
     * without the production lock existing at all, proving nothing; code review 2026-09-21). Because
     * the writer holds the lock for the ENTIRE {@code LOCK_HOLD_MILLIS} window regardless of anything
     * the eraser does, and {@code deletePlayerDevelopmentData} takes the identical lock before
     * deleting anything, {@code erase()} is guaranteed to be genuinely blocked/retrying for that whole
     * window — it cannot acquire the lock (and thus cannot delete) until the writer's transaction
     * commits, so the writer's insert can never land in between {@code erase()}'s delete and its
     * commit. This test therefore exercises exactly one, but the interesting, order deterministically
     * (writer commits first, {@code erase()} always runs second) rather than leaving it to scheduling
     * luck.
     *
     * <p><strong>Why this would have failed pre-fix (verified logically, story-review.md M4):</strong>
     * before this AC, {@code deletePlayerDevelopmentData} took no {@code player_profiles} lock at all.
     * The writer thread below could hold the lock for its full window exactly as it does here — but a
     * pre-fix {@code erase()} would not block behind that lock at all, so it could run its deletes and
     * commit WHILE the writer still holds the lock. Once the writer's own hold period ends and it
     * finally inserts and commits, its insert would land AFTER {@code erase()}'s already-committed
     * delete — a genuine resurrection: an erased player's development data reappears and nothing will
     * ever remove it again.
     */
    @Test
    void erase_concurrentWithRecalculateStyleWriter_doesNotResurrectRadarData() throws Exception {
        String skill = "PAC";
        long lockHoldMillis = 1200;
        seedCommittedRadarRows(SELF_PLAYER_PROFILE_ID, skill);

        CountDownLatch writerLockAcquired = new CountDownLatch(1);
        AtomicReference<Throwable> writerFailure = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<?> writer = executor.submit(() -> {
            try {
                transactionTemplate.execute(status -> {
                    jdbcTemplate.queryForObject(
                        "SELECT id FROM main.player_profiles WHERE id = ? FOR UPDATE",
                        Long.class, SELF_PLAYER_PROFILE_ID);
                    writerLockAcquired.countDown();
                    try {
                        Thread.sleep(lockHoldMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("Interrupted while holding the player_profiles lock", e);
                    }
                    // Mirrors PlayerRadarCompositeRepository.upsertComposite's exact statement shape.
                    jdbcTemplate.update(
                        "INSERT INTO development.player_radar_composites "
                            + "(player_id, skill_code, composite_score, entry_count, distinct_coach_count, last_updated_at) "
                            + "VALUES (?, ?, 60.0, 2, 1, now()) "
                            + "ON CONFLICT (player_id, skill_code) DO UPDATE SET "
                            + "composite_score = EXCLUDED.composite_score, entry_count = EXCLUDED.entry_count, "
                            + "distinct_coach_count = EXCLUDED.distinct_coach_count, "
                            + "last_updated_at = EXCLUDED.last_updated_at",
                        SELF_PLAYER_PROFILE_ID, skill);
                    jdbcTemplate.update(
                        "INSERT INTO development.player_radar_baselines "
                            + "(player_id, skill_code, baseline_score, recorded_at) "
                            + "VALUES (?, ?, 60.0, now()) ON CONFLICT DO NOTHING",
                        SELF_PLAYER_PROFILE_ID, skill);
                    return null;
                });
            } catch (Throwable t) {
                writerFailure.set(t);
            }
        });

        AtomicReference<Throwable> eraseFailure = new AtomicReference<>();
        Future<?> eraser = executor.submit(() -> {
            try {
                writerLockAcquired.await(10, TimeUnit.SECONDS);
                erase(SELF_PLAYER_USER_ID);
            } catch (Throwable t) {
                eraseFailure.set(t);
            }
        });

        try {
            writer.get(15, TimeUnit.SECONDS);
            eraser.get(15, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }

        assertThat(writerFailure.get()).isNull();
        assertThat(eraseFailure.get()).isNull();

        int compositeCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM development.player_radar_composites WHERE player_id = ?",
            Integer.class, SELF_PLAYER_PROFILE_ID);
        int baselineCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM development.player_radar_baselines WHERE player_id = ?",
            Integer.class, SELF_PLAYER_PROFILE_ID);
        assertThat(compositeCount).as("composite must not be resurrected after erasure").isZero();
        assertThat(baselineCount).as("baseline must not be resurrected after erasure").isZero();
    }

    private void seedCommittedRadarRows(long playerId, String skill) {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO development.player_radar_composites "
                    + "(player_id, skill_code, composite_score, entry_count, distinct_coach_count, last_updated_at) "
                    + "VALUES (?, ?, 55.0, 1, 1, now())",
                playerId, skill);
            jdbcTemplate.update(
                "INSERT INTO development.player_radar_baselines "
                    + "(player_id, skill_code, baseline_score, recorded_at) VALUES (?, ?, 55.0, now())",
                playerId, skill);
            return null;
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting on latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting latch", e);
        }
    }

    /** Seeds a PENDING admin.gdpr_requests row and calls {@code GdprErasureService.erase} directly —
     * bypassing the HTTP/{@code AFTER_COMMIT} path, whose listener swallows every exception, so a
     * test asserting {@code erase()}'s own thrown/propagated behavior must call it this way. */
    private UUID erase(long userId) {
        UUID requestId = UUID.randomUUID();
        // Must run inside an explicit transaction — this datasource's hikari auto-commit is false
        // (see AbstractIntegrationTest / DatabaseResetTestExecutionListener's own class Javadoc), so
        // a bare jdbcTemplate.update outside a transaction is silently never committed.
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO admin.gdpr_requests (id, user_id, request_type, status, created_at) "
                    + "VALUES (?, ?, 'ERASURE', 'PENDING', ?)",
                requestId, userId, Timestamp.from(Instant.now()));
            return null;
        });
        gdprErasureService.erase(requestId, userId);
        return requestId;
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
