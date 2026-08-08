package com.softropic.skillars.platform.messaging.api;

import com.softropic.skillars.config.AbstractIntegrationTest;

import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.messaging.contract.ModerationVerdict;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Story deferred-16 AC4: an orphaned player_profiles row must cost the one conversation/label that
 * depends on it, never the whole caller's list or a 404 — and PLAYER identity must resolve through
 * playerProfileRepository.findByUserId, not by treating the caller's user id as a player-profile id.
 */
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class MessagingIdentityIT extends AbstractIntegrationTest {

    private static final String LOGIN_ENDPOINT  = "/api/auth/login";
    private static final String MESSAGING_BASE  = "/api/messaging";
    private static final String CLIENT_ID       = "testClientId";
    private static final String TEST_PASSWORD   = "TestPass@123!";

    private static final long PARENT_ID        = 9860_000_001L;
    private static final long PLAYER_ID_OK     = 9860_000_002L;
    private static final long PLAYER_ID_ORPHAN = 9860_000_003L; // referenced by a conversation, no player_profiles row
    private static final long COACH_USER_ID    = 9860_000_010L;
    private static final long SELF_PLAYER_USER_ID = 9860_000_020L;
    private static final long SELF_PLAYER_PROFILE_ID = 9860_000_021L;
    private static final long SELF_PLAYER2_USER_ID = 9860_000_022L;
    private static final long SELF_PLAYER2_PROFILE_ID = 9860_000_023L;
    private static final long NO_ROLE_USER_ID  = 9860_000_030L;

    private static final String PARENT_EMAIL      = "parent.identity@skillars-test.com";
    private static final String COACH_EMAIL       = "coach.identity@skillars-test.com";
    private static final String SELF_PLAYER_EMAIL  = "selfplayer.identity@skillars-test.com";
    private static final String SELF_PLAYER2_EMAIL = "selfplayer2.identity@skillars-test.com";
    private static final String NO_ROLE_EMAIL      = "norole.identity@skillars-test.com";


    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private PasswordEncoder passwordEncoder;

    @LocalServerPort private int randomServerPort;

    private UUID coachProfileId;
    private Long conversationOkId;
    private Long conversationOrphanId;
    private Long conversationSelfPlayerId;
    private Long selfPlayerMessageId;

    @BeforeEach
    void setUp() {
        when(geminiClient.evaluate(any())).thenReturn(ModerationVerdict.SAFE);

        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        coachProfileId = UUID.randomUUID();

        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (9860, 'ROLE_PARENT', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (9861, 'ROLE_COACH', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (9862, 'ROLE_ADMIN', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));

            insertUser(PARENT_ID, PARENT_EMAIL, passwordHash, "PARENT");
            grantAuthority(PARENT_ID, "ROLE_PARENT");

            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Identity Player', ?, 'MIDFIELDER', 'ADULT', ?, true, ?, 'system')",
                PLAYER_ID_OK, Date.valueOf(LocalDate.now().minusYears(20)), PARENT_ID, Timestamp.from(Instant.now()));

            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH");
            grantAuthority(COACH_USER_ID, "ROLE_COACH");
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles " +
                "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, 'Identity Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
                coachProfileId, COACH_USER_ID);

            // Booking so the coach can create a conversation with the real player
            jdbcTemplate.update(
                "INSERT INTO booking.bookings " +
                "(id, coach_id, parent_id, player_id, status, requested_start_time, requested_end_time, " +
                " version, created_at, updated_at, canonical_timezone) " +
                "VALUES (?, ?, ?, ?, 'COMPLETED', ?, ?, 0, ?, ?, 'Europe/Berlin')",
                UUID.randomUUID(), coachProfileId, PARENT_ID, PLAYER_ID_OK,
                Timestamp.from(Instant.now().minusSeconds(7200)),
                Timestamp.from(Instant.now().minusSeconds(3600)),
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()));

            // Self-registered adult player: ROLE_PLAYER, profile owned via user_id (no parent_id)
            insertUser(SELF_PLAYER_USER_ID, SELF_PLAYER_EMAIL, passwordHash, "PLAYER");
            grantAuthority(SELF_PLAYER_USER_ID, "ROLE_PLAYER");
            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, user_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Self Player', ?, 'MIDFIELDER', 'ADULT', ?, true, ?, 'system')",
                SELF_PLAYER_PROFILE_ID, Date.valueOf(LocalDate.now().minusYears(20)), SELF_PLAYER_USER_ID, Timestamp.from(Instant.now()));

            // A second self-registered player, party to nothing — the honest fixture for the
            // "200 + empty list, not 404" case now that SELF_PLAYER has a conversation.
            insertUser(SELF_PLAYER2_USER_ID, SELF_PLAYER2_EMAIL, passwordHash, "PLAYER");
            grantAuthority(SELF_PLAYER2_USER_ID, "ROLE_PLAYER");
            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, user_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Self Player Two', ?, 'MIDFIELDER', 'ADULT', ?, true, ?, 'system')",
                SELF_PLAYER2_PROFILE_ID, Date.valueOf(LocalDate.now().minusYears(20)), SELF_PLAYER2_USER_ID, Timestamp.from(Instant.now()));

            // Authenticated user with none of COACH/PARENT/PLAYER — but must hold SOME authority
            // from AppEndpoints.SECURED_AUTHORITIES (e.g. ROLE_ADMIN) to clear the filter-chain-level
            // hasAnyAuthority gate on /api/**; a caller with zero authorities never reaches the
            // controller at all, so it cannot exercise MessagingResource.resolveRole's own guard.
            insertUser(NO_ROLE_USER_ID, NO_ROLE_EMAIL, passwordHash, "PARENT");
            grantAuthority(NO_ROLE_USER_ID, "ROLE_ADMIN");

            return null;
        });

        // Conversation with the real player (created via API so it goes through the normal path)
        String coachCookies = loginAndGetCookies(COACH_EMAIL);
        conversationOkId = ensureConversation(coachCookies, PLAYER_ID_OK);

        // Conversation referencing a player_id with NO player_profiles row — messaging.conversations
        // has no FK on player_id (verified during story creation), so a direct insert is valid.
        conversationOrphanId = 9860_002_002L;
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO messaging.conversations (id, coach_id, player_id, parent_id, status, created_at, last_message_at) " +
                "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)",
                conversationOrphanId, coachProfileId, PLAYER_ID_ORPHAN, PARENT_ID,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
            return null;
        });

        // A conversation the SELF-REGISTERED PLAYER really is a party to. player_id holds their
        // PROFILE id while they authenticate as their USER id — the two differ, which is what makes
        // the tests below able to fail: the pre-fix code compared conv.playerId against the caller's
        // user id and would answer "not a party" / "no conversations" for all three.
        conversationSelfPlayerId = 9860_002_003L;
        selfPlayerMessageId = 9860_003_001L;
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO messaging.conversations (id, coach_id, player_id, parent_id, status, created_at, last_message_at) " +
                "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)",
                conversationSelfPlayerId, coachProfileId, SELF_PLAYER_PROFILE_ID, PARENT_ID,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
            jdbcTemplate.update(
                "INSERT INTO messaging.messages (id, conversation_id, sender_id, sender_role, content, " +
                "moderation_status, created_at) VALUES (?, ?, ?, 'COACH', 'Coach message to adult player', " +
                "'APPROVED', ?)",
                selfPlayerMessageId, conversationSelfPlayerId, COACH_USER_ID, Timestamp.from(Instant.now()));
            return null;
        });
    }


    @Test
    void coachConversationList_orphanedPlayerProfile_returns200WithBothConversations() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<List> resp = httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/conversations",
            HttpMethod.GET, null, authenticatedHeaders(coachCookies), List.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> body = resp.getBody();
        assertThat(body).hasSizeGreaterThanOrEqualTo(2);
        Map<String, Object> orphanEntry = body.stream()
            .filter(e -> conversationOrphanId.equals(toLong(e.get("conversationId"))))
            .findFirst().orElseThrow(() -> new AssertionError("Orphan conversation missing from response"));
        assertThat(orphanEntry.get("otherPartyName")).isEqualTo("Unknown Player");
    }

    @Test
    void parentConversationList_orphanedPlayerProfile_excludedFromList() {
        String parentCookies = loginAndGetCookies(PARENT_EMAIL);

        ResponseEntity<List> resp = httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/conversations",
            HttpMethod.GET, null, authenticatedHeaders(parentCookies), List.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> body = resp.getBody();
        boolean orphanPresent = body.stream()
            .anyMatch(e -> conversationOrphanId.equals(toLong(e.get("conversationId"))));
        assertThat(orphanPresent).isFalse();
    }

    @Test
    void selfRegisteredPlayer_noConversationsYet_returns200WithEmptyList_notFound() {
        // SELF_PLAYER2 holds ROLE_PLAYER and a profile but is party to nothing. Uses a second user
        // because SELF_PLAYER is now deliberately inside a conversation — an empty list there would
        // no longer distinguish "nothing to show" from "looked up by the wrong id".
        String playerCookies = loginAndGetCookies(SELF_PLAYER2_EMAIL);

        ResponseEntity<List> resp = httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/conversations",
            HttpMethod.GET, null, authenticatedHeaders(playerCookies), List.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isEmpty();
    }

    @Test
    void unrecognisedMessagingRole_returns403WithNotAPartyCode() {
        String noRoleCookies = loginAndGetCookies(NO_ROLE_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/conversations",
            HttpMethod.GET, null, authenticatedHeaders(noRoleCookies), List.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(ex.getResponseBodyAsString()).contains("messaging.notAParty");
            });
    }

    @Test
    void reportConversation_selfRegisteredPlayerWithNoBooking_returns403NotAParty() {
        // Exercises MessagingReportService.verifyIsParty's own copy of the identity fix: the caller
        // holds ROLE_PLAYER and has a profile, but is not a party to conversationOkId (coach1/player-ok).
        String playerCookies = loginAndGetCookies(SELF_PLAYER_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/conversations/" + conversationOkId + "/report",
            HttpMethod.POST,
            Map.of("reason", "SPAM"),
            authenticatedHeaders(playerCookies),
            Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(ex.getResponseBodyAsString()).contains("messaging.notAParty");
            });
    }

    // ── Code review 2026-08-05: the identity fix needs cases that can actually fail ──
    //
    // Every pre-existing PLAYER case here resolves to the same answer under the old and the new
    // comparison, because the caller is never a party to the conversation under test — 403 either
    // way. These three put the self-registered player INSIDE a conversation, where the profile-id
    // and user-id readings diverge, and cover both copies of verifyIsParty plus the message-report
    // endpoint that had no coverage at all.

    @Test
    void selfRegisteredPlayer_ownConversation_appearsInTheirList() {
        String playerCookies = loginAndGetCookies(SELF_PLAYER_EMAIL);

        ResponseEntity<List> resp = httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/conversations",
            HttpMethod.GET, null, authenticatedHeaders(playerCookies), List.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Pre-fix this list is EMPTY: findActiveByPlayerId was handed the caller's user id.
        assertThat(resp.getBody()).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) resp.getBody().get(0);
        assertThat(toLong(summary.get("conversationId"))).isEqualTo(conversationSelfPlayerId);
    }

    @Test
    void reportConversation_selfRegisteredPlayerIsParty_succeeds() {
        // MessagingReportService.verifyIsParty — the hand-copy. Pre-fix: compares the conversation's
        // player-profile id against the caller's user id, so a real party is refused with 403.
        String playerCookies = loginAndGetCookies(SELF_PLAYER_EMAIL);

        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/conversations/" + conversationSelfPlayerId + "/report",
            HttpMethod.POST, Map.of("reason", "SPAM"),
            authenticatedHeaders(playerCookies), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().get("reportId")).isNotNull();
        Integer reportCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM messaging.conversation_reports WHERE conversation_id = ? AND reported_by = ?",
            Integer.class, conversationSelfPlayerId, SELF_PLAYER_USER_ID);
        assertThat(reportCount).isEqualTo(1);
    }

    @Test
    void reportMessage_selfRegisteredPlayerIsParty_succeeds() {
        // The message-report endpoint goes through the same copy and had no coverage whatsoever.
        String playerCookies = loginAndGetCookies(SELF_PLAYER_EMAIL);

        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/conversations/" + conversationSelfPlayerId
                + "/messages/" + selfPlayerMessageId + "/report",
            HttpMethod.POST, Map.of("reason", "HARASSMENT"),
            authenticatedHeaders(playerCookies), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Integer reportCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM messaging.message_reports WHERE message_id = ? AND reported_by = ?",
            Integer.class, selfPlayerMessageId, SELF_PLAYER_USER_ID);
        assertThat(reportCount).isEqualTo(1);
    }

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
            "92" + (id % 100000000),
            email, passwordHash, role);
    }

    private void grantAuthority(long userId, String roleName) {
        jdbcTemplate.update(
            "INSERT INTO main.user_authority (user_id, authority_id) " +
            "VALUES (?, (SELECT id FROM main.authority WHERE name = ?)) ON CONFLICT DO NOTHING",
            userId, roleName);
    }
}
