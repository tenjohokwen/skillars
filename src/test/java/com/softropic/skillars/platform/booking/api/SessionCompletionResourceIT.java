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
class SessionCompletionResourceIT {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String CLIENT_ID      = "testClientId";
    private static final String TEST_PASSWORD  = "TestPass@123!";

    private static final long PARENT_ID      = 9600000001L;
    private static final long PLAYER_ID      = 9600000002L;
    private static final long COACH_USER_ID  = 9600000010L;
    private static final long COACH_2_USER_ID = 9600000011L;

    private static final String PARENT_EMAIL  = "parent.completion@skillars-test.com";
    private static final String COACH_EMAIL   = "coach.completion@skillars-test.com";
    private static final String COACH_2_EMAIL = "coach2.completion@skillars-test.com";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private PasswordEncoder passwordEncoder;

    @LocalServerPort private int randomServerPort;

    private UUID coachProfileId;
    private UUID coachProfile2Id;
    private UUID bookingId;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        coachProfileId = UUID.randomUUID();
        coachProfile2Id = UUID.randomUUID();
        bookingId = UUID.randomUUID();

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
                "VALUES (?, 'Completion Player', '2008-01-01', 'MIDFIELDER', 'ADULT', ?, true, ?, 'system')",
                PLAYER_ID, PARENT_ID, Timestamp.from(Instant.now())
            );

            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_COACH')) ON CONFLICT DO NOTHING",
                COACH_USER_ID
            );
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles " +
                "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, 'Completion Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
                coachProfileId, COACH_USER_ID
            );
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_pricing (coach_id, per_session_price, currency) VALUES (?, 50.00, 'EUR')",
                coachProfileId
            );

            insertUser(COACH_2_USER_ID, COACH_2_EMAIL, passwordHash, "COACH");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_COACH')) ON CONFLICT DO NOTHING",
                COACH_2_USER_ID
            );
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles " +
                "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, 'Other Coach', 'Bio', 'Munich', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
                coachProfile2Id, COACH_2_USER_ID
            );
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_pricing (coach_id, per_session_price, currency) VALUES (?, 50.00, 'EUR')",
                coachProfile2Id
            );

            jdbcTemplate.update(
                "INSERT INTO booking.session_packs_purchased " +
                "(id, parent_id, player_id, coach_id, session_count, credits_remaining, status, purchased_at, expires_at) " +
                "VALUES (?, ?, ?, ?, 5, 5, 'ACTIVE', ?, ?)",
                UUID.randomUUID(), PARENT_ID, PLAYER_ID, coachProfileId,
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now().plus(180, ChronoUnit.DAYS))
            );

            insertUpcomingBooking(bookingId);
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM booking.session_completion_data WHERE booking_id = ?", bookingId);
            jdbcTemplate.update("DELETE FROM booking.bookings WHERE parent_id = ?", PARENT_ID);
            jdbcTemplate.update("DELETE FROM booking.session_packs_purchased WHERE parent_id = ?", PARENT_ID);
            jdbcTemplate.update("DELETE FROM marketplace.coach_pricing WHERE coach_id IN (?, ?)", coachProfileId, coachProfile2Id);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id IN (?, ?)", coachProfileId, coachProfile2Id);
            jdbcTemplate.update("DELETE FROM main.player_profiles WHERE id = ?", PLAYER_ID);
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?, ?)", PARENT_ID, COACH_USER_ID, COACH_2_USER_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?, ?)", PARENT_ID, COACH_USER_ID, COACH_2_USER_ID);
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id IN (9600, 9601)");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    // ---- Tests ----

    @Test
    void startSession_upcomingBooking_returns204AndStatusIsInProgress() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/start",
            HttpMethod.POST, null, authenticatedHeaders(coachCookies), Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM booking.bookings WHERE id = ?", String.class, bookingId);
        assertThat(status).isEqualTo("IN_PROGRESS");
    }

    @Test
    void startSession_unauthenticated_returns401() {
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/start",
            HttpMethod.POST, null, clientHeaders(), Void.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void startSession_parentRole_returns403() {
        String parentCookies = loginAndGetCookies(PARENT_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/start",
            HttpMethod.POST, null, authenticatedHeaders(parentCookies), Void.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void endSession_fromInProgress_returns204AndStatusIsCompletedPendingConfirmation() {
        setBookingStatus("IN_PROGRESS");
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/end",
            HttpMethod.POST, null, authenticatedHeaders(coachCookies), Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM booking.bookings WHERE id = ?", String.class, bookingId);
        assertThat(status).isEqualTo("COMPLETED_PENDING_CONFIRMATION");
    }

    @Test
    void submitWrapUp_liveMode_returns204AndStatusIsCompleted() {
        setBookingStatus("COMPLETED_PENDING_CONFIRMATION");
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/complete",
            HttpMethod.POST,
            Map.of(
                "playerAttended", true,
                "effortRating", 4,
                "focusRating", 3,
                "techniqueRating", 5,
                "mode", "LIVE"
            ),
            authenticatedHeaders(coachCookies), Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM booking.bookings WHERE id = ?", String.class, bookingId);
        assertThat(status).isEqualTo("COMPLETED");
    }

    @Test
    void submitWrapUp_quickMode_returns204AndStatusRemainsCompletedPendingConfirmation() {
        setBookingStatus("COMPLETED_PENDING_CONFIRMATION");
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/complete",
            HttpMethod.POST,
            Map.of(
                "playerAttended", true,
                "effortRating", 3,
                "focusRating", 4,
                "techniqueRating", 4,
                "mode", "QUICK"
            ),
            authenticatedHeaders(coachCookies), Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM booking.bookings WHERE id = ?", String.class, bookingId);
        assertThat(status).isEqualTo("COMPLETED_PENDING_CONFIRMATION");
    }

    @Test
    void confirmCompletion_byOwningParent_returns204AndStatusIsCompleted() {
        setBookingStatus("COMPLETED_PENDING_CONFIRMATION");
        insertCompletionData("QUICK");
        String parentCookies = loginAndGetCookies(PARENT_EMAIL);

        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/confirm-completion",
            HttpMethod.PUT, null, authenticatedHeaders(parentCookies), Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM booking.bookings WHERE id = ?", String.class, bookingId);
        assertThat(status).isEqualTo("COMPLETED");
    }

    @Test
    void pauseSession_fromInProgress_returns204AndStatusIsPaused() {
        setBookingStatus("IN_PROGRESS");
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/pause",
            HttpMethod.POST, null, authenticatedHeaders(coachCookies), Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM booking.bookings WHERE id = ?", String.class, bookingId);
        assertThat(status).isEqualTo("PAUSED");
    }

    @Test
    void resumeSession_fromPaused_returns204AndStatusIsInProgress() {
        setBookingStatus("PAUSED");
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/resume",
            HttpMethod.POST, null, authenticatedHeaders(coachCookies), Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM booking.bookings WHERE id = ?", String.class, bookingId);
        assertThat(status).isEqualTo("IN_PROGRESS");
    }

    @Test
    void pauseSession_unauthenticated_returns401() {
        setBookingStatus("IN_PROGRESS");

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/pause",
            HttpMethod.POST, null, clientHeaders(), Void.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void pauseSession_parentRole_returns403() {
        setBookingStatus("IN_PROGRESS");
        String parentCookies = loginAndGetCookies(PARENT_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/pause",
            HttpMethod.POST, null, authenticatedHeaders(parentCookies), Void.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void pauseSession_alreadyPaused_returnsForbidden() {
        setBookingStatus("PAUSED");
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/pause",
            HttpMethod.POST, null, authenticatedHeaders(coachCookies), Void.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void endSession_fromPaused_returns204AndStatusIsCompletedPendingConfirmation() {
        setBookingStatus("PAUSED");
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/end",
            HttpMethod.POST, null, authenticatedHeaders(coachCookies), Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM booking.bookings WHERE id = ?", String.class, bookingId);
        assertThat(status).isEqualTo("COMPLETED_PENDING_CONFIRMATION");
    }

    @Test
    void pauseSession_wrongCoach_returnsForbidden() {
        setBookingStatus("IN_PROGRESS");
        String coach2Cookies = loginAndGetCookies(COACH_2_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/pause",
            HttpMethod.POST, null, authenticatedHeaders(coach2Cookies), Void.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void resumeSession_wrongCoach_returnsForbidden() {
        setBookingStatus("PAUSED");
        String coach2Cookies = loginAndGetCookies(COACH_2_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/resume",
            HttpMethod.POST, null, authenticatedHeaders(coach2Cookies), Void.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void getDrillSuggestions_returns200AndEmptyArray() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<List> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/session/" + bookingId + "/drills/suggestions",
            HttpMethod.GET, null, authenticatedHeaders(coachCookies), List.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    // ---- helpers ----

    private void insertUpcomingBooking(UUID id) {
        Instant futureStart = Instant.now().plusSeconds(86400);
        jdbcTemplate.update(
            "INSERT INTO booking.bookings " +
            "(id, parent_id, player_id, coach_id, requested_start_time, requested_end_time, " +
            "status, canonical_timezone, version, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, 'UPCOMING', 'Europe/Berlin', 0, ?, ?)",
            id, PARENT_ID, PLAYER_ID, coachProfileId,
            Timestamp.from(futureStart), Timestamp.from(futureStart.plusSeconds(3600)),
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now())
        );
    }

    private void setBookingStatus(String status) {
        transactionTemplate.execute(s -> {
            jdbcTemplate.update("UPDATE booking.bookings SET status = ? WHERE id = ?", status, bookingId);
            return null;
        });
    }

    private void insertCompletionData(String mode) {
        transactionTemplate.execute(s -> {
            jdbcTemplate.update(
                "INSERT INTO booking.session_completion_data " +
                "(id, booking_id, coach_id, player_id, player_attended, completion_mode, created_at) " +
                "VALUES (?, ?, ?, ?, true, ?, ?)",
                UUID.randomUUID(), bookingId, coachProfileId, PLAYER_ID, mode, Timestamp.from(Instant.now())
            );
            return null;
        });
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
            "69" + (id % 100000000),
            email, passwordHash, role
        );
    }
}
