package com.softropic.skillars.platform.booking.api;

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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class AvailabilityResourceIT extends AbstractIntegrationTest {

    private static final String LOGIN_ENDPOINT    = "/api/auth/login";
    private static final String AVAILABILITY_BASE = "/api/bookings/coaches";
    private static final String CLIENT_ID         = "testClientId";
    private static final String TEST_PASSWORD     = "CoachPass@123!";

    private static final long COACH_ID  = 9300000001L;
    private static final long PARENT_ID = 9300000002L;
    private static final String COACH_EMAIL  = "coach.availability@skillars-test.com";
    private static final String PARENT_EMAIL = "parent.availability@skillars-test.com";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private PasswordEncoder passwordEncoder;

    @LocalServerPort
    private int randomServerPort;

    private UUID coachProfileId;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (9300, 'ROLE_COACH', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now())
            );
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (9301, 'ROLE_PARENT', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now())
            );
            insertCoachUser(COACH_ID, COACH_EMAIL, passwordHash);
            insertParentUser(PARENT_ID, PARENT_EMAIL, passwordHash);
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_COACH')) ON CONFLICT DO NOTHING",
                COACH_ID
            );
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_PARENT')) ON CONFLICT DO NOTHING",
                PARENT_ID
            );

            // Insert minimal coach profile so availability operations can resolve profile UUID
            coachProfileId = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles " +
                "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, 'Coach Availability', 'Test bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
                coachProfileId, COACH_ID
            );
            return null;
        });
    }


    @Test
    void getAvailability_noWindowsNoBlocks_returnsEmpty() {
        String cookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + AVAILABILITY_BASE + "/" + coachProfileId + "/availability?weekStart=2026-06-16",
            HttpMethod.GET,
            null,
            authenticatedHeaders(cookies),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) response.getBody().get("windows")).isEmpty();
        assertThat((List<?>) response.getBody().get("blocks")).isEmpty();
        assertThat((List<?>) response.getBody().get("computedSlots")).isEmpty();
        assertThat(response.getBody().get("canonicalTimezone")).isEqualTo("Europe/Berlin");
    }

    /**
     * Deferred-17 AC4. The window's own canonical_timezone is a separate, independently-writable
     * column from the coach profile's — this seeds them to different zones and asserts the
     * response's top-level canonicalTimezone reflects the coach profile's zone, not the window's.
     *
     * <p>Scope, precisely: this pins where the top-level <em>response field</em> is sourced from.
     * It does not assert that the computedSlots instants agree with that zone — they are still
     * materialized from the window's zone (see AvailabilityService), a known and accepted split
     * recorded as D8 in deferred-work.md. The windows assertion is load-bearing: without it, a
     * seeded window that never reached the response (wrong coach, or a weekStart that filters out
     * day_of_week=2) would leave this test green while exercising no divergence at all.
     */
    @Test
    void getAvailability_windowTimezoneDivergesFromProfile_responseUsesProfileTimezone() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_availability_windows " +
                "(id, coach_id, day_of_week, start_time, end_time, canonical_timezone) " +
                "VALUES (?, ?, 2, '09:00', '17:00', 'America/New_York')",
                UUID.randomUUID(), coachProfileId
            );
            return null;
        });

        String cookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + AVAILABILITY_BASE + "/" + coachProfileId + "/availability?weekStart=2026-06-16",
            HttpMethod.GET,
            null,
            authenticatedHeaders(cookies),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) response.getBody().get("windows")).hasSize(1);
        assertThat(response.getBody().get("canonicalTimezone")).isEqualTo("Europe/Berlin");
    }

    /**
     * AC1 end-to-end over HTTP against the real query. The only other AC1 coverage mocks
     * BookingRepository, so nothing exercised the actual findOverlappingBookings JPQL with the
     * padded bounds, ACTIVE_SLOT_STATUSES and a null excludeBookingId on this path — flagged by the
     * 2026-08-07 code review. The booking belongs to a parent/player unrelated to the caller, which
     * is the whole point of AC1: the deleted frontend guard only ever saw the current parent's own.
     */
    @Test
    void getAvailability_activeBookingFromAnotherRequester_isCarvedOutOfComputedSlots() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_availability_windows " +
                "(id, coach_id, day_of_week, start_time, end_time, canonical_timezone) " +
                "VALUES (?, ?, 2, '09:00', '17:00', 'Europe/Berlin')",
                UUID.randomUUID(), coachProfileId
            );
            // Tuesday 2026-06-16, 10:00-11:00Z, inside the 07:00-15:00Z window (09:00-17:00 CEST).
            jdbcTemplate.update(
                "INSERT INTO booking.bookings " +
                "(id, parent_id, player_id, coach_id, requested_start_time, requested_end_time, " +
                " status, canonical_timezone) " +
                "VALUES (?, 9399999998, 9399999999, ?, ?, ?, 'REQUESTED', 'Europe/Berlin')",
                UUID.randomUUID(), coachProfileId,
                Timestamp.from(Instant.parse("2026-06-16T10:00:00Z")),
                Timestamp.from(Instant.parse("2026-06-16T11:00:00Z"))
            );
            return null;
        });

        String cookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + AVAILABILITY_BASE + "/" + coachProfileId + "/availability?weekStart=2026-06-16",
            HttpMethod.GET,
            null,
            authenticatedHeaders(cookies),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) response.getBody().get("windows")).hasSize(1);

        // UAT.2 AC2 reshaped this from "the segment is split" to "the slots covering the booked
        // hour are absent": the 07:00Z-15:00Z window slices into eight one-hour slots, and the one
        // starting 10:00Z is carved out, leaving seven. Both halves of the assertion are
        // load-bearing — without the carve-out there would be eight, including the booked hour.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> slots = (List<Map<String, Object>>) response.getBody().get("computedSlots");
        assertThat(slots).hasSize(7);
        assertThat(slots).extracting(s -> Instant.parse((String) s.get("startDatetime")))
            .doesNotContain(Instant.parse("2026-06-16T10:00:00Z"))
            .contains(Instant.parse("2026-06-16T09:00:00Z"), Instant.parse("2026-06-16T11:00:00Z"));
        // A booking is not a block — the transient pseudo-block must not leak into `blocks`.
        assertThat((List<?>) response.getBody().get("blocks")).isEmpty();
    }

    @Test
    void getAvailability_unknownCoachId_returns404() {
        String cookies = loginAndGetCookies(COACH_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + AVAILABILITY_BASE + "/" + UUID.randomUUID() + "/availability?weekStart=2026-06-16",
            HttpMethod.GET,
            null,
            authenticatedHeaders(cookies),
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void addWindow_validRequest_returns201AndPersists() {
        String cookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + AVAILABILITY_BASE + "/me/availability/windows",
            HttpMethod.POST,
            Map.of("dayOfWeek", 1, "startTime", "09:00:00", "endTime", "11:00:00"),
            authenticatedHeaders(cookies),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsKey("id");
        assertThat(response.getBody().get("dayOfWeek")).isEqualTo(1);

        int count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM marketplace.coach_availability_windows WHERE coach_id = ?",
            Integer.class, coachProfileId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void deleteWindow_ownedByCoach_returns204() {
        String cookies = loginAndGetCookies(COACH_EMAIL);

        // Add a window first
        ResponseEntity<Map> created = httpTestClient.makeHttpRequest(
            baseUrl() + AVAILABILITY_BASE + "/me/availability/windows",
            HttpMethod.POST,
            Map.of("dayOfWeek", 2, "startTime", "10:00:00", "endTime", "12:00:00"),
            authenticatedHeaders(cookies),
            Map.class
        );
        String windowId = (String) created.getBody().get("id");

        ResponseEntity<Void> deleteResponse = httpTestClient.makeHttpRequest(
            baseUrl() + AVAILABILITY_BASE + "/me/availability/windows/" + windowId,
            HttpMethod.DELETE,
            null,
            authenticatedHeaders(cookies),
            Void.class
        );

        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        int count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM marketplace.coach_availability_windows WHERE id = ?",
            Integer.class, UUID.fromString(windowId));
        assertThat(count).isZero();
    }

    @Test
    void addBlock_validRange_returns201AndAppearsAsUnavailable() {
        String cookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + AVAILABILITY_BASE + "/me/availability/blocks",
            HttpMethod.POST,
            Map.of(
                "startDatetime", "2026-06-16T09:00:00Z",
                "endDatetime", "2026-06-16T17:00:00Z",
                "reason", "Vacation"
            ),
            authenticatedHeaders(cookies),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsKey("id");
        assertThat(response.getBody().get("reason")).isEqualTo("Vacation");

        int count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM booking.coach_availability_blocks WHERE coach_id = ?",
            Integer.class, coachProfileId);
        assertThat(count).isEqualTo(1);

        // Verify block appears in the availability response for that week
        ResponseEntity<Map> availResponse = httpTestClient.makeHttpRequest(
            baseUrl() + AVAILABILITY_BASE + "/" + coachProfileId + "/availability?weekStart=2026-06-15",
            HttpMethod.GET,
            null,
            authenticatedHeaders(cookies),
            Map.class
        );
        assertThat(availResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) availResponse.getBody().get("blocks")).hasSize(1);
    }

    @Test
    void deleteBlock_notOwnedByCoach_returns403() {
        // Insert a block owned by a different coach UUID
        UUID otherCoachId = UUID.randomUUID();
        UUID blockId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO booking.coach_availability_blocks (id, coach_id, start_datetime, end_datetime) " +
            "VALUES (?, ?, ?, ?)",
            blockId, otherCoachId,
            Timestamp.from(Instant.parse("2026-06-17T08:00:00Z")),
            Timestamp.from(Instant.parse("2026-06-17T10:00:00Z"))
        );

        String cookies = loginAndGetCookies(COACH_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + AVAILABILITY_BASE + "/me/availability/blocks/" + blockId,
            HttpMethod.DELETE,
            null,
            authenticatedHeaders(cookies),
            Void.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isIn(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND));

        // Clean up orphan block
        jdbcTemplate.update("DELETE FROM booking.coach_availability_blocks WHERE id = ?", blockId);
    }

    @Test
    void updateWindow_validRequest_returns200AndPersists() {
        String cookies = loginAndGetCookies(COACH_EMAIL);

        // Create a window
        ResponseEntity<Map> created = httpTestClient.makeHttpRequest(
            baseUrl() + AVAILABILITY_BASE + "/me/availability/windows",
            HttpMethod.POST,
            Map.of("dayOfWeek", 3, "startTime", "08:00:00", "endTime", "10:00:00"),
            authenticatedHeaders(cookies),
            Map.class
        );
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String windowId = (String) created.getBody().get("id");

        // Update it
        ResponseEntity<Map> updated = httpTestClient.makeHttpRequest(
            baseUrl() + AVAILABILITY_BASE + "/me/availability/windows/" + windowId,
            HttpMethod.PUT,
            Map.of("dayOfWeek", 3, "startTime", "09:00:00", "endTime", "11:00:00"),
            authenticatedHeaders(cookies),
            Map.class
        );

        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody().get("startTime")).isEqualTo("09:00:00");
        assertThat(updated.getBody().get("endTime")).isEqualTo("11:00:00");
        assertThat(updated.getBody()).containsKey("hasConflict");

        // Verify updated values persisted
        String startTime = jdbcTemplate.queryForObject(
            "SELECT start_time FROM marketplace.coach_availability_windows WHERE id = ?",
            String.class, UUID.fromString(windowId));
        assertThat(startTime).startsWith("09:00");
    }

    // ---- Deferred-78 AC6: weekStart bound (default booking.availability.weekStartRangeYears=2) ----

    @Test
    void getAvailability_weekStartExactlyAtPastBoundary_returns200() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        String weekStart = java.time.LocalDate.now().minusYears(2).toString();

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + AVAILABILITY_BASE + "/" + coachProfileId + "/availability?weekStart=" + weekStart,
            HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getAvailability_weekStartOneDayPastBoundary_returns403WithWeekStartOutOfRangeKey() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        String weekStart = java.time.LocalDate.now().minusYears(2).minusDays(1).toString();

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + AVAILABILITY_BASE + "/" + coachProfileId + "/availability?weekStart=" + weekStart,
            HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(ex.getResponseBodyAsString()).contains("\"errorKey\":\"booking.weekStartOutOfRange\"");
            });
    }

    @Test
    void getAvailability_weekStartExactlyAtFutureBoundary_returns200() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        String weekStart = java.time.LocalDate.now().plusYears(2).toString();

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + AVAILABILITY_BASE + "/" + coachProfileId + "/availability?weekStart=" + weekStart,
            HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getAvailability_weekStartOneDayFutureBoundary_returns403WithWeekStartOutOfRangeKey() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        String weekStart = java.time.LocalDate.now().plusYears(2).plusDays(1).toString();

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + AVAILABILITY_BASE + "/" + coachProfileId + "/availability?weekStart=" + weekStart,
            HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(ex.getResponseBodyAsString()).contains("\"errorKey\":\"booking.weekStartOutOfRange\"");
            });
    }

    // ----- helpers -----

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


    private void insertCoachUser(long id, String email, String passwordHash) {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" " +
            "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, " +
            "status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, " +
            "activated, locked, login, login_id_type, password_hash, otp_enabled, " +
            "skillars_role, verification_status) " +
            "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, " +
            "'ACTIVE', '1990-03-15', ?, 'Test', 'OTHER', 'en', 'Coach', 'DE', ?, " +
            "true, false, ?, 'EMAIL', ?, false, " +
            "'COACH', 'BASIC_VERIFIED')",
            id,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            email,
            "680" + (id % 10000000),
            email, passwordHash
        );
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
            "680" + (id % 10000000 + 1),
            email, passwordHash
        );
    }
}
