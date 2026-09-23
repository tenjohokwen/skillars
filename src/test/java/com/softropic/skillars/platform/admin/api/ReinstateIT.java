package com.softropic.skillars.platform.admin.api;

import com.softropic.skillars.config.AbstractIntegrationTest;

import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.SecurityIT;
import com.softropic.skillars.utils.CoachProfileTestFixtures;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class ReinstateIT extends AbstractIntegrationTest {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String CLIENT_ID      = "testClientId";
    private static final String TEST_PASSWORD  = "TestPass@123!";

    private static final long ADMIN_ID      = 9060_000_100L;
    private static final long COACH_USER_ID = 9060_000_010L;

    private static final String ADMIN_EMAIL = "admin.reinstate9060@skillars-test.com";
    private static final String COACH_EMAIL = "coach.reinstate9060@skillars-test.com";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private PasswordEncoder passwordEncoder;

    @LocalServerPort private int randomServerPort;

    private UUID coachProfileId;
    private UUID strikeAlertId;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        coachProfileId = UUID.randomUUID();
        strikeAlertId = UUID.randomUUID();

        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) VALUES (9060, 'ROLE_COACH', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) VALUES (9061, 'ROLE_ADMIN', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));

            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH");
            grantAuthority(COACH_USER_ID, "ROLE_COACH");

            insertUser(ADMIN_ID, ADMIN_EMAIL, passwordHash, "ADMIN");
            grantAuthority(ADMIN_ID, "ROLE_ADMIN");

            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles (id, user_id, display_name, bio, city, languages, canonical_timezone, status, status_changed_at) " +
                "VALUES (?, ?, 'Reinstate Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'SUSPENDED', ?)",
                coachProfileId, COACH_USER_ID, Timestamp.from(Instant.now()));

            // skillars-deferred-131 AC1 Fix 3: reinstateCoach now re-validates the profile is
            // publishable (CoachProfileService.validateReadyForActivation) before writing ACTIVE, so
            // every test in this class needs complete builder-step data, not just a display name.
            CoachProfileTestFixtures.seedCompleteBuilderSteps(jdbcTemplate, coachProfileId);

            jdbcTemplate.update(
                "INSERT INTO admin.admin_alerts (alert_id, type, reference_id, reference_type, status, created_at) " +
                "VALUES (?, 'STRIKE_THRESHOLD', ?, 'COACH', 'OPEN', ?)",
                strikeAlertId, coachProfileId.toString(), Timestamp.from(Instant.now()));

            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM admin.admin_action_log WHERE reference_id = ?", coachProfileId.toString());
            // skillars-deferred-123 code review 2026-09-18: delete by reference_id, not just the
            // seeded alert_id — reinstateCoach_withStrikesStillInWindow_setsActiveButLeavesAlertOpen
            // can cause a second STRIKE_THRESHOLD alert to be inserted if the seeded one is ever
            // resolved first, and a leaked OPEN alert would break the other tests' assertions.
            jdbcTemplate.update("DELETE FROM admin.admin_alerts WHERE reference_id = ?", coachProfileId.toString());
            // FK from coach_reliability_strikes -> coach_profiles: strikes must go first.
            jdbcTemplate.update("DELETE FROM marketplace.coach_reliability_strikes WHERE coach_id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_specialties WHERE coach_id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_age_groups WHERE coach_id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_pricing WHERE coach_id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_availability_windows WHERE coach_id = ?", coachProfileId);
            // skillars-deferred-131 AC1 Fix 3: reinstateCoach now creates a coach_subscriptions row
            // (find-or-create) — must be deleted before the FK-referenced coach_profiles row.
            jdbcTemplate.update("DELETE FROM marketplace.coach_subscriptions WHERE coach_id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id = ?", coachProfileId);
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?)", COACH_USER_ID, ADMIN_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?)", COACH_USER_ID, ADMIN_ID);
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id IN (9060, 9061)");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    /**
     * skillars-deferred-123 code review 2026-09-18 (Decision 2). Sibling of
     * {@link #reinstateCoach_setsActiveAndResolvesAlert()}, which seeds no strikes and therefore
     * always took the resolve branch. This one seeds {@code DEFAULT_VISIBILITY_THRESHOLD} (3)
     * in-window strikes to prove the other branch.
     *
     * <p>What it pins: AC1 suppresses escalation outright for a coach already off the marketplace, and
     * {@code StrikeThresholdReachedEvent} is published only inside {@code issue()}'s
     * {@code status != PENDING_REVIEW} branch — so strikes accrued while a coach is SUSPENDED raise no
     * alert at all, where pre-AC1 they did. Without the fix, {@code reinstateCoach} would then clear
     * the alert and return the coach to full marketplace visibility carrying an at-or-above-threshold
     * strike count, invisible to {@code getCoachesUnderEnforcement} (which lists only PENDING_REVIEW
     * and SUSPENDED) until a further strike landed — possibly never, on a 30-day window.
     *
     * <p>The status assertion is deliberately {@code ACTIVE}, not a de-escalated tier: the
     * {@code [DECIDED 2026-09-18, skillars-deferred-122]} note on {@code reinstateCoach} is binding —
     * explicit admin intent wins. Only the alert, not the status, is driven by the re-evaluated count.
     */
    @Test
    void reinstateCoach_withStrikesStillInWindow_setsActiveButLeavesAlertOpen() {
        transactionTemplate.execute(status -> {
            for (int i = 0; i < 3; i++) {
                jdbcTemplate.update(
                    "INSERT INTO marketplace.coach_reliability_strikes (id, coach_id, booking_id, reason, acknowledged, created_at) " +
                    "VALUES (?, ?, ?, 'COACH_NO_SHOW', false, ?)",
                    UUID.randomUUID(), coachProfileId, UUID.randomUUID(),
                    Timestamp.from(Instant.now().minusSeconds(3600L * (i + 1))));
            }
            return null;
        });

        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        ResponseEntity<Void> resp = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/admin/coaches/" + coachProfileId + "/reinstate",
            HttpMethod.POST,
            Map.of("reason", "Reviewed and cleared"),
            authenticatedHeaders(adminCookies), Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Admin intent still wins — the coach is reinstated.
        String coachStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM marketplace.coach_profiles WHERE id = ?", String.class, coachProfileId);
        assertThat(coachStatus).isEqualTo("ACTIVE");

        // ...but the enforcement signal survives: the alert is NOT resolved.
        String alertStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM admin.admin_alerts WHERE alert_id = ?", String.class, strikeAlertId);
        assertThat(alertStatus).isEqualTo("OPEN");

        Instant resolvedAt = jdbcTemplate.queryForObject(
            "SELECT resolved_at FROM admin.admin_alerts WHERE alert_id = ?", Instant.class, strikeAlertId);
        assertThat(resolvedAt).isNull();

        // Exactly one OPEN STRIKE_THRESHOLD alert for this coach — re-publishing the event must be
        // idempotent (AdminAlertEventListener.insertAlert short-circuits on an existing OPEN alert).
        Long openAlertCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM admin.admin_alerts WHERE reference_id = ? AND type = 'STRIKE_THRESHOLD' AND status = 'OPEN'",
            Long.class, coachProfileId.toString());
        assertThat(openAlertCount).isEqualTo(1L);

        // The elevated count is recorded on the audit row rather than left implicit.
        String logReason = jdbcTemplate.queryForObject(
            "SELECT reason FROM admin.admin_action_log WHERE reference_id = ? AND action_type = 'COACH_REINSTATE'",
            String.class, coachProfileId.toString());
        assertThat(logReason).contains("3 in-window strikes");
    }

    @Test
    void reinstateCoach_setsActiveAndResolvesAlert() {
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        ResponseEntity<Void> resp = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/admin/coaches/" + coachProfileId + "/reinstate",
            HttpMethod.POST,
            Map.of("reason", "Reviewed and cleared"),
            authenticatedHeaders(adminCookies), Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        String coachStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM marketplace.coach_profiles WHERE id = ?", String.class, coachProfileId);
        assertThat(coachStatus).isEqualTo("ACTIVE");

        String alertStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM admin.admin_alerts WHERE alert_id = ?", String.class, strikeAlertId);
        assertThat(alertStatus).isEqualTo("RESOLVED");

        Instant resolvedAt = jdbcTemplate.queryForObject(
            "SELECT resolved_at FROM admin.admin_alerts WHERE alert_id = ?", Instant.class, strikeAlertId);
        assertThat(resolvedAt).isNotNull();

        Long resolvedBy = jdbcTemplate.queryForObject(
            "SELECT resolved_by FROM admin.admin_alerts WHERE alert_id = ?", Long.class, strikeAlertId);
        assertThat(resolvedBy).isEqualTo(ADMIN_ID);

        Long logCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM admin.admin_action_log WHERE reference_id = ? AND action_type = 'COACH_REINSTATE'",
            Long.class, coachProfileId.toString());
        assertThat(logCount).isEqualTo(1L);
    }

    /**
     * Code review 2026-09-18: {@code reinstateCoach} rejected {@code REDUCED} with {@code BAD_REQUEST}
     * pre-fix, leaving a coach whose elevated status had become stale purely from strike ageout with no
     * admin path back to {@code ACTIVE} — {@code deleteStrike}'s own out-of-window guard (AC1) changes
     * nothing for an already-out-of-window strike, and this method threw for any status other than
     * {@code SUSPENDED}/{@code PENDING_REVIEW}. {@code REDUCED} is now an accepted source status,
     * mirroring {@code PENDING_REVIEW}'s existing behavior.
     */
    @Test
    void reinstateCoach_fromReducedStatus_setsActiveAndResolvesAlert() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("UPDATE marketplace.coach_profiles SET status = 'REDUCED' WHERE id = ?", coachProfileId);
            return null;
        });

        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        ResponseEntity<Void> resp = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/admin/coaches/" + coachProfileId + "/reinstate",
            HttpMethod.POST,
            Map.of("reason", "Strikes aged out"),
            authenticatedHeaders(adminCookies), Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        String coachStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM marketplace.coach_profiles WHERE id = ?", String.class, coachProfileId);
        assertThat(coachStatus).isEqualTo("ACTIVE");

        String alertStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM admin.admin_alerts WHERE alert_id = ?", String.class, strikeAlertId);
        assertThat(alertStatus).isEqualTo("RESOLVED");
    }

    @Test
    void reinstateIdempotent_doubleCallDoesNotFail() {
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        String reinstateUrl = baseUrl() + "/api/admin/coaches/" + coachProfileId + "/reinstate";
        Map<String, Object> body = Map.of("reason", "Cleared");

        ResponseEntity<Void> r1 = httpTestClient.makeHttpRequest(reinstateUrl, HttpMethod.POST, body, authenticatedHeaders(adminCookies), Void.class);
        ResponseEntity<Void> r2 = httpTestClient.makeHttpRequest(reinstateUrl, HttpMethod.POST, body, authenticatedHeaders(adminCookies), Void.class);

        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.OK);

        String coachStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM marketplace.coach_profiles WHERE id = ?", String.class, coachProfileId);
        assertThat(coachStatus).isEqualTo("ACTIVE");
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
            email, role, "906" + (id % 10000000), email, passwordHash, role);
    }

    private void grantAuthority(long userId, String roleName) {
        jdbcTemplate.update(
            "INSERT INTO main.user_authority (user_id, authority_id) VALUES (?, (SELECT id FROM main.authority WHERE name = ?)) ON CONFLICT DO NOTHING",
            userId, roleName);
    }

}
