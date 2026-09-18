package com.softropic.skillars.platform.admin.api;

import com.softropic.skillars.config.AbstractIntegrationTest;

import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.payment.service.ReliabilityStrikeConfig;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class ManualStrikeIT extends AbstractIntegrationTest {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String CLIENT_ID      = "testClientId";
    private static final String TEST_PASSWORD  = "TestPass@123!";

    private static final long ADMIN_ID      = 9070_000_100L;
    private static final long COACH_USER_ID = 9070_000_010L;

    private static final String ADMIN_EMAIL = "admin.manualstrike9070@skillars-test.com";
    private static final String COACH_EMAIL = "coach.manualstrike9070@skillars-test.com";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ConfigService configService;

    @LocalServerPort private int randomServerPort;

    private UUID coachProfileId;
    private UUID bookingId;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        coachProfileId = UUID.randomUUID();
        bookingId = UUID.randomUUID();

        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) VALUES (9070, 'ROLE_COACH', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) VALUES (9071, 'ROLE_ADMIN', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));

            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH");
            grantAuthority(COACH_USER_ID, "ROLE_COACH");

            insertUser(ADMIN_ID, ADMIN_EMAIL, passwordHash, "ADMIN");
            grantAuthority(ADMIN_ID, "ROLE_ADMIN");

            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles (id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, 'Strike Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
                coachProfileId, COACH_USER_ID);

            jdbcTemplate.update(
                "INSERT INTO booking.bookings (id, parent_id, player_id, coach_id, requested_start_time, requested_end_time, status, canonical_timezone, version, created_at, updated_at) " +
                "VALUES (?, 9070999001, 9070999002, ?, ?, ?, 'COMPLETED', 'Europe/Berlin', 0, ?, ?)",
                bookingId, coachProfileId,
                Timestamp.from(Instant.now().minusSeconds(7200)), Timestamp.from(Instant.now().minusSeconds(3600)),
                Timestamp.from(Instant.now().minusSeconds(7200)), Timestamp.from(Instant.now()));

            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM admin.admin_action_log WHERE reference_id = ?", coachProfileId.toString());
            jdbcTemplate.update("DELETE FROM admin.admin_alerts WHERE reference_id = ?", coachProfileId.toString());
            jdbcTemplate.update("DELETE FROM marketplace.coach_reliability_strikes WHERE coach_id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM booking.bookings WHERE coach_id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id = ?", coachProfileId);
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?)", COACH_USER_ID, ADMIN_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?)", COACH_USER_ID, ADMIN_ID);
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id IN (9070, 9071)");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void issueManualStrike_insertsStrikeRow() {
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/admin/coaches/" + coachProfileId + "/strikes",
            HttpMethod.POST,
            Map.of("bookingId", bookingId.toString(), "reason", "COACH_NO_SHOW"),
            authenticatedHeaders(adminCookies), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).containsKey("strikeId");

        Long strikeCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM marketplace.coach_reliability_strikes WHERE coach_id = ?",
            Long.class, coachProfileId);
        assertThat(strikeCount).isEqualTo(1L);
    }

    /**
     * skillars-deferred-123 AC1: a coach already off the marketplace has no enforcement value in
     * further escalation. Seeds 4 in-window strikes (suspensionThreshold=5 default) against a coach
     * manually set to SUSPENDED, then issues one more via the manual (admin) path — the fresh count
     * (5) would, pre-fix, flip the coach to PENDING_REVIEW and publish a StrikeThresholdReachedEvent
     * (which resolveOpenStrikeAlert's sibling would surface as a new OPEN admin_alerts row). Post-fix
     * the strike is still recorded (compliance record, not a rejected call — 201, not 409) but the
     * coach's status and statusChangedAt are unchanged and no alert is created.
     */
    @Test
    void issueManualStrike_againstSuspendedCoach_recordsStrikeWithoutEscalation() {
        Timestamp originalStatusChangedAt = Timestamp.from(Instant.now().minusSeconds(600));
        transactionTemplate.execute(status -> {
            for (int i = 0; i < 4; i++) {
                jdbcTemplate.update(
                    "INSERT INTO marketplace.coach_reliability_strikes (id, coach_id, booking_id, reason, created_at, acknowledged) VALUES (?, ?, ?, 'COACH_NO_SHOW', ?, false)",
                    UUID.randomUUID(), coachProfileId, bookingId, Timestamp.from(Instant.now()));
            }
            jdbcTemplate.update(
                "UPDATE marketplace.coach_profiles SET status = 'SUSPENDED', status_changed_at = ? WHERE id = ?",
                originalStatusChangedAt, coachProfileId);
            return null;
        });

        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/admin/coaches/" + coachProfileId + "/strikes",
            HttpMethod.POST,
            Map.of("bookingId", bookingId.toString(), "reason", "COACH_NO_SHOW"),
            authenticatedHeaders(adminCookies), Map.class);

        assertThat(resp.getStatusCode())
            .as("a strike against an already-off-marketplace coach is suppressed, not rejected")
            .isEqualTo(HttpStatus.CREATED);

        Long strikeCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM marketplace.coach_reliability_strikes WHERE coach_id = ?",
            Long.class, coachProfileId);
        assertThat(strikeCount).as("the strike remains a durable compliance record").isEqualTo(5L);

        Map<String, Object> coachRow = jdbcTemplate.queryForMap(
            "SELECT status, status_changed_at FROM marketplace.coach_profiles WHERE id = ?", coachProfileId);
        assertThat(coachRow.get("status")).isEqualTo("SUSPENDED");
        assertThat(((Timestamp) coachRow.get("status_changed_at")).toInstant())
            .as("no escalation side effect — statusChangedAt must not move")
            .isEqualTo(originalStatusChangedAt.toInstant());

        Long alertCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM admin.admin_alerts WHERE reference_id = ? AND type = 'STRIKE_THRESHOLD'",
            Long.class, coachProfileId.toString());
        assertThat(alertCount).as("no StrikeThresholdReachedEvent was published").isEqualTo(0L);
    }

    /** Mirrors {@link #issueManualStrike_againstSuspendedCoach_recordsStrikeWithoutEscalation()} for DEACTIVATED. */
    @Test
    void issueManualStrike_againstDeactivatedCoach_recordsStrikeWithoutEscalation() {
        transactionTemplate.execute(status -> {
            for (int i = 0; i < 4; i++) {
                jdbcTemplate.update(
                    "INSERT INTO marketplace.coach_reliability_strikes (id, coach_id, booking_id, reason, created_at, acknowledged) VALUES (?, ?, ?, 'COACH_NO_SHOW', ?, false)",
                    UUID.randomUUID(), coachProfileId, bookingId, Timestamp.from(Instant.now()));
            }
            jdbcTemplate.update("UPDATE marketplace.coach_profiles SET status = 'DEACTIVATED' WHERE id = ?", coachProfileId);
            return null;
        });

        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/admin/coaches/" + coachProfileId + "/strikes",
            HttpMethod.POST,
            Map.of("bookingId", bookingId.toString(), "reason", "COACH_NO_SHOW"),
            authenticatedHeaders(adminCookies), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Long strikeCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM marketplace.coach_reliability_strikes WHERE coach_id = ?",
            Long.class, coachProfileId);
        assertThat(strikeCount).isEqualTo(5L);

        String coachStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM marketplace.coach_profiles WHERE id = ?", String.class, coachProfileId);
        assertThat(coachStatus).isEqualTo("DEACTIVATED");
    }

    /**
     * Regression guard for AC1: an ACTIVE coach (not off the marketplace) must still escalate exactly
     * as before — the deny-list guard must not suppress escalation for any status other than SUSPENDED
     * or DEACTIVATED.
     */
    @Test
    void issueManualStrike_againstActiveCoach_stillEscalatesAtThreshold() {
        transactionTemplate.execute(status -> {
            for (int i = 0; i < 4; i++) {
                jdbcTemplate.update(
                    "INSERT INTO marketplace.coach_reliability_strikes (id, coach_id, booking_id, reason, created_at, acknowledged) VALUES (?, ?, ?, 'COACH_NO_SHOW', ?, false)",
                    UUID.randomUUID(), coachProfileId, bookingId, Timestamp.from(Instant.now()));
            }
            return null;
        });

        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        httpTestClient.makeHttpRequest(
            baseUrl() + "/api/admin/coaches/" + coachProfileId + "/strikes",
            HttpMethod.POST,
            Map.of("bookingId", bookingId.toString(), "reason", "COACH_NO_SHOW"),
            authenticatedHeaders(adminCookies), Map.class);

        String coachStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM marketplace.coach_profiles WHERE id = ?", String.class, coachProfileId);
        assertThat(coachStatus)
            .as("count(5) >= suspensionThreshold(5) for an ACTIVE coach must still escalate")
            .isEqualTo("PENDING_REVIEW");

        jdbcTemplate.update("DELETE FROM admin.admin_alerts WHERE reference_id = ?", coachProfileId.toString());
    }

    @Test
    void deleteStrike_thatWasFinalStrike_revertsStatusToActiveAndResolvesAlert() {
        // Seed 3 strikes → coach becomes PENDING_REVIEW
        UUID strikeToDelete = UUID.randomUUID();
        UUID alertId = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            for (int i = 0; i < 2; i++) {
                jdbcTemplate.update(
                    "INSERT INTO marketplace.coach_reliability_strikes (id, coach_id, booking_id, reason, created_at, acknowledged) VALUES (?, ?, ?, 'COACH_NO_SHOW', ?, false)",
                    UUID.randomUUID(), coachProfileId, bookingId, Timestamp.from(Instant.now()));
            }
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_reliability_strikes (id, coach_id, booking_id, reason, created_at, acknowledged) VALUES (?, ?, ?, 'COACH_NO_SHOW', ?, false)",
                strikeToDelete, coachProfileId, bookingId, Timestamp.from(Instant.now()));
            jdbcTemplate.update("UPDATE marketplace.coach_profiles SET status = 'PENDING_REVIEW' WHERE id = ?", coachProfileId);
            jdbcTemplate.update(
                "INSERT INTO admin.admin_alerts (alert_id, type, reference_id, reference_type, status, created_at) " +
                "VALUES (?, 'STRIKE_THRESHOLD', ?, 'COACH', 'OPEN', ?)",
                alertId, coachProfileId.toString(), Timestamp.from(Instant.now()));
            return null;
        });

        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        ResponseEntity<Void> resp = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/admin/coaches/" + coachProfileId + "/strikes/" + strikeToDelete + "?reason=Issued+in+error",
            HttpMethod.DELETE, null, authenticatedHeaders(adminCookies), Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        String coachStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM marketplace.coach_profiles WHERE id = ?", String.class, coachProfileId);
        assertThat(coachStatus).isEqualTo("ACTIVE");

        String alertStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM admin.admin_alerts WHERE alert_id = ?", String.class, alertId);
        assertThat(alertStatus).isEqualTo("RESOLVED");

        jdbcTemplate.update("DELETE FROM admin.admin_alerts WHERE alert_id = ?", alertId);
    }

    /**
     * skillars-deferred-122 AC1: renamed from {@code deleteStrike_noStatusChange_doesNotResolveAlert},
     * which asserted the pre-fix bug (staying {@code PENDING_REVIEW}) as if it were correct behavior.
     * Seed 5 strikes → {@code PENDING_REVIEW}; deleting 1 → fresh count 4, which is
     * {@code >= visibilityThreshold(3)} and {@code < suspensionThreshold(5)} — the coach must
     * transition to {@code REDUCED} under the corrected tiering, and the alert must stay {@code OPEN}
     * (only a full {@code ACTIVE} clear resolves it).
     */
    @Test
    void deleteStrike_countDropsIntoReducedBand_revertsToReducedAlertStaysOpen() {
        UUID strikeToDelete = UUID.randomUUID();
        UUID alertId = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            for (int i = 0; i < 4; i++) {
                jdbcTemplate.update(
                    "INSERT INTO marketplace.coach_reliability_strikes (id, coach_id, booking_id, reason, created_at, acknowledged) VALUES (?, ?, ?, 'COACH_NO_SHOW', ?, false)",
                    UUID.randomUUID(), coachProfileId, bookingId, Timestamp.from(Instant.now()));
            }
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_reliability_strikes (id, coach_id, booking_id, reason, created_at, acknowledged) VALUES (?, ?, ?, 'COACH_NO_SHOW', ?, false)",
                strikeToDelete, coachProfileId, bookingId, Timestamp.from(Instant.now()));
            jdbcTemplate.update("UPDATE marketplace.coach_profiles SET status = 'PENDING_REVIEW' WHERE id = ?", coachProfileId);
            jdbcTemplate.update(
                "INSERT INTO admin.admin_alerts (alert_id, type, reference_id, reference_type, status, created_at) " +
                "VALUES (?, 'STRIKE_THRESHOLD', ?, 'COACH', 'OPEN', ?)",
                alertId, coachProfileId.toString(), Timestamp.from(Instant.now()));
            return null;
        });

        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        httpTestClient.makeHttpRequest(
            baseUrl() + "/api/admin/coaches/" + coachProfileId + "/strikes/" + strikeToDelete + "?reason=Partial+review",
            HttpMethod.DELETE, null, authenticatedHeaders(adminCookies), Void.class);

        String coachStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM marketplace.coach_profiles WHERE id = ?", String.class, coachProfileId);
        assertThat(coachStatus).isEqualTo("REDUCED");

        String alertStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM admin.admin_alerts WHERE alert_id = ?", String.class, alertId);
        assertThat(alertStatus)
            .as("only a full ACTIVE clear resolves the STRIKE_THRESHOLD alert, not a REDUCED transition")
            .isEqualTo("OPEN");

        jdbcTemplate.update("DELETE FROM admin.admin_alerts WHERE alert_id = ?", alertId);
    }

    /**
     * skillars-deferred-122 AC1: the corrected top tier. A coach whose fresh post-delete count is
     * still {@code >= suspensionThreshold} must stay {@code PENDING_REVIEW}, not fall through to
     * {@code REDUCED} — gating the {@code REDUCED} transition on {@code count >= visibilityThreshold}
     * alone (without also requiring {@code count < suspensionThreshold}) would de-escalate a coach
     * still above the suspension bar. Seeds 8 strikes (suspensionThreshold=5 default); deleting 1
     * leaves a fresh count of 7, still {@code >= 5}.
     */
    @Test
    void deleteStrike_countStillAtOrAboveSuspensionThreshold_staysPendingReview() {
        UUID strikeToDelete = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            for (int i = 0; i < 7; i++) {
                jdbcTemplate.update(
                    "INSERT INTO marketplace.coach_reliability_strikes (id, coach_id, booking_id, reason, created_at, acknowledged) VALUES (?, ?, ?, 'COACH_NO_SHOW', ?, false)",
                    UUID.randomUUID(), coachProfileId, bookingId, Timestamp.from(Instant.now()));
            }
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_reliability_strikes (id, coach_id, booking_id, reason, created_at, acknowledged) VALUES (?, ?, ?, 'COACH_NO_SHOW', ?, false)",
                strikeToDelete, coachProfileId, bookingId, Timestamp.from(Instant.now()));
            jdbcTemplate.update("UPDATE marketplace.coach_profiles SET status = 'PENDING_REVIEW' WHERE id = ?", coachProfileId);
            return null;
        });

        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        httpTestClient.makeHttpRequest(
            baseUrl() + "/api/admin/coaches/" + coachProfileId + "/strikes/" + strikeToDelete + "?reason=Still+elevated",
            HttpMethod.DELETE, null, authenticatedHeaders(adminCookies), Void.class);

        String coachStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM marketplace.coach_profiles WHERE id = ?", String.class, coachProfileId);
        assertThat(coachStatus)
            .as("count(7) >= suspensionThreshold(5) after the delete — must not de-escalate to REDUCED")
            .isEqualTo("PENDING_REVIEW");

        Long deletedLogCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM admin.admin_action_log WHERE reference_id = ? AND action_type = 'COACH_STRIKE_DELETED'",
            Long.class, coachProfileId.toString());
        assertThat(deletedLogCount).isEqualTo(1L);
    }

    /**
     * skillars-deferred-122 AC1: the out-of-window guard. A coach already below
     * {@code visibilityThreshold} (2 in-window strikes) but manually set to {@code PENDING_REVIEW} has
     * a 40-day-old (already not counted) strike deleted — this must produce no status/alert change,
     * but must still write the existing {@code COACH_STRIKE_DELETED} action-log row (every
     * {@code deleteStrike} call writes exactly one, on every branch — the out-of-window guard only
     * suppresses the status/alert side effects, not the log write).
     */
    @Test
    void deleteStrike_deletedStrikeOutOfThirtyDayWindow_noStatusChangeButLogsAction() {
        UUID strikeToDelete = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            for (int i = 0; i < 2; i++) {
                jdbcTemplate.update(
                    "INSERT INTO marketplace.coach_reliability_strikes (id, coach_id, booking_id, reason, created_at, acknowledged) VALUES (?, ?, ?, 'COACH_NO_SHOW', ?, false)",
                    UUID.randomUUID(), coachProfileId, bookingId, Timestamp.from(Instant.now()));
            }
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_reliability_strikes (id, coach_id, booking_id, reason, created_at, acknowledged) VALUES (?, ?, ?, 'COACH_NO_SHOW', ?, false)",
                strikeToDelete, coachProfileId, bookingId,
                Timestamp.from(Instant.now().minusSeconds(40L * 86400L)));
            jdbcTemplate.update("UPDATE marketplace.coach_profiles SET status = 'PENDING_REVIEW' WHERE id = ?", coachProfileId);
            return null;
        });

        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        httpTestClient.makeHttpRequest(
            baseUrl() + "/api/admin/coaches/" + coachProfileId + "/strikes/" + strikeToDelete + "?reason=Stale+strike+cleanup",
            HttpMethod.DELETE, null, authenticatedHeaders(adminCookies), Void.class);

        String coachStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM marketplace.coach_profiles WHERE id = ?", String.class, coachProfileId);
        assertThat(coachStatus)
            .as("the deleted strike was already out of the 30-day window — no status change")
            .isEqualTo("PENDING_REVIEW");

        Long deletedLogCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM admin.admin_action_log WHERE reference_id = ? AND action_type = 'COACH_STRIKE_DELETED'",
            Long.class, coachProfileId.toString());
        assertThat(deletedLogCount)
            .as("the out-of-window guard suppresses the status change, not the action-log row")
            .isEqualTo(1L);
    }

    /**
     * skillars-deferred-122 AC2: a strike that no longer exists (already deleted by an earlier,
     * fully-completed call) must see a clean 404, not the {@code StaleStateException} the old
     * entity-based {@code deleteById} threw for a 0-affected-row delete.
     * <p>
     * Code review 2026-09-18: this <em>sequential</em> shape only exercises the ownership-check
     * {@code ResourceNotFoundException} at {@code AdminCoachEnforcementService.java}'s
     * {@code findById(strikeId).orElseThrow(...)} — the second call's strike no longer exists by the
     * time its own ownership check runs, so it 404s before {@code deleteByIdAndCoachId} is ever
     * called. It does not reach AC2's actual {@code deletedRows == 0} branch, which only a genuine
     * concurrent duplicate delete (both callers passing the ownership check before either commits) can
     * exercise — see
     * {@code AdminCoachEnforcementConcurrencyIT#deleteStrike_concurrentDuplicateDelete_loserGets404NotStaleStateException}
     * for that coverage.
     */
    @Test
    void deleteStrike_alreadyDeleted_returns404NotStaleStateException() {
        UUID strikeToDelete = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_reliability_strikes (id, coach_id, booking_id, reason, created_at, acknowledged) VALUES (?, ?, ?, 'COACH_NO_SHOW', ?, false)",
                strikeToDelete, coachProfileId, bookingId, Timestamp.from(Instant.now()));
            return null;
        });

        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        ResponseEntity<Void> firstDelete = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/admin/coaches/" + coachProfileId + "/strikes/" + strikeToDelete + "?reason=First+delete",
            HttpMethod.DELETE, null, authenticatedHeaders(adminCookies), Void.class);
        assertThat(firstDelete.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/admin/coaches/" + coachProfileId + "/strikes/" + strikeToDelete + "?reason=Duplicate+delete",
            HttpMethod.DELETE, null, authenticatedHeaders(adminCookies), Void.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));
    }

    /**
     * skillars-deferred-122 AC4 / code review 2026-09-18: a live runtime misconfiguration
     * (visibilityThreshold > suspensionThreshold, set through the same {@code ConfigService.updateConfig}
     * write path an operator's {@code PUT /api/config} call would use — bypassing the boot-time
     * {@code ConfigStartupAssertion} check entirely, since neither key is registered in
     * {@code ConfigBounds.ALL}) still must not let {@code deleteStrike} wrongly revert/reduce a coach
     * whose fresh count is still above the suspension bar. Seeds 8 strikes (fresh post-delete count 7,
     * {@code >= suspensionThreshold(5)}).
     * <p>
     * Renamed from {@code ..._clampedAsIfEqualToSuspensionThreshold}: code review 2026-09-18 found the
     * {@code Math.min} read-time clamp this test's old name and docstring credited was unreachable dead
     * code — this branch (tier 1, {@code count >= suspensionThreshold}) is evaluated and short-circuits
     * before the visibility tier is ever reached, so the outcome here is identical with or without any
     * clamp. What actually prevents the wrongful revert is the suspension-first tier <em>ordering</em>,
     * which this test now documents accurately. See
     * {@link #deleteStrike_misconfiguredThresholdOrdering_reducedTierBecomesUnreachable} for the real,
     * documented effect of this misconfiguration (a silently-skipped REDUCED tier).
     */
    @Test
    void deleteStrike_misconfiguredThresholdOrdering_tierOrderingStillPreventsWrongfulRevert() {
        UUID strikeToDelete = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            for (int i = 0; i < 7; i++) {
                jdbcTemplate.update(
                    "INSERT INTO marketplace.coach_reliability_strikes (id, coach_id, booking_id, reason, created_at, acknowledged) VALUES (?, ?, ?, 'COACH_NO_SHOW', ?, false)",
                    UUID.randomUUID(), coachProfileId, bookingId, Timestamp.from(Instant.now()));
            }
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_reliability_strikes (id, coach_id, booking_id, reason, created_at, acknowledged) VALUES (?, ?, ?, 'COACH_NO_SHOW', ?, false)",
                strikeToDelete, coachProfileId, bookingId, Timestamp.from(Instant.now()));
            jdbcTemplate.update("UPDATE marketplace.coach_profiles SET status = 'PENDING_REVIEW' WHERE id = ?", coachProfileId);
            return null;
        });

        String originalVisibility = String.valueOf(ReliabilityStrikeConfig.DEFAULT_VISIBILITY_THRESHOLD);
        try {
            // Misconfigured pair: visibilityThreshold(10) > suspensionThreshold(5, seeded default).
            configService.updateConfig(ReliabilityStrikeConfig.VISIBILITY_THRESHOLD_KEY, "10");

            String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
            httpTestClient.makeHttpRequest(
                baseUrl() + "/api/admin/coaches/" + coachProfileId + "/strikes/" + strikeToDelete + "?reason=Misconfigured+pair",
                HttpMethod.DELETE, null, authenticatedHeaders(adminCookies), Void.class);

            String coachStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM marketplace.coach_profiles WHERE id = ?", String.class, coachProfileId);
            assertThat(coachStatus)
                .as("count(7) >= suspensionThreshold(5) — tier-1 ordering alone, not any clamp, must "
                    + "keep the misconfigured, unclamped visibilityThreshold(10) from producing a "
                    + "wrongful revert/reduce")
                .isEqualTo("PENDING_REVIEW");
        } finally {
            configService.updateConfig(ReliabilityStrikeConfig.VISIBILITY_THRESHOLD_KEY, originalVisibility);
        }
    }

    /**
     * Code review 2026-09-18: the real, accepted effect of {@code visibilityThreshold >
     * suspensionThreshold} — {@code deleteStrike}'s REDUCED tier ({@code count >= visibilityThreshold},
     * only reached once {@code count < suspensionThreshold}) becomes permanently unreachable, because
     * {@code visibilityThreshold(10)} then exceeds every {@code count} that can still reach that
     * branch. A coach whose fresh count would correctly land in the {@code REDUCED} band under the
     * seeded default ({@code visibilityThreshold=3}) instead skips straight to {@code ACTIVE}. Seeds 5
     * strikes (fresh post-delete count 4: {@code < suspensionThreshold(5)}, and, under the correct
     * default, {@code >= visibilityThreshold(3)} — a REDUCED case per
     * {@code deleteStrike_countDropsIntoReducedBand_revertsToReducedAlertStaysOpen}). This documents the
     * gap {@code ConfigStartupAssertion}'s cross-field check now warns operators about, rather than
     * silently passing either way.
     */
    @Test
    void deleteStrike_misconfiguredThresholdOrdering_reducedTierBecomesUnreachable() {
        UUID strikeToDelete = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            for (int i = 0; i < 4; i++) {
                jdbcTemplate.update(
                    "INSERT INTO marketplace.coach_reliability_strikes (id, coach_id, booking_id, reason, created_at, acknowledged) VALUES (?, ?, ?, 'COACH_NO_SHOW', ?, false)",
                    UUID.randomUUID(), coachProfileId, bookingId, Timestamp.from(Instant.now()));
            }
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_reliability_strikes (id, coach_id, booking_id, reason, created_at, acknowledged) VALUES (?, ?, ?, 'COACH_NO_SHOW', ?, false)",
                strikeToDelete, coachProfileId, bookingId, Timestamp.from(Instant.now()));
            jdbcTemplate.update("UPDATE marketplace.coach_profiles SET status = 'PENDING_REVIEW' WHERE id = ?", coachProfileId);
            return null;
        });

        String originalVisibility = String.valueOf(ReliabilityStrikeConfig.DEFAULT_VISIBILITY_THRESHOLD);
        try {
            // Misconfigured pair: visibilityThreshold(10) > suspensionThreshold(5, seeded default).
            configService.updateConfig(ReliabilityStrikeConfig.VISIBILITY_THRESHOLD_KEY, "10");

            String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
            httpTestClient.makeHttpRequest(
                baseUrl() + "/api/admin/coaches/" + coachProfileId + "/strikes/" + strikeToDelete + "?reason=Misconfigured+pair+skips+reduced",
                HttpMethod.DELETE, null, authenticatedHeaders(adminCookies), Void.class);

            String coachStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM marketplace.coach_profiles WHERE id = ?", String.class, coachProfileId);
            assertThat(coachStatus)
                .as("count(4) < suspensionThreshold(5) and < the misconfigured visibilityThreshold(10) "
                    + "— REDUCED is unreachable, so the coach skips straight to ACTIVE instead of the "
                    + "REDUCED outcome the correctly-configured default(3) would have produced")
                .isEqualTo("ACTIVE");
        } finally {
            configService.updateConfig(ReliabilityStrikeConfig.VISIBILITY_THRESHOLD_KEY, originalVisibility);
        }
    }

    @Test
    void invalidStrikeReason_returns400() {
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/admin/coaches/" + coachProfileId + "/strikes",
            HttpMethod.POST,
            Map.of("bookingId", bookingId.toString(), "reason", "INVALID_REASON"),
            authenticatedHeaders(adminCookies), Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void issueStrikeWithBookingFromDifferentCoach_returns400() {
        // Seed a second coach and a booking that belongs to them
        UUID otherCoachId = UUID.randomUUID();
        UUID otherBookingId = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.\"user\" (id, created_by, created_date, last_modified_by, last_modified_date, request_id, status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, activated, locked, login, login_id_type, password_hash, otp_enabled, skillars_role, verification_status) " +
                "VALUES (9070000099, 'system', ?, 'system', ?, 'test-req', 'ACTIVE', '1985-06-01', 'othercoach9070@skillars-test.com', 'Test', 'OTHER', 'en', 'Coach', 'DE', '9070099', true, false, 'othercoach9070@skillars-test.com', 'EMAIL', 'noop', false, 'COACH', 'BASIC_VERIFIED')",
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles (id, user_id, display_name, canonical_timezone, status) VALUES (?, 9070000099, 'Other Coach', 'Europe/Berlin', 'ACTIVE')",
                otherCoachId);
            jdbcTemplate.update(
                "INSERT INTO booking.bookings (id, parent_id, player_id, coach_id, requested_start_time, requested_end_time, status, canonical_timezone, version, created_at, updated_at) " +
                "VALUES (?, 9070999001, 9070999002, ?, ?, ?, 'COMPLETED', 'Europe/Berlin', 0, ?, ?)",
                otherBookingId, otherCoachId,
                Timestamp.from(Instant.now().minusSeconds(7200)), Timestamp.from(Instant.now().minusSeconds(3600)),
                Timestamp.from(Instant.now().minusSeconds(7200)), Timestamp.from(Instant.now()));
            return null;
        });

        try {
            String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
            assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
                baseUrl() + "/api/admin/coaches/" + coachProfileId + "/strikes",
                HttpMethod.POST,
                Map.of("bookingId", otherBookingId.toString(), "reason", "COACH_NO_SHOW"),
                authenticatedHeaders(adminCookies), Map.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
        } finally {
            transactionTemplate.execute(status -> {
                jdbcTemplate.update("DELETE FROM booking.bookings WHERE id = ?", otherBookingId);
                jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id = ?", otherCoachId);
                jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id = 9070000099");
                return null;
            });
        }
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
            email, role, "907" + (id % 10000000), email, passwordHash, role);
    }

    private void grantAuthority(long userId, String roleName) {
        jdbcTemplate.update(
            "INSERT INTO main.user_authority (user_id, authority_id) VALUES (?, (SELECT id FROM main.authority WHERE name = ?)) ON CONFLICT DO NOTHING",
            userId, roleName);
    }
}
