package com.softropic.skillars.platform.booking.api;

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
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UAT.5 AC1: a self-registered adult PLAYER (parent_id NULL, user_id set — the chk_pp_owner XOR
 * from V84) can create and list their own bookings, single + batch, without a parent account.
 * Asserts the opaque-id design end to end: the persisted row's parent_id equals the player's own
 * userId, not a real parent.
 */
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class PlayerBookingRequestIT extends AbstractIntegrationTest {

    private static final String LOGIN_ENDPOINT   = "/api/auth/login";
    private static final String BOOKINGS_BASE    = "/api/bookings/requests";
    private static final String BATCH_BASE       = "/api/bookings/batches";
    private static final String CLIENT_ID        = "testClientId";
    private static final String TEST_PASSWORD    = "TestPass@123!";

    private static final long SELF_PLAYER_USER_ID = 9600000001L;
    private static final long COACH_USER_ID       = 9600000010L;

    private static final long SELF_PLAYER_ID = 9600000002L;

    private static final String SELF_PLAYER_EMAIL = "self.player.booking@skillars-test.com";
    private static final String COACH_EMAIL       = "coach.player-booking@skillars-test.com";

    private static final String WINDOW_TZ = "Europe/Berlin";
    private static final String COACH_PROFILE_TZ = WINDOW_TZ;

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

        ZonedDateTime nextDaySlot = ZonedDateTime.now(ZoneId.of(WINDOW_TZ)).plusDays(1)
            .withHour(10).withMinute(0).withSecond(0).withNano(0);
        slotStart = nextDaySlot.toInstant();
        slotEnd = nextDaySlot.plusHours(1).toInstant();
        short windowDow = (short) nextDaySlot.getDayOfWeek().getValue();

        transactionTemplate.execute(status -> {
            // ROLE_PLAYER is seeded by V84 at id 102 — no ON CONFLICT insert needed here, unlike
            // uat-1's V92 lesson for ROLE_ADMIN, which genuinely was never seeded.
            insertUser(SELF_PLAYER_USER_ID, SELF_PLAYER_EMAIL, passwordHash, "PLAYER");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_PLAYER')) ON CONFLICT DO NOTHING",
                SELF_PLAYER_USER_ID
            );

            // Self-registered player: parent_id NULL, user_id set (chk_pp_owner's "else" branch)
            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, user_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Self Booking Player', ?, 'MIDFIELDER', 'ADULT', ?, true, ?, 'system')",
                SELF_PLAYER_ID, Date.valueOf(LocalDate.now().minusYears(20)),
                SELF_PLAYER_USER_ID, Timestamp.from(Instant.now())
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
                "VALUES (?, ?, 'Book Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], ?, 'ACTIVE')",
                coachProfileId, COACH_USER_ID, COACH_PROFILE_TZ
            );
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_pricing (coach_id, per_session_price, currency) VALUES (?, 50.00, 'EUR')",
                coachProfileId
            );

            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_availability_windows " +
                "(id, coach_id, day_of_week, start_time, end_time, canonical_timezone) " +
                "VALUES (?, ?, ?, '08:00', '18:00', ?)",
                UUID.randomUUID(), coachProfileId, windowDow, WINDOW_TZ
            );

            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM payment.booking_payments WHERE booking_id IN (SELECT id FROM booking.bookings WHERE parent_id = ?)", SELF_PLAYER_USER_ID);
            jdbcTemplate.update("DELETE FROM booking.bookings WHERE parent_id = ?", SELF_PLAYER_USER_ID);
            jdbcTemplate.update("DELETE FROM booking.booking_batches WHERE parent_id = ?", SELF_PLAYER_USER_ID);
            jdbcTemplate.update("DELETE FROM marketplace.coach_availability_windows WHERE coach_id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_pricing WHERE coach_id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM main.player_profiles WHERE id = ?", SELF_PLAYER_ID);
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?)", SELF_PLAYER_USER_ID, COACH_USER_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?)", SELF_PLAYER_USER_ID, COACH_USER_ID);
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void createBookingRequest_selfRegisteredPlayer_returns201AndPersistsOwnUserIdAsParentId() {
        String cookies = loginAndGetCookies(SELF_PLAYER_EMAIL);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + BOOKINGS_BASE,
            HttpMethod.POST,
            Map.of(
                "coachId", coachProfileId.toString(),
                "playerId", SELF_PLAYER_ID,
                "requestedStartTime", slotStart.toString(),
                "requestedEndTime", slotEnd.toString()
            ),
            authenticatedHeaders(cookies),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("status")).isEqualTo("REQUESTED");

        String bookingId = (String) response.getBody().get("id");
        Long persistedParentId = jdbcTemplate.queryForObject(
            "SELECT parent_id FROM booking.bookings WHERE id = ?::uuid", Long.class, bookingId);
        assertThat(persistedParentId)
            .as("the opaque-id design: a self-booking player's own userId lands in booking.parent_id")
            .isEqualTo(SELF_PLAYER_USER_ID);
    }

    @Test
    void getParentBookings_selfRegisteredPlayer_returnsOwnBookings() {
        String cookies = loginAndGetCookies(SELF_PLAYER_EMAIL);

        httpTestClient.makeHttpRequest(
            baseUrl() + BOOKINGS_BASE,
            HttpMethod.POST,
            Map.of(
                "coachId", coachProfileId.toString(),
                "playerId", SELF_PLAYER_ID,
                "requestedStartTime", slotStart.toString(),
                "requestedEndTime", slotEnd.toString()
            ),
            authenticatedHeaders(cookies),
            Map.class
        );

        ResponseEntity<List> response = httpTestClient.makeHttpRequest(
            baseUrl() + BOOKINGS_BASE,
            HttpMethod.GET,
            null,
            authenticatedHeaders(cookies),
            List.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
    }

    @Test
    void createBatch_selfRegisteredPlayer_returns201AndPersistsOwnUserIdAsParentId() {
        String cookies = loginAndGetCookies(SELF_PLAYER_EMAIL);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + BATCH_BASE,
            HttpMethod.POST,
            Map.of(
                "coachId", coachProfileId.toString(),
                "playerId", SELF_PLAYER_ID,
                "slots", List.of(Map.of(
                    "requestedStartTime", slotStart.toString(),
                    "requestedEndTime", slotEnd.toString()
                )),
                "totalAmount", 50.00
            ),
            authenticatedHeaders(cookies),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String batchId = (String) response.getBody().get("batchId");
        Long persistedParentId = jdbcTemplate.queryForObject(
            "SELECT parent_id FROM booking.booking_batches WHERE id = ?::uuid", Long.class, batchId);
        assertThat(persistedParentId)
            .as("the opaque-id design applies identically on the batch path")
            .isEqualTo(SELF_PLAYER_USER_ID);
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
