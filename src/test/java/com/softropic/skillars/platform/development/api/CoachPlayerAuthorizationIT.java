package com.softropic.skillars.platform.development.api;

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

/**
 * Verifies coach-player booking-relationship authorization (Story deferred-5, AC1/AC2)
 * and the admin bypass on the single-booking lookup endpoint (AC3).
 */
@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestConfig.class)
@TestPropertySource(properties = {
    "spring.cloud.compatibility-verifier.enabled=false",
    "rate.limiting.enabled=false",
    "allowed.clients=testClientId"
})
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class CoachPlayerAuthorizationIT {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String CLIENT_ID      = "testClientId";
    private static final String TEST_PASSWORD  = "TestPass@123!";

    private static final long COACH_USER_ID       = 9_330_000_010L;
    private static final long PARENT_USER_ID      = 9_330_000_001L;
    private static final long OTHER_PARENT_USER_ID = 9_330_000_002L;
    private static final long ADMIN_USER_ID       = 9_330_000_099L;
    private static final long DUAL_ROLE_USER_ID   = 9_330_000_030L;
    private static final long PLAYER_ID           = 9_330_000_020L;
    private static final long DUAL_ROLE_PLAYER_ID = 9_330_000_021L;

    private static final String COACH_EMAIL        = "coach.relationship@skillars-test.com";
    private static final String PARENT_EMAIL       = "parent.relationship@skillars-test.com";
    private static final String OTHER_PARENT_EMAIL = "other.relationship@skillars-test.com";
    private static final String ADMIN_EMAIL        = "admin.relationship@skillars-test.com";
    private static final String DUAL_ROLE_EMAIL    = "dualrole.relationship@skillars-test.com";

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
            insertAuthority(9330, "ROLE_COACH");
            insertAuthority(9331, "ROLE_PARENT");
            insertAuthority(9332, "ROLE_ADMIN");

            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH");
            grantRole(COACH_USER_ID, "ROLE_COACH");
            coachProfileId = insertCoachProfile(COACH_USER_ID);

            insertUser(PARENT_USER_ID, PARENT_EMAIL, passwordHash, "PARENT");
            grantRole(PARENT_USER_ID, "ROLE_PARENT");

            insertUser(OTHER_PARENT_USER_ID, OTHER_PARENT_EMAIL, passwordHash, "PARENT");
            grantRole(OTHER_PARENT_USER_ID, "ROLE_PARENT");

            insertUser(ADMIN_USER_ID, ADMIN_EMAIL, passwordHash, "ADMIN");
            grantRole(ADMIN_USER_ID, "ROLE_ADMIN");

            insertUser(DUAL_ROLE_USER_ID, DUAL_ROLE_EMAIL, passwordHash, "COACH");
            grantRole(DUAL_ROLE_USER_ID, "ROLE_COACH");
            grantRole(DUAL_ROLE_USER_ID, "ROLE_PARENT");
            insertCoachProfile(DUAL_ROLE_USER_ID);

            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Relationship Player', ?, 'MIDFIELDER', 'AGE_10_12', ?, false, ?, 'system')",
                PLAYER_ID, Date.valueOf(LocalDate.now().minusYears(10)),
                PARENT_USER_ID, Timestamp.from(Instant.now())
            );
            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Dual Role Player', ?, 'MIDFIELDER', 'AGE_10_12', ?, false, ?, 'system')",
                DUAL_ROLE_PLAYER_ID, Date.valueOf(LocalDate.now().minusYears(10)),
                DUAL_ROLE_USER_ID, Timestamp.from(Instant.now())
            );
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM booking.bookings WHERE coach_id IN " +
                "(SELECT id FROM marketplace.coach_profiles WHERE user_id IN (?, ?))",
                COACH_USER_ID, DUAL_ROLE_USER_ID);
            jdbcTemplate.update("DELETE FROM development.player_slu_weekly_snapshot WHERE player_id IN (?, ?)",
                PLAYER_ID, DUAL_ROLE_PLAYER_ID);
            jdbcTemplate.update("DELETE FROM main.player_profiles WHERE id IN (?, ?)", PLAYER_ID, DUAL_ROLE_PLAYER_ID);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE user_id IN (?, ?)",
                COACH_USER_ID, DUAL_ROLE_USER_ID);
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?, ?, ?, ?)",
                COACH_USER_ID, PARENT_USER_ID, OTHER_PARENT_USER_ID, ADMIN_USER_ID, DUAL_ROLE_USER_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?, ?, ?, ?)",
                COACH_USER_ID, PARENT_USER_ID, OTHER_PARENT_USER_ID, ADMIN_USER_ID, DUAL_ROLE_USER_ID);
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id IN (9330, 9331, 9332)");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void coachAccessPlayerWithoutRelationship_returns403() {
        String cookies = loginAndGetCookies(COACH_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/development/players/" + PLAYER_ID + "/narrative",
            HttpMethod.GET, null, authenticatedHeaders(cookies), List.class
        )).isInstanceOf(HttpClientErrorException.class)
          .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void coachAccessPlayerWithRelationship_succeeds() {
        insertBooking(coachProfileId, PARENT_USER_ID, PLAYER_ID, "COMPLETED");
        String cookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/development/players/" + PLAYER_ID + "/exposure",
            HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void coachRoleParentOwnsPlayer_withNoCoachingRelationship_stillSucceeds() {
        String cookies = loginAndGetCookies(DUAL_ROLE_EMAIL);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/development/players/" + DUAL_ROLE_PLAYER_ID + "/exposure",
            HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getBooking_thirdParty_returns403() {
        UUID bookingId = insertBooking(coachProfileId, PARENT_USER_ID, PLAYER_ID, "REQUESTED");
        String cookies = loginAndGetCookies(OTHER_PARENT_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId,
            HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class
        )).isInstanceOf(HttpClientErrorException.class)
          .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void getBooking_admin_succeeds() {
        UUID bookingId = insertBooking(coachProfileId, PARENT_USER_ID, PLAYER_ID, "REQUESTED");
        String cookies = loginAndGetCookies(ADMIN_EMAIL);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId,
            HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private UUID insertBooking(UUID coachId, Long parentId, Long playerId, String status) {
        UUID bookingId = UUID.randomUUID();
        transactionTemplate.execute(txStatus -> {
            jdbcTemplate.update(
                "INSERT INTO booking.bookings " +
                "(id, parent_id, player_id, coach_id, requested_start_time, requested_end_time, status, " +
                " canonical_timezone, version, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, 'Europe/Berlin', 0, ?, ?)",
                bookingId, parentId, playerId, coachId,
                Timestamp.from(Instant.now().minusSeconds(7200)),
                Timestamp.from(Instant.now().minusSeconds(3600)),
                status, Timestamp.from(Instant.now()), Timestamp.from(Instant.now())
            );
            return null;
        });
        return bookingId;
    }

    private String loginAndGetCookies(String email) {
        ResponseEntity<Map> loginResponse = httpTestClient.makeHttpRequest(
            baseUrl() + LOGIN_ENDPOINT,
            HttpMethod.POST,
            Map.of("email", email, "password", TEST_PASSWORD),
            clientHeaders(),
            Map.class
        );
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

    private void insertAuthority(int id, String name) {
        jdbcTemplate.update(
            "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
            "VALUES (?, ?, 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
            id, name, Timestamp.from(Instant.now())
        );
    }

    private void insertUser(long id, String email, String passwordHash, String role) {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" " +
            "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, " +
            "status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, " +
            "activated, locked, login, login_id_type, password_hash, otp_enabled, " +
            "skillars_role, verification_status) " +
            "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, " +
            "'ACTIVE', '1990-01-01', ?, 'Test', 'OTHER', 'en', ?, 'DE', ?, " +
            "true, false, ?, 'EMAIL', ?, false, " +
            "?, 'BASIC_VERIFIED')",
            id,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            email, role,
            "69" + (id % 100000000L),
            email, passwordHash, role
        );
    }

    private void grantRole(long userId, String roleName) {
        jdbcTemplate.update(
            "INSERT INTO main.user_authority (user_id, authority_id) " +
            "VALUES (?, (SELECT id FROM main.authority WHERE name = ?)) ON CONFLICT DO NOTHING",
            userId, roleName
        );
    }

    private UUID insertCoachProfile(long userId) {
        UUID profileId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO marketplace.coach_profiles " +
            "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
            "VALUES (?, ?, 'Test Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
            profileId, userId
        );
        return profileId;
    }
}
