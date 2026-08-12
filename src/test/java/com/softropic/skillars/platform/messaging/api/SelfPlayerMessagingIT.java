package com.softropic.skillars.platform.messaging.api;

import com.softropic.skillars.config.AbstractIntegrationTest;

import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.messaging.contract.ModerationVerdict;
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

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * UAT.5 AC3: the messaging fix skillars-deferred-16 D1 deferred, now unblocked by AC1.
 * {@code messaging.conversations.parent_id} is nullable (V95) for a self-registered adult PLAYER,
 * who has no real parent. Both the coach and the player themselves must be able to open a
 * conversation once a booking exists, and a message send + moderation pass must complete against
 * the real null {@code parent_id} without a 500/NPE anywhere downstream.
 */
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class SelfPlayerMessagingIT extends AbstractIntegrationTest {

    private static final String LOGIN_ENDPOINT  = "/api/auth/login";
    private static final String MESSAGING_BASE  = "/api/messaging";
    private static final String CLIENT_ID       = "testClientId";
    private static final String TEST_PASSWORD   = "TestPass@123!";

    private static final long SELF_PLAYER_USER_ID    = 9870_000_001L;
    private static final long SELF_PLAYER_PROFILE_ID = 9870_000_002L;
    private static final long COACH_USER_ID          = 9870_000_010L;

    private static final String SELF_PLAYER_EMAIL = "selfplayer.messaging@skillars-test.com";
    private static final String COACH_EMAIL       = "coach.selfplayer-messaging@skillars-test.com";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private PasswordEncoder passwordEncoder;

    @LocalServerPort private int randomServerPort;

    private UUID coachProfileId;

    @BeforeEach
    void setUp() {
        when(geminiClient.evaluate(any())).thenReturn(ModerationVerdict.SAFE);

        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        coachProfileId = UUID.randomUUID();

        transactionTemplate.execute(status -> {
            insertUser(SELF_PLAYER_USER_ID, SELF_PLAYER_EMAIL, passwordHash, "PLAYER");
            grantAuthority(SELF_PLAYER_USER_ID, "ROLE_PLAYER");
            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, user_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Self Player', ?, 'MIDFIELDER', 'ADULT', ?, true, ?, 'system')",
                SELF_PLAYER_PROFILE_ID, Date.valueOf(LocalDate.now().minusYears(20)),
                SELF_PLAYER_USER_ID, Timestamp.from(Instant.now()));

            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH");
            grantAuthority(COACH_USER_ID, "ROLE_COACH");
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles " +
                "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, 'Messaging Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
                coachProfileId, COACH_USER_ID);

            // The precondition initiateConversation requires: a CONFIRMED-status booking between
            // this exact coach/player pair. parent_id here is the player's own userId — the
            // opaque-id shortcut AC1 relies on — but is irrelevant to this AC's trigger chain.
            jdbcTemplate.update(
                "INSERT INTO booking.bookings " +
                "(id, coach_id, parent_id, player_id, status, requested_start_time, requested_end_time, " +
                " version, created_at, updated_at, canonical_timezone) " +
                "VALUES (?, ?, ?, ?, 'CONFIRMED', ?, ?, 0, ?, ?, 'Europe/Berlin')",
                UUID.randomUUID(), coachProfileId, SELF_PLAYER_USER_ID, SELF_PLAYER_PROFILE_ID,
                Timestamp.from(Instant.now().minusSeconds(7200)),
                Timestamp.from(Instant.now().minusSeconds(3600)),
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()));

            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM messaging.messages WHERE conversation_id IN " +
                "(SELECT id FROM messaging.conversations WHERE coach_id = ?)", coachProfileId);
            jdbcTemplate.update("DELETE FROM messaging.conversations WHERE coach_id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM booking.bookings WHERE coach_id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM main.player_profiles WHERE id = ?", SELF_PLAYER_PROFILE_ID);
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?)", SELF_PLAYER_USER_ID, COACH_USER_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?)", SELF_PLAYER_USER_ID, COACH_USER_ID);
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void coachOpensConversationWithSelfBookingPlayer_returns200AndPersistsNullParentId() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/conversations",
            HttpMethod.POST,
            Map.of("coachId", coachProfileId.toString(), "playerId", SELF_PLAYER_PROFILE_ID),
            authenticatedHeaders(coachCookies),
            Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Long conversationId = toLong(response.getBody().get("conversationId"));
        Long persistedParentId = jdbcTemplate.queryForObject(
            "SELECT parent_id FROM messaging.conversations WHERE id = ?", Long.class, conversationId);
        assertThat(persistedParentId)
            .as("V95: a self-registered player has no real parent to notify")
            .isNull();
    }

    @Test
    void selfRegisteredPlayerOpensConversationForThemselves_returns200() {
        // Exercises the new isSelf branch in MessagingResource.initiateConversation: the caller is
        // neither the coach nor a parent who owns the player, but IS the player being messaged.
        String playerCookies = loginAndGetCookies(SELF_PLAYER_EMAIL);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/conversations",
            HttpMethod.POST,
            Map.of("coachId", coachProfileId.toString(), "playerId", SELF_PLAYER_PROFILE_ID),
            authenticatedHeaders(playerCookies),
            Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Long conversationId = toLong(response.getBody().get("conversationId"));
        Long persistedParentId = jdbcTemplate.queryForObject(
            "SELECT parent_id FROM messaging.conversations WHERE id = ?", Long.class, conversationId);
        assertThat(persistedParentId).isNull();
    }

    @Test
    void messageSendAndModerationOnNullParentConversation_completesWithoutError() {
        // Exercises ModerationResultApplier.resolveRecipient's COACH-sender branch under a REAL
        // null parent_id: a self-registered player is always ADULT tier, so parentIsBlocked() is
        // true and the branch routes to resolvePlayerUserId — conv.getParentId() is never
        // dereferenced. Proven here end to end, not just by reading the code.
        String coachCookies = loginAndGetCookies(COACH_EMAIL);
        Long conversationId = ensureConversation(coachCookies, SELF_PLAYER_PROFILE_ID);

        ResponseEntity<Map> sendResp = httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/conversations/" + conversationId + "/messages",
            HttpMethod.POST,
            Map.of("content", "Great session today!"),
            authenticatedHeaders(coachCookies),
            Map.class);

        assertThat(sendResp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Long messageId = toLong(sendResp.getBody().get("messageId"));

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            String moderationStatus = jdbcTemplate.queryForObject(
                "SELECT moderation_status FROM messaging.messages WHERE id = ?", String.class, messageId);
            assertThat(moderationStatus).isEqualTo("APPROVED");
        });
    }

    // ── mutation check ──
    //
    // Reverting V95 (restoring NOT NULL on messaging.conversations.parent_id) must fail
    // coachOpensConversationWithSelfBookingPlayer_returns200AndPersistsNullParentId with the exact
    // constraint-violation-turned-400 described in the story's AC3 trigger chain, not a clean pass.
    // Verified by inspection during implementation per the story's testing note — the assertion
    // above IS that check: it fails on ANY status other than 200, which a restored NOT NULL would
    // produce as a 400 generic.dataError.

    // ── helpers ──

    private Long ensureConversation(String cookies, long playerId) {
        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/conversations",
            HttpMethod.POST,
            Map.of("coachId", coachProfileId.toString(), "playerId", playerId),
            authenticatedHeaders(cookies),
            Map.class);
        return toLong(resp.getBody().get("conversationId"));
    }

    private Long toLong(Object val) {
        if (val == null) return null;
        if (val instanceof Number n) return n.longValue();
        return Long.parseLong(val.toString());
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
            "'ACTIVE', '1985-06-01', ?, 'Test', 'OTHER', 'en', 'User', 'DE', ?, " +
            "true, false, ?, 'EMAIL', ?, false, " +
            "?, 'BASIC_VERIFIED')",
            id,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            email,
            "93" + (id % 100000000),
            email, passwordHash, role);
    }

    private void grantAuthority(long userId, String roleName) {
        jdbcTemplate.update(
            "INSERT INTO main.user_authority (user_id, authority_id) " +
            "VALUES (?, (SELECT id FROM main.authority WHERE name = ?)) ON CONFLICT DO NOTHING",
            userId, roleName);
    }
}
