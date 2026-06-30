package com.softropic.skillars.platform.admin.api;

import com.softropic.skillars.config.TestConfig;
import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.gemini.GeminiClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.filestorage.service.FileStorageService;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;

import java.sql.Timestamp;
import java.time.Instant;
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
class ActiveBookingsErasureBlockIT {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String ERASURE_URL    = "/api/gdpr/erasure";
    private static final String CLIENT_ID      = "testClientId";
    private static final String TEST_PASSWORD  = "TestPass@123!";

    private static final long COACH_USER_ID    = 9220_000_001L;
    private static final long PARENT_USER_ID   = 9220_000_002L;
    private static final long PLAYER_ID        = 9220_000_003L;

    private static final String COACH_EMAIL    = "gdpr.block.coach.9220@skillars-test.com";
    private static final String PARENT_EMAIL   = "gdpr.block.parent.9220@skillars-test.com";

    @MockitoBean
    private GeminiClient geminiClient;

    @MockitoBean
    private FileStorageService fileStorageService;

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private PasswordEncoder passwordEncoder;

    @LocalServerPort private int randomServerPort;

    private UUID coachProfileId;
    private UUID activeBookingId;
    private UUID completedBookingId;

    @BeforeEach
    void setUp() {
        coachProfileId     = UUID.randomUUID();
        activeBookingId    = UUID.randomUUID();
        completedBookingId = UUID.randomUUID();
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);

        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) VALUES (9220, 'ROLE_COACH', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) VALUES (9221, 'ROLE_PARENT', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));

            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH");
            grantAuthority(COACH_USER_ID, "ROLE_COACH");
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles (id, user_id, display_name, bio, city, languages, canonical_timezone, status) VALUES (?, ?, 'Block Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
                coachProfileId, COACH_USER_ID);

            insertUser(PARENT_USER_ID, PARENT_EMAIL, passwordHash, "PARENT");
            grantAuthority(PARENT_USER_ID, "ROLE_PARENT");

            jdbcTemplate.update(
                "INSERT INTO booking.bookings (id, coach_id, parent_id, player_id, status, requested_start_time, requested_end_time, created_at, canonical_timezone) VALUES (?, ?, ?, ?, 'COMPLETED', ?, ?, ?, 'Europe/Berlin')",
                completedBookingId, coachProfileId, PARENT_USER_ID, PLAYER_ID,
                Timestamp.from(Instant.now().minus(2, ChronoUnit.DAYS)),
                Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS)),
                Timestamp.from(Instant.now().minus(3, ChronoUnit.DAYS)));

            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM admin.gdpr_requests WHERE user_id IN (?, ?)", COACH_USER_ID, PARENT_USER_ID);
            jdbcTemplate.update("DELETE FROM booking.bookings WHERE id IN (?, ?)", activeBookingId, completedBookingId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id = ?", coachProfileId);
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?)", COACH_USER_ID, PARENT_USER_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?)", COACH_USER_ID, PARENT_USER_ID);
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id IN (9220, 9221)");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void requestErasure_coachWithActiveBookings_returns409() {
        insertActiveBooking();
        String cookies = loginAndGetCookies(COACH_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + ERASURE_URL, HttpMethod.POST, null, authenticatedHeaders(cookies), Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void requestErasure_coachWithOnlyCompletedBookings_returns202() {
        // No active bookings — setUp only inserted a COMPLETED booking
        String cookies = loginAndGetCookies(COACH_EMAIL);
        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            baseUrl() + ERASURE_URL, HttpMethod.POST, null, authenticatedHeaders(cookies), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(resp.getBody()).containsKey("requestId");
    }

    @Test
    void requestErasure_parentUserNotBlockedByCoachBookings_returns202() {
        // A PARENT user requesting erasure is NOT subject to the coach-active-bookings check
        String cookies = loginAndGetCookies(PARENT_EMAIL);
        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            baseUrl() + ERASURE_URL, HttpMethod.POST, null, authenticatedHeaders(cookies), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(resp.getBody()).containsKey("requestId");
    }

    // ── helpers ──

    private void insertActiveBooking() {
        Instant futureTime = Instant.now().plus(2, ChronoUnit.DAYS);
        transactionTemplate.execute(ts -> {
            jdbcTemplate.update(
                "INSERT INTO booking.bookings (id, coach_id, parent_id, player_id, status, requested_start_time, requested_end_time, created_at, canonical_timezone) VALUES (?, ?, ?, ?, 'ACCEPTED', ?, ?, ?, 'Europe/Berlin')",
                activeBookingId, coachProfileId, PARENT_USER_ID, PLAYER_ID,
                Timestamp.from(futureTime), Timestamp.from(futureTime.plus(1, ChronoUnit.HOURS)),
                Timestamp.from(Instant.now()));
            return null;
        });
    }

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
            email, role, "922" + (id % 10000000), email, passwordHash, role);
    }

    private void grantAuthority(long userId, String roleName) {
        jdbcTemplate.update(
            "INSERT INTO main.user_authority (user_id, authority_id) VALUES (?, (SELECT id FROM main.authority WHERE name = ?)) ON CONFLICT DO NOTHING",
            userId, roleName);
    }
}
