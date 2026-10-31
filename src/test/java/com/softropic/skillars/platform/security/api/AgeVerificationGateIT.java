package com.softropic.skillars.platform.security.api;

import com.softropic.skillars.config.AbstractIntegrationTest;
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

/**
 * S-27: the age-verification gates and endpoints with the feature in its default (off) state, which is how dev, UAT
 * and any environment without Stripe Identity run: the fallback applies, so a self-registered player and a coach may
 * chat only after a COMPLETED session, and the verification endpoints report "not enabled".
 */
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class AgeVerificationGateIT extends AbstractIntegrationTest {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String CLIENT_ID = "testClientId";
    private static final String TEST_PASSWORD = "TestPass@123!";

    private static final long SELF_USER_ID = 9810000001L;
    private static final long SELF_PROFILE_ID = 9810000002L;
    private static final long COACH_USER_ID = 9810000010L;
    private static final String SELF_EMAIL = "agegate.player@skillars-test.com";
    private static final String COACH_EMAIL = "agegate.coach@skillars-test.com";

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
            for (String[] a : new String[][] {{"9811", "ROLE_PLAYER"}, {"9812", "ROLE_COACH"}}) {
                jdbcTemplate.update(
                    "INSERT INTO main.authority (id, name, status, created_by, created_date) "
                        + "VALUES (" + a[0] + ", '" + a[1] + "', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                    Timestamp.from(Instant.now()));
            }
            insertUser(SELF_USER_ID, SELF_EMAIL, passwordHash, "PLAYER");
            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH");
            jdbcTemplate.update("INSERT INTO main.user_authority (user_id, authority_id) "
                + "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_PLAYER'))", SELF_USER_ID);
            jdbcTemplate.update("INSERT INTO main.user_authority (user_id, authority_id) "
                + "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_COACH'))", COACH_USER_ID);
            jdbcTemplate.update(
                "INSERT INTO main.player_profiles "
                    + "(id, name, date_of_birth, position, age_tier, user_id, independent_account_allowed, created_at, created_by) "
                    + "VALUES (?, 'Gate Player', ?, 'MIDFIELDER', 'ADULT', ?, true, ?, 'system')",
                SELF_PROFILE_ID, Date.valueOf(LocalDate.now().minusYears(25)), SELF_USER_ID,
                Timestamp.from(Instant.now()));
            coachProfileId = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles "
                    + "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) "
                    + "VALUES (?, ?, 'Gate Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
                coachProfileId, COACH_USER_ID);
            insertBooking("CONFIRMED");
            return null;
        });
    }

    @Test
    void fallback_unverifiedSelfRegisteredPlayer_coachCannotOpenAChat_beforeACompletedSession() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        assertThatThrownBy(() -> initiate(coachCookies))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(ex.getResponseBodyAsString()).contains("security.playerNotAgeVerified");
            });
    }

    @Test
    void fallback_unverifiedSelfRegisteredPlayer_cannotOpenAChat_beforeACompletedSession() {
        String playerCookies = loginAndGetCookies(SELF_EMAIL);

        assertThatThrownBy(() -> initiate(playerCookies))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(ex.getResponseBodyAsString()).contains("security.completedSessionRequired");
            });
    }

    @Test
    void fallback_aCompletedSessionOpensTheChat() {
        transactionTemplate.executeWithoutResult(s -> insertBooking("COMPLETED"));
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Map> resp = initiate(coachCookies);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKey("conversationId");
    }

    @Test
    void aVerifiedPlayerIsNotGated_evenWithoutACompletedSession() {
        transactionTemplate.executeWithoutResult(s -> jdbcTemplate.update(
            "INSERT INTO main.age_verifications (id, user_id, stripe_session_id, status, verified_tier) "
                + "VALUES (?, ?, ?, 'VERIFIED', 'ADULT')",
            9810000099L, SELF_USER_ID, "vs_gate_it_" + UUID.randomUUID()));
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        assertThat(initiate(coachCookies).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void status_whenTheFeatureIsOff_reportsNotEnabledForAPlayer() {
        String playerCookies = loginAndGetCookies(SELF_EMAIL);

        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/security/age-verification", HttpMethod.GET, null,
            authenticatedHeaders(playerCookies), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("applicable")).isEqualTo(true);
        assertThat(resp.getBody().get("enabled")).isEqualTo(false);
        assertThat(resp.getBody().get("state")).isEqualTo("NOT_STARTED");
    }

    @Test
    void startingASession_whenTheFeatureIsOff_is503_andCreatesNothing() {
        String playerCookies = loginAndGetCookies(SELF_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/security/age-verification/session", HttpMethod.POST, null,
            authenticatedHeaders(playerCookies), Map.class))
            .satisfies(e -> assertThat(e.getMessage()).contains("503"));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM main.age_verifications WHERE user_id = ?", Integer.class, SELF_USER_ID)).isZero();
    }

    @Test
    void theEndpoints_areClosedToACoach() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/security/age-verification", HttpMethod.GET, null,
            authenticatedHeaders(coachCookies), Map.class))
            .satisfies(e -> assertThat(e.getMessage()).contains("403"));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────────────

    private ResponseEntity<Map> initiate(String cookies) {
        return httpTestClient.makeHttpRequest(
            baseUrl() + "/api/messaging/conversations", HttpMethod.POST,
            Map.of("coachId", coachProfileId.toString(), "playerId", SELF_PROFILE_ID),
            authenticatedHeaders(cookies), Map.class);
    }

    private void insertBooking(String status) {
        jdbcTemplate.update(
            "INSERT INTO booking.bookings "
                + "(id, coach_id, parent_id, player_id, status, requested_start_time, requested_end_time, "
                + " version, created_at, updated_at, canonical_timezone) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?, 'Europe/Berlin')",
            UUID.randomUUID(), coachProfileId, SELF_USER_ID, SELF_PROFILE_ID, status,
            Timestamp.from(Instant.now().minusSeconds(7200)),
            Timestamp.from(Instant.now().minusSeconds(3600)),
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
    }

    private String loginAndGetCookies(String email) {
        ResponseEntity<Map> loginResponse = httpTestClient.makeHttpRequest(
            baseUrl() + LOGIN_ENDPOINT, HttpMethod.POST,
            Map.of("email", email, "password", TEST_PASSWORD), clientHeaders(), Map.class);
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
            "INSERT INTO main.\"user\" "
                + "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, "
                + "status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, "
                + "activated, locked, login, login_id_type, password_hash, otp_enabled, "
                + "skillars_role, verification_status) "
                + "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, "
                + "'ACTIVE', '1985-06-01', ?, 'Test', 'OTHER', 'en', ?, 'DE', ?, "
                + "true, false, ?, 'EMAIL', ?, false, "
                + "?, 'BASIC_VERIFIED')",
            id, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            email, role, "70" + (id % 100000000), email, passwordHash, role);
    }
}
