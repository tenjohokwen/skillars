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
class BookingBatchResourceIT {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String CLIENT_ID      = "testClientId";
    private static final String TEST_PASSWORD  = "TestPass@123!";

    private static final long PARENT_ID      = 9800000001L;
    private static final long PLAYER_ID      = 9800000002L;
    private static final long COACH_USER_ID  = 9800000010L;
    private static final long COACH_2_USER_ID = 9800000011L;

    private static final String PARENT_EMAIL  = "parent.batch@skillars-test.com";
    private static final String COACH_EMAIL   = "coach.batch@skillars-test.com";
    private static final String COACH_2_EMAIL = "coach2.batch@skillars-test.com";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private PasswordEncoder passwordEncoder;

    @LocalServerPort private int randomServerPort;

    private UUID coachProfileId;
    private UUID coach2ProfileId;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        coachProfileId  = UUID.randomUUID();
        coach2ProfileId = UUID.randomUUID();

        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (9800, 'ROLE_PARENT', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now())
            );
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (9801, 'ROLE_COACH', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
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
                "VALUES (?, 'Batch Player', '2012-01-01', 'MIDFIELDER', 'ADULT', ?, true, ?, 'system')",
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
                "VALUES (?, ?, 'Batch Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
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
                coach2ProfileId, COACH_2_USER_ID
            );
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_pricing (coach_id, per_session_price, currency) VALUES (?, 50.00, 'EUR')",
                coach2ProfileId
            );

            jdbcTemplate.update(
                "INSERT INTO booking.session_packs_purchased " +
                "(id, parent_id, player_id, coach_id, session_count, credits_remaining, status, purchased_at, expires_at) " +
                "VALUES (?, ?, ?, ?, 10, 10, 'ACTIVE', ?, ?)",
                UUID.randomUUID(), PARENT_ID, PLAYER_ID, coachProfileId,
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now().plus(365, ChronoUnit.DAYS))
            );

            jdbcTemplate.update(
                "INSERT INTO main.platform_config (id, key, value, value_type, description, updated_at) " +
                "VALUES (50, 'booking.batch.maxSize', '5', 'LONG', 'Batch max size', ?) ON CONFLICT DO NOTHING",
                Timestamp.from(Instant.now())
            );

            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM booking.bookings WHERE parent_id = ?", PARENT_ID);
            jdbcTemplate.update("DELETE FROM booking.booking_batches WHERE parent_id = ?", PARENT_ID);
            jdbcTemplate.update("DELETE FROM booking.session_packs_purchased WHERE parent_id = ?", PARENT_ID);
            jdbcTemplate.update("DELETE FROM marketplace.coach_pricing WHERE coach_id IN (?, ?)", coachProfileId, coach2ProfileId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id IN (?, ?)", coachProfileId, coach2ProfileId);
            jdbcTemplate.update("DELETE FROM main.player_profiles WHERE id = ?", PLAYER_ID);
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?, ?)", PARENT_ID, COACH_USER_ID, COACH_2_USER_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?, ?)", PARENT_ID, COACH_USER_ID, COACH_2_USER_ID);
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id IN (9800, 9801)");
            jdbcTemplate.execute("DELETE FROM main.sec");
            jdbcTemplate.update("DELETE FROM main.platform_config WHERE id = 50");
            return null;
        });
    }

    @Test
    void createBatch_asParentWithTwoSlots_returns201AndCreatesRecords() {
        String parentCookies = loginAndGetCookies(PARENT_EMAIL);
        Instant base = Instant.now().plus(3, ChronoUnit.DAYS);
        Map<String, Object> req = Map.of(
            "coachId", coachProfileId.toString(),
            "playerId", PLAYER_ID,
            "totalAmount", 0,
            "slots", List.of(
                Map.of("requestedStartTime", base.toString(),
                       "requestedEndTime", base.plus(1, ChronoUnit.HOURS).toString()),
                Map.of("requestedStartTime", base.plus(2, ChronoUnit.HOURS).toString(),
                       "requestedEndTime", base.plus(3, ChronoUnit.HOURS).toString())
            )
        );

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/batches",
            HttpMethod.POST, req, authenticatedHeaders(parentCookies), Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsKey("batchId");
        assertThat(response.getBody().get("bookingCount")).isEqualTo(2);

        Integer batchCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM booking.booking_batches WHERE parent_id = ?", Integer.class, PARENT_ID);
        assertThat(batchCount).isEqualTo(1);

        Integer bookingCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM booking.bookings WHERE parent_id = ? AND batch_id IS NOT NULL", Integer.class, PARENT_ID);
        assertThat(bookingCount).isEqualTo(2);
    }

    @Test
    void createBatch_unauthenticated_returns401() {
        Instant base = Instant.now().plus(3, ChronoUnit.DAYS);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/batches",
            HttpMethod.POST,
            Map.of("coachId", coachProfileId.toString(), "playerId", PLAYER_ID,
                   "totalAmount", 0,
                   "slots", List.of(Map.of("requestedStartTime", base.toString(),
                                           "requestedEndTime", base.plus(1, ChronoUnit.HOURS).toString()))),
            clientHeaders(), Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void createBatch_asCoach_returns403() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);
        Instant base = Instant.now().plus(3, ChronoUnit.DAYS);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/batches",
            HttpMethod.POST,
            Map.of("coachId", coachProfileId.toString(), "playerId", PLAYER_ID,
                   "totalAmount", 0,
                   "slots", List.of(Map.of("requestedStartTime", base.toString(),
                                           "requestedEndTime", base.plus(1, ChronoUnit.HOURS).toString()))),
            authenticatedHeaders(coachCookies), Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void createBatch_withSixSlots_returns400WithBatchSizeExceeded() {
        String parentCookies = loginAndGetCookies(PARENT_EMAIL);
        Instant base = Instant.now().plus(3, ChronoUnit.DAYS);
        List<Map<String, String>> slots = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Instant start = base.plus(i * 2L, ChronoUnit.HOURS);
            slots.add(Map.of("requestedStartTime", start.toString(),
                             "requestedEndTime", start.plus(1, ChronoUnit.HOURS).toString()));
        }

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/batches",
            HttpMethod.POST,
            Map.of("coachId", coachProfileId.toString(), "playerId", PLAYER_ID,
                   "totalAmount", 0, "slots", slots),
            authenticatedHeaders(parentCookies), Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(ex.getResponseBodyAsString()).contains("booking.batchSizeExceeded");
            });
    }

    @Test
    void acceptAll_asOwningCoach_returns204AndUpdatesBookingsAndBatch() {
        UUID batchId = createBatchInDb(2);
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/batches/" + batchId + "/accept-all",
            HttpMethod.POST, null, authenticatedHeaders(coachCookies), Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        String batchStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM booking.booking_batches WHERE id = ?", String.class, batchId);
        assertThat(batchStatus).isEqualTo("FULLY_ACCEPTED");
    }

    @Test
    void acceptAll_wrongCoach_returns403() {
        UUID batchId = createBatchInDb(1);
        String coach2Cookies = loginAndGetCookies(COACH_2_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/batches/" + batchId + "/accept-all",
            HttpMethod.POST, null, authenticatedHeaders(coach2Cookies), Void.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void getCoachBookingRequests_withBatch_returnsGroupedBatchStructure() {
        createBatchInDb(2);
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/requests/coach",
            HttpMethod.GET, null, authenticatedHeaders(coachCookies), Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKeys("singleBookings", "batchGroups");
        List<?> batchGroups = (List<?>) response.getBody().get("batchGroups");
        assertThat(batchGroups).hasSize(1);
    }

    @Test
    void individualAcceptDecline_updatesBatchStatusToPartiallyAccepted() {
        UUID batchId = createBatchInDb(2);
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        List<Map<String, Object>> bookingRows = jdbcTemplate.queryForList(
            "SELECT id FROM booking.bookings WHERE batch_id = ?", batchId);
        assertThat(bookingRows).hasSize(2);

        UUID firstBookingId = (UUID) bookingRows.get(0).get("id");
        UUID secondBookingId = (UUID) bookingRows.get(1).get("id");

        httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/requests/" + firstBookingId + "/accept",
            HttpMethod.PUT, null, authenticatedHeaders(coachCookies), Map.class
        );

        httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/requests/" + secondBookingId + "/decline",
            HttpMethod.PUT, null, authenticatedHeaders(coachCookies), Void.class
        );

        String batchStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM booking.booking_batches WHERE id = ?", String.class, batchId);
        assertThat(batchStatus).isEqualTo("PARTIALLY_ACCEPTED");
    }

    // ---- Helpers ----

    private UUID createBatchInDb(int bookingCount) {
        UUID batchId = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO booking.booking_batches " +
                "(id, parent_id, coach_id, requested_count, total_amount, status, created_at) " +
                "VALUES (?, ?, ?, ?, 0.00, 'PENDING', ?)",
                batchId, PARENT_ID, coachProfileId, bookingCount, Timestamp.from(Instant.now())
            );
            for (int i = 0; i < bookingCount; i++) {
                Instant start = Instant.now().plus(3 + i, ChronoUnit.DAYS);
                jdbcTemplate.update(
                    "INSERT INTO booking.bookings " +
                    "(id, parent_id, player_id, coach_id, requested_start_time, requested_end_time, " +
                    "status, canonical_timezone, batch_id, version, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, 'REQUESTED', 'Europe/Berlin', ?, 0, ?, ?)",
                    UUID.randomUUID(), PARENT_ID, PLAYER_ID, coachProfileId,
                    Timestamp.from(start), Timestamp.from(start.plus(1, ChronoUnit.HOURS)),
                    batchId, Timestamp.from(Instant.now()), Timestamp.from(Instant.now())
                );
            }
            return null;
        });
        return batchId;
    }

    private String loginAndGetCookies(String email) {
        ResponseEntity<Map> loginResponse = httpTestClient.makeHttpRequest(
            baseUrl() + LOGIN_ENDPOINT,
            HttpMethod.POST,
            Map.of("email", email, "password", TEST_PASSWORD),
            clientHeaders(), Map.class
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
