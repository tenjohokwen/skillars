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
class ManualStrikeIT {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String CLIENT_ID      = "testClientId";
    private static final String TEST_PASSWORD  = "TestPass@123!";

    private static final long ADMIN_ID      = 9070_000_100L;
    private static final long COACH_USER_ID = 9070_000_010L;

    private static final String ADMIN_EMAIL = "admin.manualstrike9070@skillars-test.com";
    private static final String COACH_EMAIL = "coach.manualstrike9070@skillars-test.com";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private PasswordEncoder passwordEncoder;

    @LocalServerPort private int randomServerPort;

    private UUID coachProfileId;
    private UUID bookingId;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        coachProfileId = UUID.randomUUID();
        bookingId = UUID.randomUUID();

        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) VALUES (9070, 'ROLE_COACH', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) VALUES (9071, 'ROLE_ADMIN', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));

            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH");
            grantAuthority(COACH_USER_ID, "ROLE_COACH");

            insertUser(ADMIN_ID, ADMIN_EMAIL, passwordHash, "ADMIN");
            grantAuthority(ADMIN_ID, "ROLE_ADMIN");

            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles (id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, 'Strike Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
                coachProfileId, COACH_USER_ID);

            jdbcTemplate.update(
                "INSERT INTO booking.bookings (id, parent_id, player_id, coach_id, requested_start_time, requested_end_time, status, canonical_timezone, version, created_at, updated_at) " +
                "VALUES (?, 9070999001, 9070999002, ?, ?, ?, 'COMPLETED', 'Europe/Berlin', 0, ?, ?)",
                bookingId, coachProfileId,
                Timestamp.from(Instant.now().minusSeconds(7200)), Timestamp.from(Instant.now().minusSeconds(3600)),
                Timestamp.from(Instant.now().minusSeconds(7200)), Timestamp.from(Instant.now()));

            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM admin.admin_action_log WHERE reference_id = ?", coachProfileId.toString());
            jdbcTemplate.update("DELETE FROM admin.admin_alerts WHERE reference_id = ?", coachProfileId.toString());
            jdbcTemplate.update("DELETE FROM marketplace.coach_reliability_strikes WHERE coach_id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM booking.bookings WHERE coach_id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id = ?", coachProfileId);
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?)", COACH_USER_ID, ADMIN_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?)", COACH_USER_ID, ADMIN_ID);
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id IN (9070, 9071)");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void issueManualStrike_insertsStrikeRow() {
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/admin/coaches/" + coachProfileId + "/strikes",
            HttpMethod.POST,
            Map.of("bookingId", bookingId.toString(), "reason", "COACH_NO_SHOW"),
            authenticatedHeaders(adminCookies), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).containsKey("strikeId");

        Long strikeCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM marketplace.coach_reliability_strikes WHERE coach_id = ?",
            Long.class, coachProfileId);
        assertThat(strikeCount).isEqualTo(1L);
    }

    @Test
    void deleteStrike_thatWasFinalStrike_revertsStatusToActiveAndResolvesAlert() {
        // Seed 3 strikes → coach becomes PENDING_REVIEW
        UUID strikeToDelete = UUID.randomUUID();
        UUID alertId = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            for (int i = 0; i < 2; i++) {
                jdbcTemplate.update(
                    "INSERT INTO marketplace.coach_reliability_strikes (id, coach_id, booking_id, reason, created_at, acknowledged) VALUES (?, ?, ?, 'COACH_NO_SHOW', ?, false)",
                    UUID.randomUUID(), coachProfileId, bookingId, Timestamp.from(Instant.now()));
            }
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_reliability_strikes (id, coach_id, booking_id, reason, created_at, acknowledged) VALUES (?, ?, ?, 'COACH_NO_SHOW', ?, false)",
                strikeToDelete, coachProfileId, bookingId, Timestamp.from(Instant.now()));
            jdbcTemplate.update("UPDATE marketplace.coach_profiles SET status = 'PENDING_REVIEW' WHERE id = ?", coachProfileId);
            jdbcTemplate.update(
                "INSERT INTO admin.admin_alerts (alert_id, type, reference_id, reference_type, status, created_at) " +
                "VALUES (?, 'STRIKE_THRESHOLD', ?, 'COACH', 'OPEN', ?)",
                alertId, coachProfileId.toString(), Timestamp.from(Instant.now()));
            return null;
        });

        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        ResponseEntity<Void> resp = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/admin/coaches/" + coachProfileId + "/strikes/" + strikeToDelete + "?reason=Issued+in+error",
            HttpMethod.DELETE, null, authenticatedHeaders(adminCookies), Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        String coachStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM marketplace.coach_profiles WHERE id = ?", String.class, coachProfileId);
        assertThat(coachStatus).isEqualTo("ACTIVE");

        String alertStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM admin.admin_alerts WHERE alert_id = ?", String.class, alertId);
        assertThat(alertStatus).isEqualTo("RESOLVED");

        jdbcTemplate.update("DELETE FROM admin.admin_alerts WHERE alert_id = ?", alertId);
    }

    @Test
    void deleteStrike_noStatusChange_doesNotResolveAlert() {
        // Seed 5 strikes → PENDING_REVIEW; deleting 1 → 4, still above threshold=3
        UUID strikeToDelete = UUID.randomUUID();
        UUID alertId = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            for (int i = 0; i < 4; i++) {
                jdbcTemplate.update(
                    "INSERT INTO marketplace.coach_reliability_strikes (id, coach_id, booking_id, reason, created_at, acknowledged) VALUES (?, ?, ?, 'COACH_NO_SHOW', ?, false)",
                    UUID.randomUUID(), coachProfileId, bookingId, Timestamp.from(Instant.now()));
            }
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_reliability_strikes (id, coach_id, booking_id, reason, created_at, acknowledged) VALUES (?, ?, ?, 'COACH_NO_SHOW', ?, false)",
                strikeToDelete, coachProfileId, bookingId, Timestamp.from(Instant.now()));
            jdbcTemplate.update("UPDATE marketplace.coach_profiles SET status = 'PENDING_REVIEW' WHERE id = ?", coachProfileId);
            jdbcTemplate.update(
                "INSERT INTO admin.admin_alerts (alert_id, type, reference_id, reference_type, status, created_at) " +
                "VALUES (?, 'STRIKE_THRESHOLD', ?, 'COACH', 'OPEN', ?)",
                alertId, coachProfileId.toString(), Timestamp.from(Instant.now()));
            return null;
        });

        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        httpTestClient.makeHttpRequest(
            baseUrl() + "/api/admin/coaches/" + coachProfileId + "/strikes/" + strikeToDelete + "?reason=Partial+review",
            HttpMethod.DELETE, null, authenticatedHeaders(adminCookies), Void.class);

        String coachStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM marketplace.coach_profiles WHERE id = ?", String.class, coachProfileId);
        assertThat(coachStatus).isEqualTo("PENDING_REVIEW");

        String alertStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM admin.admin_alerts WHERE alert_id = ?", String.class, alertId);
        assertThat(alertStatus).isEqualTo("OPEN");

        jdbcTemplate.update("DELETE FROM admin.admin_alerts WHERE alert_id = ?", alertId);
    }

    @Test
    void invalidStrikeReason_returns400() {
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/admin/coaches/" + coachProfileId + "/strikes",
            HttpMethod.POST,
            Map.of("bookingId", bookingId.toString(), "reason", "INVALID_REASON"),
            authenticatedHeaders(adminCookies), Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void issueStrikeWithBookingFromDifferentCoach_returns400() {
        // Seed a second coach and a booking that belongs to them
        UUID otherCoachId = UUID.randomUUID();
        UUID otherBookingId = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.\"user\" (id, created_by, created_date, last_modified_by, last_modified_date, request_id, status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, activated, locked, login, login_id_type, password_hash, otp_enabled, skillars_role, verification_status) " +
                "VALUES (9070000099, 'system', ?, 'system', ?, 'test-req', 'ACTIVE', '1985-06-01', 'othercoach9070@skillars-test.com', 'Test', 'OTHER', 'en', 'Coach', 'DE', '9070099', true, false, 'othercoach9070@skillars-test.com', 'EMAIL', 'noop', false, 'COACH', 'BASIC_VERIFIED')",
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles (id, user_id, display_name, canonical_timezone, status) VALUES (?, 9070000099, 'Other Coach', 'Europe/Berlin', 'ACTIVE')",
                otherCoachId);
            jdbcTemplate.update(
                "INSERT INTO booking.bookings (id, parent_id, player_id, coach_id, requested_start_time, requested_end_time, status, canonical_timezone, version, created_at, updated_at) " +
                "VALUES (?, 9070999001, 9070999002, ?, ?, ?, 'COMPLETED', 'Europe/Berlin', 0, ?, ?)",
                otherBookingId, otherCoachId,
                Timestamp.from(Instant.now().minusSeconds(7200)), Timestamp.from(Instant.now().minusSeconds(3600)),
                Timestamp.from(Instant.now().minusSeconds(7200)), Timestamp.from(Instant.now()));
            return null;
        });

        try {
            String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
            assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
                baseUrl() + "/api/admin/coaches/" + coachProfileId + "/strikes",
                HttpMethod.POST,
                Map.of("bookingId", otherBookingId.toString(), "reason", "COACH_NO_SHOW"),
                authenticatedHeaders(adminCookies), Map.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
        } finally {
            transactionTemplate.execute(status -> {
                jdbcTemplate.update("DELETE FROM booking.bookings WHERE id = ?", otherBookingId);
                jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id = ?", otherCoachId);
                jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id = 9070000099");
                return null;
            });
        }
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
            email, role, "907" + (id % 10000000), email, passwordHash, role);
    }

    private void grantAuthority(long userId, String roleName) {
        jdbcTemplate.update(
            "INSERT INTO main.user_authority (user_id, authority_id) VALUES (?, (SELECT id FROM main.authority WHERE name = ?)) ON CONFLICT DO NOTHING",
            userId, roleName);
    }
}
