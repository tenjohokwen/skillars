package com.softropic.skillars.platform.reviews.api;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.messaging.contract.ModerationVerdict;

import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class ReviewUpdateIT extends AbstractIntegrationTest {

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

            // skillars-deferred-145, round-2 (R16): the linked player's age tier is NOT
            // load-bearing. An earlier draft set AGE_13_17 because the then-specified AC2.b would
            // 403 a parent-of-adult; D2 deleted that rule, so a plain parent-linked player of any
            // age is eligible and this fixture's DOB only has to be a valid date.
            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Rev Update Player', ?, 'MIDFIELDER', 'AGE_13_17', ?, true, ?, 'system')",
                PLAYER_ID, Date.valueOf(LocalDate.now().minusYears(15)),
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

            // The ORIGINAL qualifying session behind the existing review — deliberately old (50 days)
            // so it predates every lastModifiedAt value used below (-10/-35 days), matching the
            // "original booking, no NEW session" shape updateReview_afterCooldownButNoNewSession_returns403
            // depends on. Still comfortably matured past the 7-day floor.
            insertCompletedBooking(Instant.now().minusSeconds(86400L * 50));

            // Existing APPROVED review — last_modified_at/author_last_edited_at both 400 days ago by
            // default (outside both the old 365-day gate and the new 30-day cooldown either way);
            // individual tests override them. author_last_edited_at mirrors last_modified_at here,
            // same as the V157 migration's own backfill for a pre-existing row -- most tests move
            // both together via setReviewEditedAt() below, matching "the review was actually edited
            // at time X"; the D1 regression test alone moves last_modified_at on its own to simulate
            // a non-author (admin) write.
            reviewId = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO reviews.coach_reviews " +
                "(review_id, coach_id, author_id, author_role, rating, body, moderation_status, " +
                " last_modified_at, author_last_edited_at, created_at) " +
                "VALUES (?, ?, ?, 'PARENT', 4, 'Original review', 'APPROVED', ?, ?, ?)",
                reviewId, coachProfileId, PARENT_ID,
                Timestamp.from(Instant.now().minusSeconds(86400L * 400)),
                Timestamp.from(Instant.now().minusSeconds(86400L * 400)),
                Timestamp.from(Instant.now().minusSeconds(86400L * 400)));

            return null;
        });
    }

    @Test
    void updateReview_withinCooldown_returns403() {
        setReviewEditedAt(Instant.now().minusSeconds(86400L * 10)); // inside the 30-day default

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

    /**
     * skillars-deferred-145 code review (Patch, 2026-10-06): restores the status-code assertion and
     * the coach-response-clearing / moderation-reset assertions that
     * {@code updateReview_afterOneYear_returns204} (removed by Task 7 as "superseded") used to carry
     * alongside its own now-irrelevant 365-day boundary. AC4 claims the coach-response-clearing and
     * moderation-reset-on-edit behaviours are "unchanged" by this story; this test is what actually
     * proves that, not just that the body text landed.
     */
    @Test
    void updateReview_afterCooldownWithNewSession_returns204() {
        // Round-2 code review (R3): stub geminiClient EXPLICITLY. Without it this test asserted
        // PENDING and passed for an accidental reason: the hoisted @MockitoBean returns null, null
        // does NOT trigger ReviewModerationService's catch (nothing is thrown), so its
        // switch (verdict) NPEs on a null enum selector and the listener never writes a status --
        // leaving the PENDING that updateReview itself set. That is the same accidental-precondition
        // pattern an earlier code review removed from this test's predecessor (which stubbed
        // thenThrow and asserted UNDER_REVIEW). The listener is @TransactionalEventListener(
        // AFTER_COMMIT) with no @Async, so it runs synchronously before the response returns and the
        // post-moderation status below is deterministic, not racy.
        when(geminiClient.evaluate(any())).thenThrow(
            new RuntimeException("moderation service unavailable"));
        setReviewEditedAt(Instant.now().minusSeconds(86400L * 35)); // outside the 30-day cooldown
        insertCompletedBooking(Instant.now().minusSeconds(86400L * 10)); // new, matured, after lastModifiedAt
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "UPDATE reviews.coach_reviews SET coach_response_body = ?, coach_response_at = ? "
                    + "WHERE review_id = ?",
                "Thanks for the feedback!", Timestamp.from(Instant.now().minusSeconds(86400L * 20)), reviewId);
            return null;
        });

        String parentCookies = loginAndGetCookies(PARENT_EMAIL);
        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            reviewsUrl("/" + reviewId),
            HttpMethod.PATCH,
            Map.of("rating", 5, "body", "Updated after cooldown"),
            authenticatedHeaders(parentCookies),
            Void.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT body, coach_response_body, coach_response_at, moderation_status "
                + "FROM reviews.coach_reviews WHERE review_id = ?::uuid", reviewId.toString());
        assertThat(row.get("body")).isEqualTo("Updated after cooldown");
        assertThat(row.get("coach_response_body"))
            .as("an edit must clear the coach's prior response").isNull();
        assertThat(row.get("coach_response_at"))
            .as("an edit must clear the coach's prior response timestamp").isNull();
        assertThat(row.get("moderation_status"))
            .as("an edit re-enters moderation; with the moderator unreachable it fails closed to "
                + "UNDER_REVIEW (PENDING is only the transient value updateReview writes before the "
                + "AFTER_COMMIT listener runs)")
            .isEqualTo("UNDER_REVIEW");
    }

    /**
     * Round-2 code review (R3): the sibling above pins the moderator-unreachable branch. This pins
     * the opposite one, which had no coverage on the EDIT path at all ({@code ReviewModerationIT}
     * covers only the submit route): an edit really does re-enter moderation and get re-verdicted,
     * rather than simply being left at whatever {@code updateReview} wrote. A SAFE verdict landing on
     * APPROVED is only reachable if the listener actually ran against the edited row.
     */
    @Test
    void updateReview_afterCooldownWithNewSession_safeVerdict_reApprovesTheEdit() {
        when(geminiClient.evaluate(any())).thenReturn(ModerationVerdict.SAFE);
        setReviewEditedAt(Instant.now().minusSeconds(86400L * 35));
        insertCompletedBooking(Instant.now().minusSeconds(86400L * 10));

        String parentCookies = loginAndGetCookies(PARENT_EMAIL);
        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            reviewsUrl("/" + reviewId),
            HttpMethod.PATCH,
            Map.of("rating", 5, "body", "Edited and re-approved"),
            authenticatedHeaders(parentCookies),
            Void.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        String moderationStatus = jdbcTemplate.queryForObject(
            "SELECT moderation_status FROM reviews.coach_reviews WHERE review_id = ?::uuid",
            String.class, reviewId.toString());
        assertThat(moderationStatus)
            .as("a SAFE verdict on the edited body must re-approve it, proving the edit really "
                + "re-entered moderation")
            .isEqualTo("APPROVED");
    }

    /**
     * skillars-deferred-145 code review (Patch, 2026-10-06): brackets the 30-day cooldown to ±1 day.
     *
     * <p><strong>Round-2 correction (R5): this does NOT kill an off-by-one on the {@code isAfter}
     * comparison, which an earlier version of this javadoc claimed.</strong> 29 days fails and 31
     * days passes under either boundary convention; only a fixture exactly on the cooldown instant
     * could distinguish them, which needs an injectable clock. What these two genuinely pin is the
     * configured value to within a day.
     */
    @Test
    void updateReview_oneDayWithinCooldown_returns403() {
        setReviewEditedAt(Instant.now().minusSeconds(86400L * 29));

        String parentCookies = loginAndGetCookies(PARENT_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            reviewsUrl("/" + reviewId),
            HttpMethod.PATCH,
            Map.of("rating", 5, "body", "One day within cooldown"),
            authenticatedHeaders(parentCookies),
            Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(ex.getResponseBodyAsString()).contains("reviews.updateTooSoon");
            });
    }

    /** Mirrors the test above from the other side of the same 30-day boundary. */
    @Test
    void updateReview_oneDayPastCooldown_returns204() {
        setReviewEditedAt(Instant.now().minusSeconds(86400L * 31));
        insertCompletedBooking(Instant.now().minusSeconds(86400L * 15)); // new, matured, after lastModifiedAt

        String parentCookies = loginAndGetCookies(PARENT_EMAIL);
        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            reviewsUrl("/" + reviewId),
            HttpMethod.PATCH,
            Map.of("rating", 5, "body", "One day past cooldown"),
            authenticatedHeaders(parentCookies),
            Void.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    /**
     * skillars-deferred-145 code review (D1, 2026-10-06 — Patch, "add a test proving an admin
     * approval between two author edits does not void a session completed before it"). Simulates
     * AdminReviewService.approveReview's effect in isolation: it bumps last_modified_at (and only
     * that column) without being an author edit, so author_last_edited_at stays at the real last
     * edit. A qualifying booking that occurred AFTER that real edit but BEFORE the simulated
     * approval must still count as a "new" session under the fix (anchored on
     * author_last_edited_at) — under the pre-fix bug (anchored on last_modified_at), that same
     * booking would predate the admin-bumped timestamp and be wrongly rejected as stale.
     */
    @Test
    void updateReview_qualifyingSessionBetweenLastEditAndAdminApproval_stillCounts_returns204() {
        transactionTemplate.execute(status -> {
            // author_last_edited_at: the real last author edit, 40 days ago.
            jdbcTemplate.update(
                "UPDATE reviews.coach_reviews SET author_last_edited_at = ? WHERE review_id = ?",
                Timestamp.from(Instant.now().minusSeconds(86400L * 40)), reviewId);
            return null;
        });
        // Simulates AdminReviewService.approveReview: bumps ONLY last_modified_at, 31 days ago —
        // outside the 30-day cooldown, but (pre-fix) would have reset sinceAfter to this point.
        setReviewLastModifiedAt(Instant.now().minusSeconds(86400L * 31));
        // The qualifying session: occurred after the real author edit (-40d) but before the
        // simulated admin approval (-31d), and comfortably matured.
        insertCompletedBooking(Instant.now().minusSeconds(86400L * 35));

        String parentCookies = loginAndGetCookies(PARENT_EMAIL);
        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            reviewsUrl("/" + reviewId),
            HttpMethod.PATCH,
            Map.of("rating", 5, "body", "Edit after an intervening admin approval"),
            authenticatedHeaders(parentCookies),
            Void.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    /**
     * skillars-deferred-145 code review (Patch, 2026-10-06): AC4 re-runs AC2.a/b/c on update, but no
     * test exercised the dispute gate (AC2.c) on the update path at all.
     */
    @Test
    void updateReview_activeDisputeOnOtherBooking_returns403() {
        setReviewEditedAt(Instant.now().minusSeconds(86400L * 35)); // outside the 30-day cooldown
        insertCompletedBooking(Instant.now().minusSeconds(86400L * 10)); // new, matured, after lastModifiedAt
        transactionTemplate.execute(status -> {
            UUID otherBookingId = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO booking.bookings " +
                "(id, coach_id, parent_id, player_id, status, requested_start_time, requested_end_time, " +
                " version, created_at, updated_at, canonical_timezone) " +
                "VALUES (?, ?, ?, ?, 'COMPLETED', ?, ?, 0, ?, ?, 'Europe/Berlin')",
                otherBookingId, coachProfileId, PARENT_ID, PLAYER_ID,
                Timestamp.from(Instant.now().minusSeconds(20L * 86400)),
                Timestamp.from(Instant.now().minusSeconds(20L * 86400)),
                Timestamp.from(Instant.now().minusSeconds(21L * 86400)),
                Timestamp.from(Instant.now().minusSeconds(20L * 86400)));
            jdbcTemplate.update(
                "INSERT INTO admin.disputes " +
                "(id, booking_id, raised_by, raised_by_role, reason, details, status, created_at) " +
                "VALUES (?, ?, ?, 'PARENT', 'OTHER', 'Test dispute', 'OPEN', ?)",
                UUID.randomUUID(), otherBookingId, PARENT_ID, Timestamp.from(Instant.now()));
            return null;
        });

        String parentCookies = loginAndGetCookies(PARENT_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            reviewsUrl("/" + reviewId),
            HttpMethod.PATCH,
            Map.of("rating", 5, "body", "Despite the dispute"),
            authenticatedHeaders(parentCookies),
            Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(ex.getResponseBodyAsString()).contains("reviews.activeDispute");
            });
    }

    @Test
    void updateReview_afterCooldownButNoNewSession_returns403() {
        setReviewEditedAt(Instant.now().minusSeconds(86400L * 35)); // outside the 30-day cooldown
        // No new booking inserted — the only qualifying booking is setUp()'s 50-day-old one, which
        // predates this lastModifiedAt, so it does not count as a NEW session.

        String parentCookies = loginAndGetCookies(PARENT_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            reviewsUrl("/" + reviewId),
            HttpMethod.PATCH,
            Map.of("rating", 5, "body", "No new session"),
            authenticatedHeaders(parentCookies),
            Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(ex.getResponseBodyAsString()).contains("reviews.noQualifyingSession");
            });
    }

    @Test
    void updateReview_newSessionNotYetMatured_returns403() {
        setReviewEditedAt(Instant.now().minusSeconds(86400L * 35)); // outside the 30-day cooldown
        insertCompletedBooking(Instant.now().minusSeconds(86400L)); // after lastModifiedAt, but not matured

        String parentCookies = loginAndGetCookies(PARENT_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            reviewsUrl("/" + reviewId),
            HttpMethod.PATCH,
            Map.of("rating", 5, "body", "Too fresh"),
            authenticatedHeaders(parentCookies),
            Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(ex.getResponseBodyAsString()).contains("reviews.noQualifyingSession");
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

    /**
     * Moves {@code last_modified_at} alone — simulates a non-author write (an admin approval/block
     * or a flag auto-hold), which never touches {@code author_last_edited_at}. Used only by the D1
     * regression test; every other test wants {@link #setReviewEditedAt} instead.
     */
    private void setReviewLastModifiedAt(Instant lastModifiedAt) {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "UPDATE reviews.coach_reviews SET last_modified_at = ? WHERE review_id = ?",
                Timestamp.from(lastModifiedAt), reviewId);
            return null;
        });
    }

    /** Moves both {@code last_modified_at} and {@code author_last_edited_at} together — simulates a real author edit at the given instant. */
    private void setReviewEditedAt(Instant editedAt) {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "UPDATE reviews.coach_reviews SET last_modified_at = ?, author_last_edited_at = ? WHERE review_id = ?",
                Timestamp.from(editedAt), Timestamp.from(editedAt), reviewId);
            return null;
        });
    }

    private void insertCompletedBooking(Instant updatedAt) {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO booking.bookings " +
                "(id, coach_id, parent_id, player_id, status, requested_start_time, requested_end_time, " +
                " version, created_at, updated_at, canonical_timezone) " +
                "VALUES (?, ?, ?, ?, 'COMPLETED', ?, ?, 0, ?, ?, 'Europe/Berlin')",
                UUID.randomUUID(), coachProfileId, PARENT_ID, PLAYER_ID,
                Timestamp.from(updatedAt.minusSeconds(3600)),
                Timestamp.from(updatedAt),
                Timestamp.from(updatedAt.minusSeconds(86400 * 7)),
                Timestamp.from(updatedAt));
            return null;
        });
    }

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
