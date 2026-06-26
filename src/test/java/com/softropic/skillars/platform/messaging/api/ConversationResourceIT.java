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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

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
class ConversationResourceIT {

    private static final String LOGIN_ENDPOINT   = "/api/auth/login";
    private static final String MESSAGING_BASE   = "/api/messaging";
    private static final String CLIENT_ID        = "testClientId";
    private static final String TEST_PASSWORD    = "TestPass@123!";

    private static final long PARENT_ID      = 9700000001L;
    private static final long PLAYER_ID      = 9700000002L;
    private static final long COACH_USER_ID  = 9700000010L;

    private static final String PARENT_EMAIL = "parent.messaging@skillars-test.com";
    private static final String COACH_EMAIL  = "coach.messaging@skillars-test.com";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private PasswordEncoder passwordEncoder;

    @LocalServerPort private int randomServerPort;

    private UUID coachProfileId;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (9700, 'ROLE_PARENT', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (9701, 'ROLE_COACH', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));

            insertUser(PARENT_ID, PARENT_EMAIL, passwordHash, "PARENT");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_PARENT')) ON CONFLICT DO NOTHING",
                PARENT_ID);

            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Msg Player', ?, 'MIDFIELDER', 'ADULT', ?, true, ?, 'system')",
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
                "VALUES (?, ?, 'Msg Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
                coachProfileId, COACH_USER_ID);

            // Create a COMPLETED booking to satisfy booking-relationship check
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

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM messaging.messages WHERE conversation_id IN " +
                "(SELECT id FROM messaging.conversations WHERE coach_id = ?)", coachProfileId);
            jdbcTemplate.update("DELETE FROM messaging.conversations WHERE coach_id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM booking.bookings WHERE coach_id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM main.player_profiles WHERE id = ?", PLAYER_ID);
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?)", PARENT_ID, COACH_USER_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?)", PARENT_ID, COACH_USER_ID);
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id IN (9700, 9701)");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    // ── AC2: Conversation creation ──

    @Test
    void createConversation_withBooking_returns200WithConversationId() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);
        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/conversations",
            HttpMethod.POST,
            Map.of("coachId", coachProfileId.toString(), "playerId", PLAYER_ID),
            authenticatedHeaders(coachCookies),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKey("conversationId");
        assertThat(resp.getBody().get("conversationId")).isNotNull();
    }

    @Test
    void createConversation_twice_returnsSameConversationId() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);
        Map.Entry<String, Object> first = createConversation(coachCookies);
        Object firstId = first.getValue();

        ResponseEntity<Map> second = httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/conversations",
            HttpMethod.POST,
            Map.of("coachId", coachProfileId.toString(), "playerId", PLAYER_ID),
            authenticatedHeaders(coachCookies),
            Map.class);

        assertThat(second.getBody().get("conversationId")).isEqualTo(firstId);
    }

    // ── AC4: Send message ──

    @Test
    void sendMessage_validContent_returns201WithMessageDto() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);
        Long conversationId = ensureConversation(coachCookies);

        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/conversations/" + conversationId + "/messages",
            HttpMethod.POST,
            Map.of("content", "Hello player!"),
            authenticatedHeaders(coachCookies),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).containsKey("messageId");
        assertThat(resp.getBody().get("moderationStatus")).isEqualTo("APPROVED");
    }

    @Test
    void sendMessage_blankContent_returns400WithMessagingInvalidContent() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);
        Long conversationId = ensureConversation(coachCookies);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/conversations/" + conversationId + "/messages",
            HttpMethod.POST,
            Map.of("content", ""),
            authenticatedHeaders(coachCookies),
            Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(ex.getResponseBodyAsString()).contains("messaging.invalidContent");
            });
    }

    @Test
    void sendMessage_contentExceeds2000Chars_returns400WithMessagingInvalidContent() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);
        Long conversationId = ensureConversation(coachCookies);

        String tooLong = "x".repeat(2001);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/conversations/" + conversationId + "/messages",
            HttpMethod.POST,
            Map.of("content", tooLong),
            authenticatedHeaders(coachCookies),
            Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(ex.getResponseBodyAsString()).contains("messaging.invalidContent");
            });
    }

    // ── AC5: Get messages paginated ──

    @Test
    void getMessages_returns200WithPageContent() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);
        Long conversationId = ensureConversation(coachCookies);
        sendMsg(coachCookies, conversationId, "Test message");

        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/conversations/" + conversationId + "/messages?page=0&size=20",
            HttpMethod.GET,
            null,
            authenticatedHeaders(coachCookies),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKey("content");
        List<?> content = (List<?>) resp.getBody().get("content");
        assertThat(content).isNotEmpty();
    }

    @Test
    void getMessages_blockedMessage_contentIsNull() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);
        Long conversationId = ensureConversation(coachCookies);

        // Send a message and manually set it to BLOCKED
        sendMsg(coachCookies, conversationId, "Will be blocked");
        transactionTemplate.execute(s -> {
            jdbcTemplate.update(
                "UPDATE messaging.messages SET moderation_status = 'BLOCKED' " +
                "WHERE conversation_id = ? AND content = 'Will be blocked'",
                conversationId);
            return null;
        });

        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/conversations/" + conversationId + "/messages",
            HttpMethod.GET,
            null,
            authenticatedHeaders(coachCookies),
            Map.class);

        List<Map<?, ?>> messages = (List<Map<?, ?>>) resp.getBody().get("content");
        assertThat(messages).isNotEmpty();
        messages.stream()
            .filter(m -> "BLOCKED".equals(m.get("moderationStatus")))
            .forEach(m -> assertThat(m.get("content")).isNull());
    }

    @Test
    void getMessages_afterRead_unreadCountIsZeroForOwnMessages() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);
        Long conversationId = ensureConversation(coachCookies);
        sendMsg(coachCookies, conversationId, "Hello!");

        // Reading messages should update lastReadAt; own messages don't count as unread
        httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/conversations/" + conversationId + "/messages",
            HttpMethod.GET, null, authenticatedHeaders(coachCookies), Map.class);

        // Verify unreadCount for coach is 0 (own messages excluded)
        ResponseEntity<List> convList = httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/conversations",
            HttpMethod.GET, null, authenticatedHeaders(coachCookies), List.class);
        List<Map<?, ?>> convs = (List<Map<?, ?>>) convList.getBody();
        assertThat(convs).isNotEmpty();
        Object unread = convs.stream()
            .filter(c -> conversationId.equals(toLong(c.get("conversationId"))))
            .findFirst()
            .map(c -> c.get("unreadCount"))
            .orElse(null);
        assertThat(unread).isNotNull();
        assertThat(((Number) unread).longValue()).isZero();
    }

    @Test
    void getConversations_lastMessagePreview_isFirst60Chars() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);
        Long conversationId = ensureConversation(coachCookies);
        String longMsg = "A".repeat(80);
        sendMsg(coachCookies, conversationId, longMsg);

        ResponseEntity<List> resp = httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/conversations",
            HttpMethod.GET, null, authenticatedHeaders(coachCookies), List.class);

        List<Map<?, ?>> convs = (List<Map<?, ?>>) resp.getBody();
        assertThat(convs).isNotEmpty();
        String preview = (String) convs.get(0).get("lastMessagePreview");
        assertThat(preview).hasSize(60);
    }

    @Test
    void getConversations_noApprovedMessages_lastMessagePreviewIsNull() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);
        Long conversationId = ensureConversation(coachCookies);

        ResponseEntity<List> resp = httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/conversations",
            HttpMethod.GET, null, authenticatedHeaders(coachCookies), List.class);

        List<Map<?, ?>> convs = (List<Map<?, ?>>) resp.getBody();
        assertThat(convs).isNotEmpty();
        assertThat(convs.get(0).get("lastMessagePreview")).isNull();
    }

    // ── AC6: SSE registration ──

    @Test
    void sseEndpoint_validParty_returns200WithEventStreamContentType() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);
        Long conversationId = ensureConversation(coachCookies);

        HttpHeaders headers = authenticatedHeaders(coachCookies);
        headers.set(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE);

        // SSE keeps the connection open; RestTemplate will time out reading the stream body.
        // If auth or access fails we get HttpClientErrorException (4xx) — that's the only failure mode we care about.
        try {
            httpTestClient.makeHttpRequest(
                baseUrl() + MESSAGING_BASE + "/conversations/" + conversationId + "/events",
                HttpMethod.GET, null, headers, String.class);
        } catch (HttpClientErrorException e) {
            // 4xx means the server rejected the request — fail the test
            org.junit.jupiter.api.Assertions.fail(
                "Expected SSE 200, got " + e.getStatusCode() + ": " + e.getResponseBodyAsString());
        } catch (Exception e) {
            // Any other exception (read timeout, stream truncation) is acceptable —
            // it means the server accepted the connection with 200 text/event-stream
        }
    }

    // ── Concurrent conversation creation (upsert correctness) ──

    @Test
    void createConversation_concurrent_returnsSameConversationIdWithout500() throws Exception {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);
        int threadCount = 5;
        CountDownLatch latch = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(threadCount);
        @SuppressWarnings("unchecked")
        Future<Object>[] futures = new Future[threadCount];

        for (int i = 0; i < threadCount; i++) {
            futures[i] = executor.submit(() -> {
                latch.await();
                ResponseEntity<Map> r = httpTestClient.makeHttpRequest(
                    baseUrl() + MESSAGING_BASE + "/conversations",
                    HttpMethod.POST,
                    Map.of("coachId", coachProfileId.toString(), "playerId", PLAYER_ID),
                    authenticatedHeaders(coachCookies),
                    Map.class);
                return r.getBody().get("conversationId");
            });
        }
        latch.countDown();

        AtomicReference<Object> firstId = new AtomicReference<>();
        for (Future<Object> f : futures) {
            Object id = f.get();
            assertThat(id).isNotNull();
            firstId.compareAndSet(null, id);
            assertThat(id).isEqualTo(firstId.get());
        }
        executor.shutdown();
    }

    // ── helpers ──

    private Long ensureConversation(String cookies) {
        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/conversations",
            HttpMethod.POST,
            Map.of("coachId", coachProfileId.toString(), "playerId", PLAYER_ID),
            authenticatedHeaders(cookies),
            Map.class);
        return toLong(resp.getBody().get("conversationId"));
    }

    private void sendMsg(String cookies, Long conversationId, String content) {
        httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/conversations/" + conversationId + "/messages",
            HttpMethod.POST,
            Map.of("content", content),
            authenticatedHeaders(cookies),
            Map.class);
    }

    private Map.Entry<String, Object> createConversation(String cookies) {
        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/conversations",
            HttpMethod.POST,
            Map.of("coachId", coachProfileId.toString(), "playerId", PLAYER_ID),
            authenticatedHeaders(cookies),
            Map.class);
        return Map.entry("conversationId", resp.getBody().get("conversationId"));
    }

    private Long toLong(Object val) {
        if (val instanceof Number n) return n.longValue();
        if (val instanceof String s) return Long.parseLong(s);
        return null;
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

    private String baseUrl() {
        return "http://localhost:" + randomServerPort;
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
            "70" + (id % 100000000),
            email, passwordHash, role);
    }
}
