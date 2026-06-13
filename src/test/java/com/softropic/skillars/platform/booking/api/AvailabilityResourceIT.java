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
class AvailabilityResourceIT {

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

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM booking.coach_availability_blocks WHERE coach_id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_availability_windows WHERE coach_id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id = ?", coachProfileId);
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.update(
                "DELETE FROM main.user_authority WHERE user_id IN (?, ?)", COACH_ID, PARENT_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?)", COACH_ID, PARENT_ID);
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id IN (9300, 9301)");
            jdbcTemplate.execute("DELETE FROM main.sec");
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

    private String baseUrl() {
        return "http://localhost:" + randomServerPort;
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
