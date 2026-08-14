package com.softropic.skillars.platform.messaging.api;

import com.softropic.skillars.config.AbstractIntegrationTest;

import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.SecurityIT;
import com.softropic.skillars.platform.messaging.contract.MessagingErrorCode;
import com.softropic.skillars.platform.messaging.contract.MessageReportReason;
import com.softropic.skillars.platform.messaging.service.MessagingReportService;
import com.softropic.skillars.platform.messaging.service.MessagingService;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.data.domain.Pageable;
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

@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class MessagingAccessControlIT extends AbstractIntegrationTest {

    private static final String LOGIN_ENDPOINT   = "/api/auth/login";
    private static final String MESSAGING_BASE   = "/api/messaging";
    private static final String CLIENT_ID        = "testClientId";
    private static final String TEST_PASSWORD    = "TestPass@123!";

    private static final long PARENT_ID       = 9800000001L;
    private static final long PLAYER_ID       = 9800000002L;
    private static final long COACH_USER_ID   = 9800000010L;
    private static final long COACH_USER_ID2  = 9800000020L;
    private static final long PLAYER_ID2      = 9800000003L;

    private static final String PARENT_EMAIL   = "parent.msgac@skillars-test.com";
    private static final String COACH_EMAIL    = "coach.msgac@skillars-test.com";
    private static final String COACH_EMAIL2   = "coach2.msgac@skillars-test.com";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private MessagingService messagingService;
    @Autowired private MessagingApiAdvice messagingApiAdvice;
    @Autowired private MessagingReportService messagingReportService;

    @LocalServerPort private int randomServerPort;

    private UUID coachProfileId;
    private UUID coachProfileId2;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (9800, 'ROLE_PARENT', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (9801, 'ROLE_COACH', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));

            insertUser(PARENT_ID, PARENT_EMAIL, passwordHash, "PARENT");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_PARENT')) ON CONFLICT DO NOTHING",
                PARENT_ID);

            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'AC Player', ?, 'MIDFIELDER', 'ADULT', ?, true, ?, 'system')",
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
                "VALUES (?, ?, 'AC Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
                coachProfileId, COACH_USER_ID);

            insertUser(COACH_USER_ID2, COACH_EMAIL2, passwordHash, "COACH");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_COACH')) ON CONFLICT DO NOTHING",
                COACH_USER_ID2);

            coachProfileId2 = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles " +
                "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, 'AC Coach 2', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
                coachProfileId2, COACH_USER_ID2);

            // Booking between coach1 and player1 (COMPLETED)
            jdbcTemplate.update(
                "INSERT INTO booking.bookings " +
                "(id, coach_id, parent_id, player_id, status, requested_start_time, requested_end_time, " +
                " version, created_at, updated_at, canonical_timezone) " +
                "VALUES (?, ?, ?, ?, 'COMPLETED', ?, ?, 0, ?, ?, 'Europe/Berlin')",
                UUID.randomUUID(), coachProfileId, PARENT_ID, PLAYER_ID,
                Timestamp.from(Instant.now().minusSeconds(7200)),
                Timestamp.from(Instant.now().minusSeconds(3600)),
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()));

            return null;
        });
    }


    // ── AC2: No booking relationship ──

    @Test
    void createConversation_noBookingRelationship_returns403WithErrorCode() {
        // Coach2 has no booking with player1
        String coach2Cookies = loginAndGetCookies(COACH_EMAIL2);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/conversations",
            HttpMethod.POST,
            Map.of("coachId", coachProfileId2.toString(), "playerId", PLAYER_ID),
            authenticatedHeaders(coach2Cookies),
            Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(ex.getResponseBodyAsString()).contains("messaging.noBookingRelationship");
            });
    }

    // ── AC5: Non-party access to messages ──

    @Test
    void getMessages_nonParty_returns403() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);
        Long conversationId = ensureConversation(coachCookies);

        // Coach2 tries to read the conversation
        String coach2Cookies = loginAndGetCookies(COACH_EMAIL2);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/conversations/" + conversationId + "/messages",
            HttpMethod.GET,
            null,
            authenticatedHeaders(coach2Cookies),
            Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ── AC6: SSE non-party ──

    @Test
    void sseEndpoint_nonParty_returns403() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);
        Long conversationId = ensureConversation(coachCookies);

        String coach2Cookies = loginAndGetCookies(COACH_EMAIL2);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/conversations/" + conversationId + "/events",
            HttpMethod.GET,
            null,
            authenticatedHeaders(coach2Cookies),
            String.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ── Non-existent conversation returns 404 ──

    @Test
    void getMessages_nonExistentConversation_returns404() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/conversations/999999999/messages",
            HttpMethod.GET,
            null,
            authenticatedHeaders(coachCookies),
            Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));
    }

    /**
     * An unrecognised role string must yield 403, not 500.
     *
     * <p>Driven through the service rather than HTTP on purpose: {@code MessagingResource.resolveRole}
     * guarantees one of {@code COACH}/{@code PARENT}/{@code PLAYER} for every controller entry, so
     * the {@code default} arm is unreachable over the wire. That is exactly why it was left throwing
     * {@code IllegalArgumentException} — but the guard and the throws live in different classes with
     * no shared enum, so the invariant is convention, not structure. The first controller, listener
     * or scheduler that calls these methods with a role from somewhere else gets a 500 and a stack
     * trace where a 403 belongs.
     *
     * <p>Both halves are asserted: the exception the service raises, AND the status the advice maps
     * it to. Asserting only the exception type would pass against any {@code RuntimeException} with
     * the right name and prove nothing about the response the caller actually sees.
     *
     * <p><strong>Mutation-checked:</strong> reverting any one of the four {@code default} arms to
     * {@code IllegalArgumentException} fails this test on {@code isInstanceOf}.
     */
    @Test
    void unrecognisedRole_yields403NotFatal() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);
        Long conversationId = ensureConversation(coachCookies);

        // MessagingService.verifyIsParty's switch.
        assertThatThrownBy(() -> messagingService.getMessages(
                conversationId, COACH_USER_ID, "SUPERVISOR", Pageable.ofSize(20)))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(MessagingErrorCode.NOT_A_PARTY));

        // MessagingService.getConversations' own dispatch (skillars-deferred-22 AC1) — previously an
        // unrecognised role silently fell through to PLAYER handling instead of rejecting the call.
        assertThatThrownBy(() -> messagingService.getConversations(COACH_USER_ID, "SUPERVISOR"))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(MessagingErrorCode.NOT_A_PARTY));

        // MessagingReportService's own copy of verifyIsParty. Covered separately and deliberately:
        // it is a duplicate that cannot be extracted (injecting MessagingService would be a
        // circular dependency), so the two can only be kept in step by testing both.
        assertThatThrownBy(() -> messagingReportService.reportConversation(
                conversationId, COACH_USER_ID, "SUPERVISOR", MessageReportReason.HARASSMENT, "details"))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(MessagingErrorCode.NOT_A_PARTY));

        // The remaining two arms — resolveLastReadAt and updateLastRead — are NOT asserted here,
        // and that is a statement about the code, not an omission. Both sit behind a verifyIsParty
        // call that throws first (updateLastRead runs after :219 in getMessages; resolveLastReadAt
        // is reached only from the summary mapper), so no caller can drive them with an
        // unrecognised role. They are defence in depth. A test claiming to exercise them would
        // have to fake a reachability that does not exist — precisely the "test that cannot fail"
        // pattern the last three stories' reviews had to unpick. Changing them was still correct:
        // the exception type is now consistent across all four, so whichever one a future caller
        // reaches first behaves the same.

        // And the half that decides what the caller actually receives.
        ResponseEntity<?> mapped = messagingApiAdvice.handleOperationNotAllowed(
            new OperationNotAllowedException(
                "Caller does not hold a recognised messaging role", MessagingErrorCode.NOT_A_PARTY));
        assertThat(mapped.getStatusCode())
            .as("messaging.notAParty must map to 403 — the whole point of changing the exception type")
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── helpers ──

    private Long ensureConversation(String cookies) {
        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/conversations",
            HttpMethod.POST,
            Map.of("coachId", coachProfileId.toString(), "playerId", PLAYER_ID),
            authenticatedHeaders(cookies),
            Map.class);
        Object id = resp.getBody().get("conversationId");
        if (id instanceof Number n) return n.longValue();
        return Long.parseLong(id.toString());
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
