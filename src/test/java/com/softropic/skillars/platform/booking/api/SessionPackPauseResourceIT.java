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
class SessionPackPauseResourceIT {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String PACKS_BASE     = "/api/bookings/players";
    private static final String CLIENT_ID      = "testClientId";
    private static final String TEST_PASSWORD  = "ParentPass@123!";

    private static final long PARENT_ID   = 9500000001L;
    private static final long PLAYER_ID   = 9500000002L;
    private static final long PARENT2_ID  = 9500000003L;
    private static final long COACH_USER_ID = 9500000010L;

    private static final String PARENT_EMAIL  = "parent.pause@skillars-test.com";
    private static final String PARENT2_EMAIL = "parent2.pause@skillars-test.com";
    private static final String COACH_EMAIL   = "coach.pause@skillars-test.com";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private PasswordEncoder passwordEncoder;

    @LocalServerPort private int randomServerPort;

    private UUID coachProfileId;
    private UUID packId;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (9500, 'ROLE_PARENT', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now())
            );

            insertParentUser(PARENT_ID, PARENT_EMAIL, passwordHash);
            insertParentUser(PARENT2_ID, PARENT2_EMAIL, passwordHash);

            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_PARENT')) ON CONFLICT DO NOTHING",
                PARENT_ID
            );
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_PARENT')) ON CONFLICT DO NOTHING",
                PARENT2_ID
            );

            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Pause Player', ?, 'MIDFIELDER', 'ADULT', ?, true, ?, 'system')",
                PLAYER_ID, Date.valueOf(LocalDate.now().minusYears(16)),
                PARENT_ID, Timestamp.from(Instant.now())
            );

            jdbcTemplate.update(
                "INSERT INTO main.\"user\" " +
                "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, " +
                "status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, " +
                "activated, locked, login, login_id_type, password_hash, otp_enabled, " +
                "skillars_role, verification_status) " +
                "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, " +
                "'ACTIVE', '1985-01-01', ?, 'Pause', 'OTHER', 'en', 'Coach', 'DE', '6800000050', " +
                "true, false, ?, 'EMAIL', ?, false, " +
                "'COACH', 'BASIC_VERIFIED')",
                COACH_USER_ID,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
                COACH_EMAIL, COACH_EMAIL, passwordHash
            );

            coachProfileId = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles " +
                "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, 'Pause Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
                coachProfileId, COACH_USER_ID
            );

            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_pricing (coach_id, per_session_price, currency) VALUES (?, 25.00, 'EUR')",
                coachProfileId
            );

            // Insert a purchased session pack with credits and expiry
            packId = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO booking.session_packs_purchased " +
                "(id, parent_id, player_id, coach_id, session_count, credits_remaining, purchased_at, status, expires_at) " +
                "VALUES (?, ?, ?, ?, 5, 5, ?, 'ACTIVE', ?)",
                packId, PARENT_ID, PLAYER_ID, coachProfileId,
                Timestamp.from(Instant.now().minus(10, ChronoUnit.DAYS)),
                Timestamp.from(Instant.now().plus(170, ChronoUnit.DAYS))
            );

            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM booking.bookings WHERE player_id = ?", PLAYER_ID);
            jdbcTemplate.update("DELETE FROM booking.session_packs_purchased WHERE parent_id IN (?, ?)", PARENT_ID, PARENT2_ID);
            jdbcTemplate.update("DELETE FROM marketplace.coach_pricing WHERE coach_id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id = ?", COACH_USER_ID);
            jdbcTemplate.update("DELETE FROM main.player_profiles WHERE id = ?", PLAYER_ID);
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?)", PARENT_ID, PARENT2_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?)", PARENT_ID, PARENT2_ID);
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id = 9500");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void pausePack_noConflicts_returns200WithPauseApplied() {
        String cookies = loginAndGetCookies(PARENT_EMAIL);
        Instant pauseStart = Instant.now().plus(30, ChronoUnit.DAYS);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            pauseUrl(), HttpMethod.POST,
            Map.of("pauseStartDate", pauseStart.toString(), "pauseDurationDays", 14),
            authenticatedHeaders(cookies), Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("pauseApplied")).isEqualTo(true);
        assertThat(response.getBody().get("newExpiresAt")).isNotNull();

        // Verify paused_until is set in DB
        Timestamp pausedUntil = jdbcTemplate.queryForObject(
            "SELECT paused_until FROM booking.session_packs_purchased WHERE id = ?",
            Timestamp.class, packId);
        assertThat(pausedUntil).isNotNull();

        // Verify expires_at is extended
        Timestamp expiresAt = jdbcTemplate.queryForObject(
            "SELECT expires_at FROM booking.session_packs_purchased WHERE id = ?",
            Timestamp.class, packId);
        assertThat(expiresAt.toInstant()).isAfter(Instant.now().plus(180, ChronoUnit.DAYS));
    }

    @Test
    void pausePack_withConflictingBookings_returnsConflictList() {
        // Insert a booking in the pause window
        UUID bookingId = UUID.randomUUID();
        Instant sessionStart = Instant.now().plus(5, ChronoUnit.DAYS);
        Instant pauseStart = Instant.now().plus(1, ChronoUnit.DAYS);

        transactionTemplate.execute(s -> {
            jdbcTemplate.update(
                "INSERT INTO booking.bookings " +
                "(id, parent_id, player_id, coach_id, requested_start_time, requested_end_time, " +
                "status, canonical_timezone, version, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, 'CONFIRMED', 'UTC', 0, ?, ?)",
                bookingId, PARENT_ID, PLAYER_ID, coachProfileId,
                Timestamp.from(sessionStart), Timestamp.from(sessionStart.plus(1, ChronoUnit.HOURS)),
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now())
            );
            return null;
        });

        String cookies = loginAndGetCookies(PARENT_EMAIL);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            pauseUrl(), HttpMethod.POST,
            Map.of("pauseStartDate", pauseStart.toString(), "pauseDurationDays", 14),
            authenticatedHeaders(cookies), Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("pauseApplied")).isEqualTo(false);
        assertThat(((List<?>) response.getBody().get("conflictingBookings"))).isNotEmpty();

        // Verify pack NOT modified in DB
        Timestamp pausedUntil = jdbcTemplate.queryForObject(
            "SELECT paused_until FROM booking.session_packs_purchased WHERE id = ?",
            Timestamp.class, packId);
        assertThat(pausedUntil).isNull();
    }

    @Test
    void pausePack_withConfirmedCancellations_cancelsAndAppliesPause() {
        UUID bookingId = UUID.randomUUID();
        Instant sessionStart = Instant.now().plus(5, ChronoUnit.DAYS);
        Instant pauseStart = Instant.now().plus(1, ChronoUnit.DAYS);

        transactionTemplate.execute(s -> {
            jdbcTemplate.update(
                "INSERT INTO booking.bookings " +
                "(id, parent_id, player_id, coach_id, requested_start_time, requested_end_time, " +
                "status, canonical_timezone, version, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, 'CONFIRMED', 'UTC', 0, ?, ?)",
                bookingId, PARENT_ID, PLAYER_ID, coachProfileId,
                Timestamp.from(sessionStart), Timestamp.from(sessionStart.plus(1, ChronoUnit.HOURS)),
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now())
            );
            return null;
        });

        String cookies = loginAndGetCookies(PARENT_EMAIL);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            pauseUrl(), HttpMethod.POST,
            Map.of(
                "pauseStartDate", pauseStart.toString(),
                "pauseDurationDays", 14,
                "confirmedCancellationIds", List.of(bookingId.toString())
            ),
            authenticatedHeaders(cookies), Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("pauseApplied")).isEqualTo(true);

        // Booking should be CANCELLED
        String bookingStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM booking.bookings WHERE id = ?", String.class, bookingId);
        assertThat(bookingStatus).isEqualTo("CANCELLED");

        // Pack should be paused
        Timestamp pausedUntil = jdbcTemplate.queryForObject(
            "SELECT paused_until FROM booking.session_packs_purchased WHERE id = ?",
            Timestamp.class, packId);
        assertThat(pausedUntil).isNotNull();
    }

    @Test
    void pausePack_alreadyPaused_returns400() {
        // Set paused_until on the pack
        transactionTemplate.execute(s -> {
            jdbcTemplate.update(
                "UPDATE booking.session_packs_purchased SET paused_until = ? WHERE id = ?",
                Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS)), packId);
            return null;
        });

        String cookies = loginAndGetCookies(PARENT_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            pauseUrl(), HttpMethod.POST,
            Map.of("pauseStartDate", Instant.now().toString(), "pauseDurationDays", 14),
            authenticatedHeaders(cookies), Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void pausePack_unauthenticated_returns401() {
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            pauseUrl(), HttpMethod.POST,
            Map.of("pauseStartDate", Instant.now().toString(), "pauseDurationDays", 14),
            clientHeaders(), Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void pausePack_wrongParent_returns403() {
        String cookies = loginAndGetCookies(PARENT2_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            pauseUrl(), HttpMethod.POST,
            Map.of("pauseStartDate", Instant.now().toString(), "pauseDurationDays", 14),
            authenticatedHeaders(cookies), Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void pausePack_invalidDuration_zeroDays_returns400() {
        String cookies = loginAndGetCookies(PARENT_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            pauseUrl(), HttpMethod.POST,
            Map.of("pauseStartDate", Instant.now().toString(), "pauseDurationDays", 0),
            authenticatedHeaders(cookies), Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void pausePack_invalidDuration_91Days_returns400() {
        String cookies = loginAndGetCookies(PARENT_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            pauseUrl(), HttpMethod.POST,
            Map.of("pauseStartDate", Instant.now().toString(), "pauseDurationDays", 91),
            authenticatedHeaders(cookies), Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ----- helpers -----

    private String pauseUrl() {
        return baseUrl() + PACKS_BASE + "/" + PLAYER_ID + "/packs/" + packId + "/pause";
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

    private void insertParentUser(long id, String email, String passwordHash) {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" " +
            "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, " +
            "status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, " +
            "activated, locked, login, login_id_type, password_hash, otp_enabled, " +
            "skillars_role, verification_status) " +
            "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, " +
            "'ACTIVE', '1985-06-01', ?, 'Test', 'OTHER', 'en', 'Parent', 'DE', ?, " +
            "true, false, ?, 'EMAIL', ?, false, " +
            "'PARENT', 'BASIC_VERIFIED')",
            id,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            email,
            "690" + (id % 10000000),
            email, passwordHash
        );
    }
}
