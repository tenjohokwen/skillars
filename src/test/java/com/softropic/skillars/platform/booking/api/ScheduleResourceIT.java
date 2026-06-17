package com.softropic.skillars.platform.booking.api;

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
import java.time.temporal.ChronoUnit;
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
class ScheduleResourceIT {

    private static final String LOGIN_ENDPOINT   = "/api/auth/login";
    private static final String SCHEDULE_BASE    = "/api/bookings";
    private static final String CLIENT_ID        = "testClientId";
    private static final String TEST_PASSWORD    = "TestPass@123!";

    private static final long PARENT_ID      = 9600000001L;
    private static final long PLAYER_ID      = 9600000002L;
    private static final long COACH_USER_ID  = 9600000010L;

    private static final String PARENT_EMAIL = "parent.schedule@skillars-test.com";
    private static final String COACH_EMAIL  = "coach.schedule@skillars-test.com";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private PasswordEncoder passwordEncoder;

    @LocalServerPort private int randomServerPort;

    private UUID coachProfileId;

    private static final String WEEK_START = "2026-06-09";

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);

        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (9600, 'ROLE_PARENT', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now())
            );
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (9601, 'ROLE_COACH', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now())
            );

            insertUser(PARENT_ID, PARENT_EMAIL, passwordHash, "PARENT");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_PARENT')) ON CONFLICT DO NOTHING",
                PARENT_ID
            );

            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Schedule Player', ?, 'MIDFIELDER', 'ADULT', ?, true, ?, 'system')",
                PLAYER_ID, Date.valueOf(LocalDate.now().minusYears(16)),
                PARENT_ID, Timestamp.from(Instant.now())
            );

            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_COACH')) ON CONFLICT DO NOTHING",
                COACH_USER_ID
            );

            coachProfileId = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles " +
                "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, 'Schedule Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
                coachProfileId, COACH_USER_ID
            );
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_pricing (coach_id, per_session_price, currency) VALUES (?, 50.00, 'EUR')",
                coachProfileId
            );

            jdbcTemplate.update(
                "INSERT INTO booking.session_packs_purchased " +
                "(id, parent_id, player_id, coach_id, session_count, credits_remaining, status, purchased_at, expires_at) " +
                "VALUES (?, ?, ?, ?, 5, 5, 'ACTIVE', ?, ?)",
                UUID.randomUUID(), PARENT_ID, PLAYER_ID, coachProfileId,
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now().plus(180, ChronoUnit.DAYS))
            );

            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM booking.bookings WHERE parent_id = ?", PARENT_ID);
            jdbcTemplate.update("DELETE FROM booking.session_packs_purchased WHERE parent_id = ?", PARENT_ID);
            jdbcTemplate.update("DELETE FROM marketplace.coach_pricing WHERE coach_id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM main.player_profiles WHERE id = ?", PLAYER_ID);
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?)", PARENT_ID, COACH_USER_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?)", PARENT_ID, COACH_USER_ID);
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id IN (9600, 9601)");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void getCoachSchedule_authenticatedCoach_returns200WithCorrectShape() {
        String cookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + SCHEDULE_BASE + "/coaches/me/schedule?weekStart=" + WEEK_START,
            HttpMethod.GET,
            null,
            authenticatedHeaders(cookies),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsKey("weekStart");
        assertThat(body).containsKey("coachTimezone");
        assertThat(body).containsKey("bookings");
        assertThat(body).containsKey("projectedGrossRevenue");
        assertThat(body).containsKey("commissionDeduction");
        assertThat(body).containsKey("projectedNetRevenue");
    }

    @Test
    void getCoachSchedule_unauthenticated_returns401() {
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + SCHEDULE_BASE + "/coaches/me/schedule?weekStart=" + WEEK_START,
            HttpMethod.GET,
            null,
            clientHeaders(),
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void getCoachSchedule_parentRole_returns403() {
        String cookies = loginAndGetCookies(PARENT_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + SCHEDULE_BASE + "/coaches/me/schedule?weekStart=" + WEEK_START,
            HttpMethod.GET,
            null,
            authenticatedHeaders(cookies),
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void getParentSchedule_authenticatedParentWithOwnPlayer_returns200() {
        String cookies = loginAndGetCookies(PARENT_EMAIL);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + SCHEDULE_BASE + "/parents/me/schedule?playerId=" + PLAYER_ID,
            HttpMethod.GET,
            null,
            authenticatedHeaders(cookies),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsKey("playerId");
        assertThat(body).containsKey("sessions");
    }

    @Test
    void getParentSchedule_anotherParentsPlayer_returns404() {
        // Insert a second parent who does not own PLAYER_ID.
        // Ownership violation returns 404 (not 403) to prevent IDOR enumeration.
        transactionTemplate.execute(status -> {
            insertUser(9600000099L, "other.parent.sch@skillars-test.com", passwordEncoder.encode(TEST_PASSWORD), "PARENT");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (9600000099, (SELECT id FROM main.authority WHERE name = 'ROLE_PARENT')) ON CONFLICT DO NOTHING"
            );
            return null;
        });

        try {
            String cookies = loginAndGetCookies("other.parent.sch@skillars-test.com");

            assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
                baseUrl() + SCHEDULE_BASE + "/parents/me/schedule?playerId=" + PLAYER_ID,
                HttpMethod.GET,
                null,
                authenticatedHeaders(cookies),
                Map.class
            ))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        } finally {
            transactionTemplate.execute(status -> {
                jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
                jdbcTemplate.execute("DELETE FROM main.login_attempts");
                jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id = 9600000099");
                jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id = 9600000099");
                return null;
            });
        }
    }

    // ---- helpers ----

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
            "68" + (id % 100000000),
            email, passwordHash, role
        );
    }
}
