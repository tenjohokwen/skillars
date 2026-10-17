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
class AdminBookingResourceIT extends AbstractIntegrationTest {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String ADMIN_BOOKING_BASE = "/api/admin/bookings";
    private static final String CLIENT_ID = "testClientId";
    private static final String TEST_PASSWORD = "TestPass@123!";

    private static final long ADMIN_ID = 9162_000_100L;
    private static final long PARENT_ID = 9162_000_001L;
    private static final long PLAYER_ID = 9162_000_002L;
    private static final long COACH_USER_ID = 9162_000_010L;

    private static final String ADMIN_EMAIL = "admin.forcecomplete9162@skillars-test.com";
    private static final String PARENT_EMAIL = "parent.forcecomplete9162@skillars-test.com";
    private static final String COACH_EMAIL = "coach.forcecomplete9162@skillars-test.com";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private PasswordEncoder passwordEncoder;

    @LocalServerPort private int randomServerPort;

    private UUID coachProfileId;
    private UUID bookingId;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        coachProfileId = UUID.randomUUID();
        bookingId = UUID.randomUUID();

        transactionTemplate.execute(status -> {
            insertAuthority(9162, "ROLE_PARENT");
            insertAuthority(9163, "ROLE_COACH");
            insertAuthority(9164, "ROLE_ADMIN");

            insertUser(PARENT_ID, PARENT_EMAIL, passwordHash, "PARENT");
            grantAuthority(PARENT_ID, "ROLE_PARENT");

            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH");
            grantAuthority(COACH_USER_ID, "ROLE_COACH");

            insertUser(ADMIN_ID, ADMIN_EMAIL, passwordHash, "ADMIN");
            grantAuthority(ADMIN_ID, "ROLE_ADMIN");

            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles (id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, 'Force Complete Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
                coachProfileId, COACH_USER_ID);

            jdbcTemplate.update(
                "INSERT INTO booking.bookings " +
                "(id, coach_id, parent_id, player_id, status, requested_start_time, requested_end_time, " +
                "version, created_at, updated_at, canonical_timezone) " +
                "VALUES (?, ?, ?, ?, 'COMPLETED_PENDING_CONFIRMATION', ?, ?, 0, ?, ?, 'Europe/Berlin')",
                bookingId, coachProfileId, PARENT_ID, PLAYER_ID,
                Timestamp.from(Instant.now().minusSeconds(7200)),
                Timestamp.from(Instant.now().minusSeconds(3600)),
                Timestamp.from(Instant.now().minusSeconds(86400)),
                Timestamp.from(Instant.now().minusSeconds(86400)));

            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM admin.admin_action_log WHERE reference_id = ?", bookingId.toString());
            jdbcTemplate.update("DELETE FROM booking.session_completion_data WHERE booking_id = ?", bookingId);
            jdbcTemplate.update("DELETE FROM booking.bookings WHERE id = ?", bookingId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id = ?", coachProfileId);
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?, ?)",
                PARENT_ID, COACH_USER_ID, ADMIN_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?, ?)",
                PARENT_ID, COACH_USER_ID, ADMIN_ID);
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id IN (9162, 9163, 9164)");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void forceComplete_stuckBooking_createsPlaceholderRowAndCompletesBooking() {
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);

        ResponseEntity<Map> resp = forceComplete(adminCookies,
            "support ticket #42 — coach confirmed session happened");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // skillars-deferred-153 code review (2026-10-17): the endpoint returns a body now, and the
        // happy path must say FORCE_COMPLETED specifically — a bare 200 was indistinguishable from
        // the two no-op outcomes.
        assertThat(resp.getBody().get("outcome")).isEqualTo("FORCE_COMPLETED");

        String bookingStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM booking.bookings WHERE id = ?", String.class, bookingId);
        assertThat(bookingStatus).isEqualTo("COMPLETED");

        Integer scdCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM booking.session_completion_data WHERE booking_id = ?",
            Integer.class, bookingId);
        assertThat(scdCount).isEqualTo(1);

        Boolean playerAttended = jdbcTemplate.queryForObject(
            "SELECT player_attended FROM booking.session_completion_data WHERE booking_id = ?",
            Boolean.class, bookingId);
        assertThat(playerAttended).isFalse();

        Integer logCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM admin.admin_action_log WHERE reference_id = ? AND action_type = 'BOOKING_FORCE_COMPLETE'",
            Integer.class, bookingId.toString());
        assertThat(logCount).isEqualTo(1);
        assertThat(auditReason()).endsWith("[outcome: FORCE_COMPLETED]");
    }

    /**
     * skillars-deferred-153 code review (2026-10-17): this test was a FALSE PASS.
     *
     * <p>The pre-existing completion-data row was inserted with a bare {@code jdbcTemplate.update}
     * in the method body. In this harness that write NEVER COMMITS (Hikari {@code auto-commit=false}
     * and no surrounding transaction to commit it — every other write in this file, in
     * {@link #setUp} and {@link #tearDown}, goes through {@code transactionTemplate.execute}), so
     * the server never saw the row, the service took the happy path, and the {@code scdCount == 1}
     * assertion was satisfied by the service's OWN inserted row. The ALREADY_RESOLVED branch was
     * never exercised at all. Wrapping the insert is what makes this test real; the outcome and
     * unchanged-status assertions are what make it observable rather than merely coincidental.
     */
    @Test
    @SuppressWarnings("unchecked")
    void forceComplete_alreadyHasCompletionData_succeedsBenignlyWithoutDuplicateRow() {
        UUID preExistingScdId = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO booking.session_completion_data " +
                "(id, booking_id, coach_id, player_id, player_attended, completion_mode, created_at) " +
                "VALUES (?, ?, ?, ?, true, 'LIVE', ?)",
                preExistingScdId, bookingId, coachProfileId, PLAYER_ID, Timestamp.from(Instant.now()));
            return null;
        });

        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        ResponseEntity<Map> resp = forceComplete(adminCookies, "already resolved by coach");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("outcome")).isEqualTo("ALREADY_RESOLVED");

        Integer scdCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM booking.session_completion_data WHERE booking_id = ?",
            Integer.class, bookingId);
        assertThat(scdCount).isEqualTo(1);
        // ...and it is the pre-existing row, not a replacement written by the service.
        String survivingScdId = jdbcTemplate.queryForObject(
            "SELECT id::text FROM booking.session_completion_data WHERE booking_id = ?",
            String.class, bookingId);
        assertThat(survivingScdId).isEqualTo(preExistingScdId.toString());

        // The booking must NOT be transitioned — a real wrap-up owns that transition, and
        // completion releases the coach's payout.
        String bookingStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM booking.bookings WHERE id = ?", String.class, bookingId);
        assertThat(bookingStatus).isEqualTo("COMPLETED_PENDING_CONFIRMATION");

        assertThat(auditReason()).endsWith("[outcome: ALREADY_RESOLVED]");
    }

    // skillars-deferred-153 code review (2026-10-17): NEW — the admin endpoint accepts any bookingId
    // straight from a support ticket, so a misdirected id must be reported as NOT_STUCK rather than
    // writing an orphan placeholder row that would permanently block the coach's own submitWrapUp
    // through the session_completion_data_booking_id_key UNIQUE constraint.
    @Test
    @SuppressWarnings("unchecked")
    void forceComplete_bookingNotPendingConfirmation_returnsNotStuckAndChangesNothing() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "UPDATE booking.bookings SET status = 'COMPLETED' WHERE id = ?", bookingId);
            return null;
        });

        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        ResponseEntity<Map> resp = forceComplete(adminCookies, "misdirected ticket #43");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("outcome")).isEqualTo("NOT_STUCK");

        Integer scdCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM booking.session_completion_data WHERE booking_id = ?",
            Integer.class, bookingId);
        assertThat(scdCount).isZero();

        String bookingStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM booking.bookings WHERE id = ?", String.class, bookingId);
        assertThat(bookingStatus).isEqualTo("COMPLETED");

        assertThat(auditReason()).endsWith("[outcome: NOT_STUCK]");
    }

    @Test
    void forceComplete_nonAdminCaller_returns403() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + ADMIN_BOOKING_BASE + "/" + bookingId + "/force-complete",
            HttpMethod.POST,
            Map.of("note", "not allowed"),
            authenticatedHeaders(coachCookies),
            Void.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void forceComplete_unauthenticated_returns401() {
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + ADMIN_BOOKING_BASE + "/" + bookingId + "/force-complete",
            HttpMethod.POST,
            Map.of("note", "no auth"),
            clientHeaders(),
            Void.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    // ── helpers ──

    // skillars-deferred-153 code review (2026-10-17): Map.class, not Void.class — the endpoint now
    // returns an AdminForceCompleteBookingResponse body carrying the ForceCompleteOutcome name.
    private ResponseEntity<Map> forceComplete(String adminCookies, String note) {
        return httpTestClient.makeHttpRequest(
            baseUrl() + ADMIN_BOOKING_BASE + "/" + bookingId + "/force-complete",
            HttpMethod.POST,
            Map.of("note", note),
            authenticatedHeaders(adminCookies),
            Map.class);
    }

    private String auditReason() {
        return jdbcTemplate.queryForObject(
            "SELECT reason FROM admin.admin_action_log " +
            "WHERE reference_id = ? AND action_type = 'BOOKING_FORCE_COMPLETE'",
            String.class, bookingId.toString());
    }

    private void insertAuthority(int id, String name) {
        jdbcTemplate.update(
            "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
            "VALUES (?, ?, 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
            id, name, Timestamp.from(Instant.now()));
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
            "true, false, ?, 'EMAIL', ?, false, ?, 'BASIC_VERIFIED')",
            id, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            email, role,
            "916" + (id % 10000000),
            email, passwordHash, role);
    }

    private void grantAuthority(long userId, String roleName) {
        jdbcTemplate.update(
            "INSERT INTO main.user_authority (user_id, authority_id) " +
            "VALUES (?, (SELECT id FROM main.authority WHERE name = ?)) ON CONFLICT DO NOTHING",
            userId, roleName);
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
}
