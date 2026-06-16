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
class RescheduleResourceIT {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String CLIENT_ID      = "testClientId";
    private static final String TEST_PASSWORD  = "TestPass@123!";

    private static final long PARENT_ID       = 9700000001L;
    private static final long PLAYER_ID       = 9700000002L;
    private static final long COACH_USER_ID   = 9700000010L;
    private static final long COACH_2_USER_ID = 9700000011L;

    private static final String PARENT_EMAIL  = "parent.reschedule@skillars-test.com";
    private static final String COACH_EMAIL   = "coach.reschedule@skillars-test.com";
    private static final String COACH_2_EMAIL = "coach2.reschedule@skillars-test.com";

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
        coachProfileId  = UUID.randomUUID();
        coachProfile2Id = UUID.randomUUID();
        bookingId       = UUID.randomUUID();

        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (9700, 'ROLE_PARENT', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now())
            );
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (9701, 'ROLE_COACH', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
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
                "VALUES (?, 'Reschedule Player', '2010-01-01', 'MIDFIELDER', 'ADULT', ?, true, ?, 'system')",
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
                "VALUES (?, ?, 'Reschedule Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
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
                "(id, parent_id, player_id, coach_id, session_count, credits_remaining, status, purchased_at) " +
                "VALUES (?, ?, ?, ?, 5, 5, 'ACTIVE', ?)",
                UUID.randomUUID(), PARENT_ID, PLAYER_ID, coachProfileId, Timestamp.from(Instant.now())
            );

            insertConfirmedBooking(bookingId);
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM booking.booking_reschedule_requests WHERE booking_id = ?", bookingId);
            jdbcTemplate.update("DELETE FROM booking.bookings WHERE parent_id = ?", PARENT_ID);
            jdbcTemplate.update("DELETE FROM booking.session_packs_purchased WHERE parent_id = ?", PARENT_ID);
            jdbcTemplate.update("DELETE FROM marketplace.coach_pricing WHERE coach_id IN (?, ?)", coachProfileId, coachProfile2Id);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id IN (?, ?)", coachProfileId, coachProfile2Id);
            jdbcTemplate.update("DELETE FROM main.player_profiles WHERE id = ?", PLAYER_ID);
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?, ?)", PARENT_ID, COACH_USER_ID, COACH_2_USER_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?, ?)", PARENT_ID, COACH_USER_ID, COACH_2_USER_ID);
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id IN (9700, 9701)");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    // ---- Tests ----

    @Test
    void requestReschedule_asParentWithConfirmedBooking_returns204AndCreatesRecord() {
        String parentCookies = loginAndGetCookies(PARENT_EMAIL);
        Instant proposedStart = Instant.now().plus(5, ChronoUnit.DAYS);
        Instant proposedEnd   = proposedStart.plus(1, ChronoUnit.HOURS);

        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/reschedule",
            HttpMethod.POST,
            Map.of("proposedStartTime", proposedStart.toString(), "proposedEndTime", proposedEnd.toString()),
            authenticatedHeaders(parentCookies), Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM booking.booking_reschedule_requests WHERE booking_id = ? AND status = 'PENDING'",
            Integer.class, bookingId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void requestReschedule_unauthenticated_returns401() {
        Instant proposedStart = Instant.now().plus(5, ChronoUnit.DAYS);
        Instant proposedEnd   = proposedStart.plus(1, ChronoUnit.HOURS);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/reschedule",
            HttpMethod.POST,
            Map.of("proposedStartTime", proposedStart.toString(), "proposedEndTime", proposedEnd.toString()),
            clientHeaders(), Void.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void requestReschedule_asCoach_returns403() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);
        Instant proposedStart = Instant.now().plus(5, ChronoUnit.DAYS);
        Instant proposedEnd   = proposedStart.plus(1, ChronoUnit.HOURS);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/reschedule",
            HttpMethod.POST,
            Map.of("proposedStartTime", proposedStart.toString(), "proposedEndTime", proposedEnd.toString()),
            authenticatedHeaders(coachCookies), Void.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void requestReschedule_wrongParent_returns403() {
        // Create a second parent with their own booking — then try to reschedule bookingId as them
        long otherParentId = 9700000003L;
        String otherParentEmail = "other.parent.reschedule@skillars-test.com";
        transactionTemplate.execute(s -> {
            insertUser(otherParentId, otherParentEmail, passwordEncoder.encode(TEST_PASSWORD), "PARENT");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_PARENT')) ON CONFLICT DO NOTHING",
                otherParentId
            );
            return null;
        });
        try {
            String otherCookies = loginAndGetCookies(otherParentEmail);
            Instant proposedStart = Instant.now().plus(5, ChronoUnit.DAYS);

            assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
                baseUrl() + "/api/bookings/" + bookingId + "/reschedule",
                HttpMethod.POST,
                Map.of("proposedStartTime", proposedStart.toString(),
                       "proposedEndTime", proposedStart.plus(1, ChronoUnit.HOURS).toString()),
                authenticatedHeaders(otherCookies), Void.class
            ))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        } finally {
            transactionTemplate.execute(s -> {
                jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id = ?", otherParentId);
                jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id = ?", otherParentId);
                return null;
            });
        }
    }

    @Test
    void acceptReschedule_asOwningCoach_returns204AndUpdatesBookingAndStatus() {
        UUID rescheduleId = insertPendingReschedule();
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/reschedule/" + rescheduleId + "/accept",
            HttpMethod.PUT, null, authenticatedHeaders(coachCookies), Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        String rescheduleStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM booking.booking_reschedule_requests WHERE id = ?",
            String.class, rescheduleId);
        assertThat(rescheduleStatus).isEqualTo("ACCEPTED");

        Timestamp proposedStart = jdbcTemplate.queryForObject(
            "SELECT proposed_start_time FROM booking.booking_reschedule_requests WHERE id = ?",
            Timestamp.class, rescheduleId);
        Timestamp newStart = jdbcTemplate.queryForObject(
            "SELECT requested_start_time FROM booking.bookings WHERE id = ?",
            Timestamp.class, bookingId);
        assertThat(newStart).isEqualTo(proposedStart);
    }

    @Test
    void declineReschedule_asOwningCoach_returns204AndSetsDeclined() {
        UUID rescheduleId = insertPendingReschedule();
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/reschedule/" + rescheduleId + "/decline",
            HttpMethod.PUT, null, authenticatedHeaders(coachCookies), Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        String rescheduleStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM booking.booking_reschedule_requests WHERE id = ?",
            String.class, rescheduleId);
        assertThat(rescheduleStatus).isEqualTo("DECLINED");
    }

    @Test
    void acceptReschedule_wrongCoach_returns403() {
        UUID rescheduleId = insertPendingReschedule();
        String coach2Cookies = loginAndGetCookies(COACH_2_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/reschedule/" + rescheduleId + "/accept",
            HttpMethod.PUT, null, authenticatedHeaders(coach2Cookies), Void.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void duplicateNextWeek_asOwningCoachWithCompletedBooking_returns204() {
        setBookingStatus("COMPLETED");
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/duplicate-next-week",
            HttpMethod.POST, null, authenticatedHeaders(coachCookies), Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        Integer newBookingCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM booking.bookings WHERE parent_id = ? AND id != ?",
            Integer.class, PARENT_ID, bookingId);
        assertThat(newBookingCount).isEqualTo(1);
    }

    @Test
    void duplicateNextWeek_asParent_returns403() {
        setBookingStatus("COMPLETED");
        String parentCookies = loginAndGetCookies(PARENT_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/duplicate-next-week",
            HttpMethod.POST, null, authenticatedHeaders(parentCookies), Void.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ---- Helpers ----

    private void insertConfirmedBooking(UUID id) {
        Instant futureStart = Instant.now().plus(2, ChronoUnit.DAYS);
        jdbcTemplate.update(
            "INSERT INTO booking.bookings " +
            "(id, parent_id, player_id, coach_id, requested_start_time, requested_end_time, " +
            "status, canonical_timezone, version, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, 'CONFIRMED', 'Europe/Berlin', 0, ?, ?)",
            id, PARENT_ID, PLAYER_ID, coachProfileId,
            Timestamp.from(futureStart), Timestamp.from(futureStart.plus(1, ChronoUnit.HOURS)),
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now())
        );
    }

    private UUID insertPendingReschedule() {
        UUID id = UUID.randomUUID();
        Instant proposed = Instant.now().plus(7, ChronoUnit.DAYS);
        transactionTemplate.execute(s -> {
            jdbcTemplate.update(
                "INSERT INTO booking.booking_reschedule_requests " +
                "(id, booking_id, proposed_by, proposed_start_time, proposed_end_time, status, created_at) " +
                "VALUES (?, ?, 'PARENT', ?, ?, 'PENDING', ?)",
                id, bookingId, Timestamp.from(proposed),
                Timestamp.from(proposed.plus(1, ChronoUnit.HOURS)),
                Timestamp.from(Instant.now())
            );
            return null;
        });
        return id;
    }

    private void setBookingStatus(String status) {
        transactionTemplate.execute(s -> {
            jdbcTemplate.update("UPDATE booking.bookings SET status = ?, requested_start_time = ? WHERE id = ?",
                status, Timestamp.from(Instant.now().minus(2, ChronoUnit.DAYS)), bookingId);
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
            "70" + (id % 100000000),
            email, passwordHash, role
        );
    }
}
