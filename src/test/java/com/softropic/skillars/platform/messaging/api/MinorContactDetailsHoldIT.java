package com.softropic.skillars.platform.messaging.api;

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

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.softropic.skillars.platform.messaging.contract.ModerationVerdict;

// skillars-deferred-160 AC3: used to @Import its own FailureEventCapture, which forked a whole
// Spring context for one list-of-events bean. The shared RecordedEvents (TestConfig) replaces it,
// so this class now shares the base context.
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class MinorContactDetailsHoldIT extends AbstractIntegrationTest {

    private static final String LOGIN_ENDPOINT  = "/api/auth/login";
    private static final String MESSAGING_BASE  = "/api/messaging";
    private static final String CLIENT_ID       = "testClientId";
    private static final String TEST_PASSWORD   = "TestPass@123!";

    private static final long PARENT_ID     = 9830000001L;
    private static final long PLAYER_ID     = 9830000002L;
    private static final long COACH_USER_ID = 9830000010L;

    private static final String PARENT_EMAIL = "parent.contacthold@skillars-test.com";
    private static final String COACH_EMAIL  = "coach.contacthold@skillars-test.com";


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
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (9830, 'ROLE_PARENT', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (9831, 'ROLE_COACH', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));

            insertUser(PARENT_ID, PARENT_EMAIL, passwordHash, "PARENT");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_PARENT')) ON CONFLICT DO NOTHING",
                PARENT_ID);

            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'ContactHold Player', ?, 'FORWARD', 'AGE_13_17', ?, false, ?, 'system')",
                PLAYER_ID, Date.valueOf(LocalDate.now().minusYears(15)),
                PARENT_ID, Timestamp.from(Instant.now()));

            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_COACH')) ON CONFLICT DO NOTHING",
                COACH_USER_ID);
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles " +
                "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, 'ContactHold Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
                coachProfileId, COACH_USER_ID);

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
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id IN (9830, 9831)");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void coachSharesPhoneWithSupervisedMinor_isHeldWithoutConsultingGemini() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);
        Long conversationId = ensureConversation(coachCookies);

        ResponseEntity<Map> resp = send(coachCookies, conversationId, "text me on 0791 112 3456 instead");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(latestStatus(conversationId)).isEqualTo("UNDER_REVIEW");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT delivered_at::text FROM messaging.messages WHERE conversation_id = ? ORDER BY created_at DESC LIMIT 1",
            String.class, conversationId)).isNull();
        verify(geminiClient, never()).evaluate(any());
    }

    @Test
    void cleanMessageToSupervisedMinor_isModeratedNormally() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);
        Long conversationId = ensureConversation(coachCookies);

        ResponseEntity<Map> resp = send(coachCookies, conversationId, "See you at 5 pm, we won 3-2");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(latestStatus(conversationId)).isEqualTo("APPROVED");
    }

    @Test
    void suspendedCoach_cannotSendOrRead_butParentStillReadsHistory() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);
        String parentCookies = loginAndGetCookies(PARENT_EMAIL);
        Long conversationId = ensureConversation(coachCookies);
        send(coachCookies, conversationId, "hello there");

        // Wrapped: a bare jdbcTemplate write in an IT body never commits (hikari auto-commit=false).
        transactionTemplate.execute(status -> jdbcTemplate.update(
            "UPDATE marketplace.coach_profiles SET status = 'SUSPENDED' WHERE id = ?", coachProfileId));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> send(coachCookies, conversationId, "still here"))
            .isInstanceOf(org.springframework.web.client.HttpClientErrorException.class)
            .satisfies(e -> {
                var ex = (org.springframework.web.client.HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(ex.getResponseBodyAsString()).contains("messaging.coachSuspended");
            });
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
                baseUrl() + MESSAGING_BASE + "/conversations/" + conversationId + "/messages",
                HttpMethod.GET, null, authenticatedHeaders(coachCookies), Map.class))
            .isInstanceOf(org.springframework.web.client.HttpClientErrorException.class);

        ResponseEntity<Map> parentRead = httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/conversations/" + conversationId + "/messages",
            HttpMethod.GET, null, authenticatedHeaders(parentCookies), Map.class);
        assertThat(parentRead.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<Map> send(String cookies, Long conversationId, String content) {
        return httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/conversations/" + conversationId + "/messages",
            HttpMethod.POST,
            Map.of("content", content),
            authenticatedHeaders(cookies),
            Map.class);
    }

    private String latestStatus(Long conversationId) {
        return jdbcTemplate.queryForObject(
            "SELECT moderation_status FROM messaging.messages WHERE conversation_id = ? ORDER BY created_at DESC LIMIT 1",
            String.class, conversationId);
    }

    private Long ensureConversation(String cookies) {
        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            baseUrl() + MESSAGING_BASE + "/conversations",
            HttpMethod.POST,
            Map.of("coachId", coachProfileId.toString(), "playerId", PLAYER_ID),
            authenticatedHeaders(cookies),
            Map.class);
        return toLong(resp.getBody().get("conversationId"));
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
            "90" + (id % 100000000),
            email, passwordHash, role);
    }
}
