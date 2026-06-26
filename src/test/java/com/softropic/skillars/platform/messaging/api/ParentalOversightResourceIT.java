package com.softropic.skillars.platform.messaging.api;

import com.softropic.skillars.config.TestConfig;
import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.SecurityIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
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

@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestConfig.class)
@TestPropertySource(properties = {
    "spring.cloud.compatibility-verifier.enabled=false",
    "rate.limiting.enabled=false",
    "allowed.clients=testClientId"
})
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class ParentalOversightResourceIT {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String MESSAGING_BASE = "/api/messaging";
    private static final String CLIENT_ID      = "testClientId";
    private static final String TEST_PASSWORD  = "TestPass@123!";

    // ID range: 981x to avoid collision with 970x (ConversationResourceIT) and 980x (MessagingAccessControlIT)
    private static final long PARENT_ID           = 9810000001L;
    private static final long U10_PLAYER_ID       = 9810000002L;
    private static final long SUPERVISED_PLAYER_ID = 9810000003L;
    private static final long ADULT_PLAYER_ID     = 9810000004L;
    private static final long COACH_USER_ID       = 9810000010L;
    private static final long OTHER_PARENT_ID     = 9810000020L;
    private static final long OTHER_PLAYER_ID     = 9810000021L;

    // Conversation IDs (TSID-compatible Longs in the test range)
    private static final long U10_CONV_ID          = 9810000100L;
    private static final long SUPERVISED_CONV_ID   = 9810000101L;
    private static final long ADULT_CONV_ID        = 9810000102L;
    private static final long MESSAGE_ID           = 9810000200L;

    private static final String PARENT_EMAIL = "parent.oversight@skillars-test.com";
    private static final String COACH_EMAIL  = "coach.oversight@skillars-test.com";
    private static final String COACH_DISPLAY_NAME = "Oversight Coach";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private PasswordEncoder passwordEncoder;

    @LocalServerPort private int randomServerPort;

    private UUID coachProfileId;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        coachProfileId = UUID.randomUUID();

        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (9810, 'ROLE_PARENT', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (9811, 'ROLE_COACH', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));

            // Parent user
            insertUser(PARENT_ID, PARENT_EMAIL, passwordHash, "PARENT");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_PARENT')) ON CONFLICT DO NOTHING",
                PARENT_ID);

            // U10 player (age = 10 → PARENT_MANAGED per u10MaxAge=10)
            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Minor Player', ?, 'FORWARD', 'U10', ?, false, ?, 'system')",
                U10_PLAYER_ID, Date.valueOf(LocalDate.now().minusYears(10)),
                PARENT_ID, Timestamp.from(Instant.now()));

            // Supervised player (age = 15 → AGE_13_17)
            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Supervised Player', ?, 'MIDFIELDER', 'AGE_13_17', ?, false, ?, 'system')",
                SUPERVISED_PLAYER_ID, Date.valueOf(LocalDate.now().minusYears(15)),
                PARENT_ID, Timestamp.from(Instant.now()));

            // Adult player (age = 18 → ADULT)
            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Adult Player', ?, 'GOALKEEPER', 'ADULT', ?, true, ?, 'system')",
                ADULT_PLAYER_ID, Date.valueOf(LocalDate.now().minusYears(18)),
                PARENT_ID, Timestamp.from(Instant.now()));

            // Coach user
            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_COACH')) ON CONFLICT DO NOTHING",
                COACH_USER_ID);
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles " +
                "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, ?, 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
                coachProfileId, COACH_USER_ID, COACH_DISPLAY_NAME);

            // Other parent and their player (for ownership mismatch test)
            insertUser(OTHER_PARENT_ID, "other.parent.oversight@skillars-test.com", passwordHash, "PARENT");
            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Other Player', ?, 'DEFENDER', 'U10', ?, false, ?, 'system')",
                OTHER_PLAYER_ID, Date.valueOf(LocalDate.now().minusYears(10)),
                OTHER_PARENT_ID, Timestamp.from(Instant.now()));

            // Conversations (created directly — bypasses booking check which is not relevant for oversight)
            Timestamp now = Timestamp.from(Instant.now());
            jdbcTemplate.update(
                "INSERT INTO messaging.conversations (id, coach_id, player_id, parent_id, status, created_at) " +
                "VALUES (?, ?, ?, ?, 'ACTIVE', ?)",
                U10_CONV_ID, coachProfileId, U10_PLAYER_ID, PARENT_ID, now);
            jdbcTemplate.update(
                "INSERT INTO messaging.conversations (id, coach_id, player_id, parent_id, status, created_at) " +
                "VALUES (?, ?, ?, ?, 'ACTIVE', ?)",
                SUPERVISED_CONV_ID, coachProfileId, SUPERVISED_PLAYER_ID, PARENT_ID, now);
            jdbcTemplate.update(
                "INSERT INTO messaging.conversations (id, coach_id, player_id, parent_id, status, created_at) " +
                "VALUES (?, ?, ?, ?, 'ACTIVE', ?)",
                ADULT_CONV_ID, coachProfileId, ADULT_PLAYER_ID, PARENT_ID, now);

            // One APPROVED message in the supervised conversation
            jdbcTemplate.update(
                "INSERT INTO messaging.messages " +
                "(id, conversation_id, sender_id, sender_role, content, moderation_status, delivered_at, created_at) " +
                "VALUES (?, ?, ?, 'COACH', 'Hello supervised player', 'APPROVED', ?, ?)",
                MESSAGE_ID, SUPERVISED_CONV_ID, COACH_USER_ID,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));

            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM messaging.messages WHERE id = ?", MESSAGE_ID);
            jdbcTemplate.update("DELETE FROM messaging.conversations WHERE id IN (?, ?, ?)",
                U10_CONV_ID, SUPERVISED_CONV_ID, ADULT_CONV_ID);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM main.player_profiles WHERE id IN (?, ?, ?, ?)",
                U10_PLAYER_ID, SUPERVISED_PLAYER_ID, ADULT_PLAYER_ID, OTHER_PLAYER_ID);
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?, ?)",
                PARENT_ID, COACH_USER_ID, OTHER_PARENT_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?, ?)",
                PARENT_ID, COACH_USER_ID, OTHER_PARENT_ID);
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id IN (9810, 9811)");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    // ── AC5: Parent can access minor player's conversations ──

    @Test
    void getPlayerConversations_minorPlayer_returns200WithList() {
        String parentCookies = loginAndGetCookies(PARENT_EMAIL);

        ResponseEntity<List> resp = httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/players/" + U10_PLAYER_ID + "/conversations",
            HttpMethod.GET, null, authenticatedHeaders(parentCookies), List.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
    }

    // ── AC5: Adult player → 403 parentalOversightNotApplicable ──

    @Test
    void getPlayerConversations_adultPlayer_returns403OversightNotApplicable() {
        String parentCookies = loginAndGetCookies(PARENT_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/players/" + ADULT_PLAYER_ID + "/conversations",
            HttpMethod.GET, null, authenticatedHeaders(parentCookies), List.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(ex.getResponseBodyAsString()).contains("messaging.parentalOversightNotApplicable");
            });
    }

    // ── AC5: Player belongs to different parent → 403 notAParty ──

    @Test
    void getPlayerConversations_playerBelongsToDifferentParent_returns403NotAParty() {
        String parentCookies = loginAndGetCookies(PARENT_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/players/" + OTHER_PLAYER_ID + "/conversations",
            HttpMethod.GET, null, authenticatedHeaders(parentCookies), List.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(ex.getResponseBodyAsString()).contains("messaging.notAParty");
            });
    }

    // ── AC6: Parent can read messages in supervised player's conversation ──

    @Test
    void getPlayerConversationMessages_supervisedPlayer_returns200WithMessages() {
        String parentCookies = loginAndGetCookies(PARENT_EMAIL);

        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/players/" + SUPERVISED_PLAYER_ID +
            "/conversations/" + SUPERVISED_CONV_ID + "/messages",
            HttpMethod.GET, null, authenticatedHeaders(parentCookies), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKey("content");
        List<?> messages = (List<?>) resp.getBody().get("content");
        assertThat(messages).hasSize(1);
    }

    // ── AC6: Conversation playerId mismatch → 403 notAParty ──

    @Test
    void getPlayerConversationMessages_conversationBelongsToDifferentPlayer_returns403NotAParty() {
        String parentCookies = loginAndGetCookies(PARENT_EMAIL);

        // Use adult player's conversationId but pass minor player's ID in path
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/players/" + U10_PLAYER_ID +
            "/conversations/" + ADULT_CONV_ID + "/messages",
            HttpMethod.GET, null, authenticatedHeaders(parentCookies), Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(ex.getResponseBodyAsString()).contains("messaging.notAParty");
            });
    }

    // ── AC5: Coach cannot access parental oversight endpoint ──

    @Test
    void getPlayerConversations_asCoach_returns403() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/players/" + U10_PLAYER_ID + "/conversations",
            HttpMethod.GET, null, authenticatedHeaders(coachCookies), List.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ── AC4: getConversations as parent excludes adult player conversations ──

    @Test
    void getConversations_asParent_excludesAdultPlayerConversation() {
        String parentCookies = loginAndGetCookies(PARENT_EMAIL);

        ResponseEntity<List> resp = httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/conversations",
            HttpMethod.GET, null, authenticatedHeaders(parentCookies), List.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<?, ?>> convs = (List<Map<?, ?>>) resp.getBody();

        // Should only include U10 and supervised (not adult)
        assertThat(convs).hasSize(2);
        List<Long> ids = convs.stream().map(c -> toLong(c.get("conversationId"))).toList();
        assertThat(ids).doesNotContain((long) ADULT_CONV_ID);
    }

    // ── AC5: SUPERVISED player's conversations are accessible via oversight list endpoint ──

    @Test
    void getPlayerConversations_supervisedPlayer_returns200WithList() {
        String parentCookies = loginAndGetCookies(PARENT_EMAIL);

        ResponseEntity<List> resp = httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/players/" + SUPERVISED_PLAYER_ID + "/conversations",
            HttpMethod.GET, null, authenticatedHeaders(parentCookies), List.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
    }

    // ── AC6: oversight messages endpoint does NOT update parent lastReadAt ──

    @Test
    void getPlayerConversationMessages_supervisedPlayer_doesNotUpdateLastReadAt() {
        String parentCookies = loginAndGetCookies(PARENT_EMAIL);

        // Supervised conversation has one APPROVED message from the coach — parent hasn't read it, so unread = 1
        ResponseEntity<List> before = httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/conversations",
            HttpMethod.GET, null, authenticatedHeaders(parentCookies), List.class);
        long unreadBefore = ((List<Map<?, ?>>) before.getBody()).stream()
            .filter(c -> toLong(c.get("conversationId")).equals((long) SUPERVISED_CONV_ID))
            .mapToLong(c -> toLong(c.get("unreadCount")))
            .findFirst().orElse(0L);
        assertThat(unreadBefore).isGreaterThan(0);

        // Read via oversight endpoint
        httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/players/" + SUPERVISED_PLAYER_ID +
            "/conversations/" + SUPERVISED_CONV_ID + "/messages",
            HttpMethod.GET, null, authenticatedHeaders(parentCookies), Map.class);

        // Unread count must be unchanged — oversight read must not advance lastReadAt
        ResponseEntity<List> after = httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/conversations",
            HttpMethod.GET, null, authenticatedHeaders(parentCookies), List.class);
        long unreadAfter = ((List<Map<?, ?>>) after.getBody()).stream()
            .filter(c -> toLong(c.get("conversationId")).equals((long) SUPERVISED_CONV_ID))
            .mapToLong(c -> toLong(c.get("unreadCount")))
            .findFirst().orElse(0L);
        assertThat(unreadAfter).isEqualTo(unreadBefore);
    }

    // ── AC4: getConversations as parent shows correct otherPartyName labels ──

    @Test
    void getConversations_asParent_supervisedConversationHasOversightLabel() {
        String parentCookies = loginAndGetCookies(PARENT_EMAIL);

        ResponseEntity<List> resp = httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/conversations",
            HttpMethod.GET, null, authenticatedHeaders(parentCookies), List.class);

        List<Map<?, ?>> convs = (List<Map<?, ?>>) resp.getBody();

        // SUPERVISED (13-17) → "{playerFirstName}'s conversation with {coachName}"
        String supervisedLabel = convs.stream()
            .filter(c -> toLong(c.get("conversationId")).equals((long) SUPERVISED_CONV_ID))
            .findFirst()
            .map(c -> (String) c.get("otherPartyName"))
            .orElse(null);
        assertThat(supervisedLabel).isNotNull().contains("conversation with");

        // U10 (primary participant) → coach displayName
        String u10Label = convs.stream()
            .filter(c -> toLong(c.get("conversationId")).equals((long) U10_CONV_ID))
            .findFirst()
            .map(c -> (String) c.get("otherPartyName"))
            .orElse(null);
        assertThat(u10Label).isNotNull().isEqualTo(COACH_DISPLAY_NAME);
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

    private String baseUrl() {
        return "http://localhost:" + randomServerPort;
    }

    private Long toLong(Object val) {
        if (val instanceof Number n) return n.longValue();
        if (val instanceof String s) return Long.parseLong(s);
        return null;
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
            "71" + (id % 100000000),
            email, passwordHash, role);
    }
}
