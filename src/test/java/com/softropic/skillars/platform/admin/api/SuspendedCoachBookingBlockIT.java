package com.softropic.skillars.platform.admin.api;

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
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestConfig.class)
@TestPropertySource(properties = {
    "spring.cloud.compatibility-verifier.enabled=false",
    "rate.limiting.enabled=false",
    "allowed.clients=testClientId"
})
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class SuspendedCoachBookingBlockIT {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String BOOKINGS_BASE  = "/api/bookings/requests";
    private static final String CLIENT_ID      = "testClientId";
    private static final String TEST_PASSWORD  = "TestPass@123!";
    private static final String WINDOW_TZ      = "Europe/Berlin";

    private static final long PARENT_ID     = 9050_000_001L;
    private static final long PLAYER_ID     = 9050_000_002L;
    private static final long COACH_USER_ID = 9050_000_010L;

    private static final String PARENT_EMAIL = "parent.bookblock9050@skillars-test.com";
    private static final String COACH_EMAIL  = "coach.bookblock9050@skillars-test.com";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private PasswordEncoder passwordEncoder;

    @LocalServerPort private int randomServerPort;

    private UUID coachProfileId;
    private Instant slotStart;
    private Instant slotEnd;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        coachProfileId = UUID.randomUUID();

        ZonedDateTime nextDaySlot = ZonedDateTime.now(ZoneId.of(WINDOW_TZ)).plusDays(1)
            .withHour(10).withMinute(0).withSecond(0).withNano(0);
        slotStart = nextDaySlot.toInstant();
        slotEnd = nextDaySlot.plusHours(1).toInstant();
        short windowDow = (short) nextDaySlot.getDayOfWeek().getValue();

        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) VALUES (9050, 'ROLE_PARENT', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) VALUES (9051, 'ROLE_COACH', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));

            insertUser(PARENT_ID, PARENT_EMAIL, passwordHash, "PARENT");
            grantAuthority(PARENT_ID, "ROLE_PARENT");

            jdbcTemplate.update(
                "INSERT INTO main.player_profiles (id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Block Player', ?, 'MIDFIELDER', 'ADULT', ?, true, ?, 'system')",
                PLAYER_ID, Date.valueOf(LocalDate.now().minusYears(16)), PARENT_ID, Timestamp.from(Instant.now()));

            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH");
            grantAuthority(COACH_USER_ID, "ROLE_COACH");

            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles (id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, 'Block Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], ?, 'SUSPENDED')",
                coachProfileId, COACH_USER_ID, WINDOW_TZ);

            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_pricing (coach_id, per_session_price, currency) VALUES (?, 50.00, 'EUR')",
                coachProfileId);

            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_availability_windows (id, coach_id, day_of_week, start_time, end_time, canonical_timezone) " +
                "VALUES (?, ?, ?, '08:00', '18:00', ?)",
                UUID.randomUUID(), coachProfileId, windowDow, WINDOW_TZ);

            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM marketplace.coach_availability_windows WHERE coach_id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_pricing WHERE coach_id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM main.player_profiles WHERE id = ?", PLAYER_ID);
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?)", PARENT_ID, COACH_USER_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?)", PARENT_ID, COACH_USER_ID);
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id IN (9050, 9051)");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void bookingRequestForSuspendedCoach_returns403WithCoachUnavailableCode() {
        String parentCookies = loginAndGetCookies(PARENT_EMAIL);

        HttpClientErrorException ex = (HttpClientErrorException) org.junit.jupiter.api.Assertions.assertThrows(
            HttpClientErrorException.class,
            () -> httpTestClient.makeHttpRequest(
                baseUrl() + BOOKINGS_BASE,
                HttpMethod.POST,
                Map.of(
                    "coachId", coachProfileId.toString(),
                    "playerId", PLAYER_ID,
                    "requestedStartTime", slotStart.toString(),
                    "requestedEndTime", slotEnd.toString(),
                    "canonicalTimezone", WINDOW_TZ
                ),
                authenticatedHeaders(parentCookies), Map.class));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ex.getResponseBodyAsString()).contains("booking.coachUnavailable");
    }

    @Test
    void bookingRequestForPendingReviewCoach_succeeds() {
        transactionTemplate.execute(status ->
            jdbcTemplate.update("UPDATE marketplace.coach_profiles SET status = 'PENDING_REVIEW' WHERE id = ?", coachProfileId));

        String parentCookies = loginAndGetCookies(PARENT_EMAIL);
        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            baseUrl() + BOOKINGS_BASE,
            HttpMethod.POST,
            Map.of(
                "coachId", coachProfileId.toString(),
                "playerId", PLAYER_ID,
                "requestedStartTime", slotStart.toString(),
                "requestedEndTime", slotEnd.toString(),
                "canonicalTimezone", WINDOW_TZ
            ),
            authenticatedHeaders(parentCookies), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().get("status")).isEqualTo("REQUESTED");

        jdbcTemplate.update("DELETE FROM booking.bookings WHERE coach_id = ?", coachProfileId);
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

    private String baseUrl() {
        return "http://localhost:" + randomServerPort;
    }

    private void insertUser(long id, String email, String passwordHash, String role) {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" (id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, activated, locked, login, login_id_type, password_hash, otp_enabled, skillars_role, verification_status) VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, 'ACTIVE', '1985-06-01', ?, 'Test', 'OTHER', 'en', ?, 'DE', ?, true, false, ?, 'EMAIL', ?, false, ?, 'BASIC_VERIFIED')",
            id, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            email, role, "905" + (id % 10000000), email, passwordHash, role);
    }

    private void grantAuthority(long userId, String roleName) {
        jdbcTemplate.update(
            "INSERT INTO main.user_authority (user_id, authority_id) VALUES (?, (SELECT id FROM main.authority WHERE name = ?)) ON CONFLICT DO NOTHING",
            userId, roleName);
    }
}
