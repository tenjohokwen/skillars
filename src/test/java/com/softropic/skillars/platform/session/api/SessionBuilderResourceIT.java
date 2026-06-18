package com.softropic.skillars.platform.session.api;

import com.softropic.skillars.config.TestConfig;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.security.SecurityIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
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
class SessionBuilderResourceIT extends BaseSessionIT {

    private static final String SESSIONS_BASE = "/api/session/sessions";

    private static final long INSTR_COACH_USER_ID = 9570000010L;
    private static final long SCOUT_COACH_USER_ID = 9570000020L;
    private static final long OTHER_COACH_USER_ID = 9570000030L;

    private static final String INSTR_EMAIL = "instr.builder@skillars-test.com";
    private static final String SCOUT_EMAIL = "scout.builder@skillars-test.com";
    private static final String OTHER_EMAIL = "other.builder@skillars-test.com";

    private UUID instrCoachId;
    private UUID scoutCoachId;
    private UUID otherCoachId;

    private UUID confirmedBookingId;
    private UUID requestedBookingId;
    private UUID otherCoachBookingId;

    private UUID drillId;

    @MockitoBean
    VideoProviderAdapter videoProviderAdapter;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (9570, 'ROLE_COACH', 'ACTIVE', 'system', NOW()) ON CONFLICT (name) DO NOTHING"
            );

            insertUser(INSTR_COACH_USER_ID, INSTR_EMAIL, passwordHash, "COACH");
            grantRole(INSTR_COACH_USER_ID, "ROLE_COACH");
            instrCoachId = insertCoachProfile(INSTR_COACH_USER_ID);
            insertSubscription(instrCoachId, "INSTRUCTOR");

            insertUser(SCOUT_COACH_USER_ID, SCOUT_EMAIL, passwordHash, "COACH");
            grantRole(SCOUT_COACH_USER_ID, "ROLE_COACH");
            scoutCoachId = insertCoachProfile(SCOUT_COACH_USER_ID);
            insertSubscription(scoutCoachId, "SCOUT");

            insertUser(OTHER_COACH_USER_ID, OTHER_EMAIL, passwordHash, "COACH");
            grantRole(OTHER_COACH_USER_ID, "ROLE_COACH");
            otherCoachId = insertCoachProfile(OTHER_COACH_USER_ID);
            insertSubscription(otherCoachId, "INSTRUCTOR");

            drillId = UUID.randomUUID();
            insertDrill(drillId, "Test Drill", "PLATFORM", null, "ACTIVE");

            confirmedBookingId = UUID.randomUUID();
            insertBooking(confirmedBookingId, instrCoachId, "CONFIRMED");

            requestedBookingId = UUID.randomUUID();
            insertBooking(requestedBookingId, instrCoachId, "REQUESTED");

            otherCoachBookingId = UUID.randomUUID();
            insertBooking(otherCoachBookingId, otherCoachId, "CONFIRMED");

            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM session.sessions WHERE booking_id = ?", confirmedBookingId);
            jdbcTemplate.update("DELETE FROM session.sessions WHERE booking_id = ?", requestedBookingId);
            jdbcTemplate.update("DELETE FROM session.sessions WHERE booking_id = ?", otherCoachBookingId);
            jdbcTemplate.update("DELETE FROM session.drills WHERE id = ?", drillId);
            jdbcTemplate.update("DELETE FROM booking.bookings WHERE id IN (?, ?, ?)",
                confirmedBookingId, requestedBookingId, otherCoachBookingId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_subscriptions WHERE coach_id IN (?, ?, ?)",
                instrCoachId, scoutCoachId, otherCoachId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE user_id IN (?, ?, ?)",
                INSTR_COACH_USER_ID, SCOUT_COACH_USER_ID, OTHER_COACH_USER_ID);
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?, ?)",
                INSTR_COACH_USER_ID, SCOUT_COACH_USER_ID, OTHER_COACH_USER_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?, ?)",
                INSTR_COACH_USER_ID, SCOUT_COACH_USER_ID, OTHER_COACH_USER_ID);
            jdbcTemplate.update("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void createSession_instructorCoach_validRequest_returns201WithSessionPlan() {
        String cookies = loginAndGetCookies(INSTR_EMAIL);
        Map<String, Object> body = buildCreateRequest(confirmedBookingId, drillId);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + SESSIONS_BASE,
            HttpMethod.POST,
            body,
            authenticatedHeaders(cookies),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("id")).isNotNull();
        assertThat(response.getBody().get("status")).isEqualTo("DRAFT");
    }

    @Test
    void createSession_scoutCoach_returns403FeatureGated() {
        String cookies = loginAndGetCookies(SCOUT_EMAIL);
        Map<String, Object> body = buildCreateRequest(confirmedBookingId, drillId);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + SESSIONS_BASE,
            HttpMethod.POST,
            body,
            authenticatedHeaders(cookies),
            Map.class
        )).isInstanceOf(HttpClientErrorException.Forbidden.class);
    }

    @Test
    void createSession_bookingOwnedByOtherCoach_returns403() {
        String cookies = loginAndGetCookies(INSTR_EMAIL);
        // Instructor coach tries to create a session for a booking owned by other coach
        Map<String, Object> body = buildCreateRequest(otherCoachBookingId, drillId);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + SESSIONS_BASE,
            HttpMethod.POST,
            body,
            authenticatedHeaders(cookies),
            Map.class
        )).isInstanceOf(HttpClientErrorException.Forbidden.class);
    }

    @Test
    void createSession_duplicateBookingId_returns403() {
        String cookies = loginAndGetCookies(INSTR_EMAIL);
        Map<String, Object> body = buildCreateRequest(confirmedBookingId, drillId);

        // First create succeeds
        httpTestClient.makeHttpRequest(
            baseUrl() + SESSIONS_BASE,
            HttpMethod.POST,
            body,
            authenticatedHeaders(cookies),
            Map.class
        );

        // Second create for same booking returns 403
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + SESSIONS_BASE,
            HttpMethod.POST,
            body,
            authenticatedHeaders(cookies),
            Map.class
        )).isInstanceOf(HttpClientErrorException.Forbidden.class);
    }

    @Test
    void createSession_bookingInRequestedStatus_returns403() {
        String cookies = loginAndGetCookies(INSTR_EMAIL);
        Map<String, Object> body = buildCreateRequest(requestedBookingId, drillId);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + SESSIONS_BASE,
            HttpMethod.POST,
            body,
            authenticatedHeaders(cookies),
            Map.class
        )).isInstanceOf(HttpClientErrorException.Forbidden.class);
    }

    @Test
    void createSession_emptyDevelopmentFocus_returns400() {
        String cookies = loginAndGetCookies(INSTR_EMAIL);
        Map<String, Object> body = Map.of(
            "bookingId", confirmedBookingId.toString(),
            "blocks", List.of(Map.of(
                "blockType", "WARM_UP",
                "blockName", "Warm-Up",
                "durationMinutes", 10,
                "drills", List.of()
            )),
            "developmentFocus", List.of()
        );

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + SESSIONS_BASE,
            HttpMethod.POST,
            body,
            authenticatedHeaders(cookies),
            Map.class
        )).isInstanceOf(HttpClientErrorException.BadRequest.class);
    }

    @Test
    void updateSession_instructorCoach_returnsUpdatedPlan() {
        String cookies = loginAndGetCookies(INSTR_EMAIL);
        Map<String, Object> createBody = buildCreateRequest(confirmedBookingId, drillId);

        ResponseEntity<Map> createResp = httpTestClient.makeHttpRequest(
            baseUrl() + SESSIONS_BASE,
            HttpMethod.POST,
            createBody,
            authenticatedHeaders(cookies),
            Map.class
        );
        String sessionId = (String) createResp.getBody().get("id");

        Map<String, Object> updateBody = Map.of(
            "blocks", List.of(Map.of(
                "blockType", "GAME_INTENSITY",
                "blockName", "Game",
                "durationMinutes", 30,
                "drills", List.of()
            )),
            "developmentFocus", List.of("physical"),
            "status", "SAVED"
        );

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + SESSIONS_BASE + "/" + sessionId,
            HttpMethod.PUT,
            updateBody,
            authenticatedHeaders(cookies),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("status")).isEqualTo("SAVED");
    }

    @Test
    void updateSession_completedSession_returns403WithHelpCodeSessionPlanLocked() {
        String cookies = loginAndGetCookies(INSTR_EMAIL);
        Map<String, Object> createBody = buildCreateRequest(confirmedBookingId, drillId);

        ResponseEntity<Map> createResp = httpTestClient.makeHttpRequest(
            baseUrl() + SESSIONS_BASE,
            HttpMethod.POST,
            createBody,
            authenticatedHeaders(cookies),
            Map.class
        );
        String sessionId = (String) createResp.getBody().get("id");

        // Force COMPLETED status directly in DB (must use transactionTemplate — auto-commit is disabled app-wide)
        transactionTemplate.execute(s -> {
            jdbcTemplate.update("UPDATE session.sessions SET status = 'COMPLETED' WHERE id = ?",
                UUID.fromString(sessionId));
            return null;
        });

        Map<String, Object> updateBody = Map.of(
            "blocks", List.of(Map.of(
                "blockType", "WARM_UP",
                "blockName", "Warm-Up",
                "durationMinutes", 10,
                "drills", List.of()
            )),
            "developmentFocus", List.of("technical")
        );

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + SESSIONS_BASE + "/" + sessionId,
            HttpMethod.PUT,
            updateBody,
            authenticatedHeaders(cookies),
            Map.class
        )).isInstanceOf(HttpClientErrorException.Forbidden.class);
    }

    @Test
    void getSession_ownerCoach_returns200() {
        String cookies = loginAndGetCookies(INSTR_EMAIL);
        Map<String, Object> createBody = buildCreateRequest(confirmedBookingId, drillId);

        ResponseEntity<Map> createResp = httpTestClient.makeHttpRequest(
            baseUrl() + SESSIONS_BASE,
            HttpMethod.POST,
            createBody,
            authenticatedHeaders(cookies),
            Map.class
        );
        String sessionId = (String) createResp.getBody().get("id");

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + SESSIONS_BASE + "/" + sessionId,
            HttpMethod.GET,
            null,
            authenticatedHeaders(cookies),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("id")).isEqualTo(sessionId);
    }

    @Test
    void getSession_otherCoach_returns404() {
        String instrCookies = loginAndGetCookies(INSTR_EMAIL);
        Map<String, Object> createBody = buildCreateRequest(confirmedBookingId, drillId);

        ResponseEntity<Map> createResp = httpTestClient.makeHttpRequest(
            baseUrl() + SESSIONS_BASE,
            HttpMethod.POST,
            createBody,
            authenticatedHeaders(instrCookies),
            Map.class
        );
        String sessionId = (String) createResp.getBody().get("id");

        // OTHER coach tries to get the session — must be 404, not 403
        String otherCookies = loginAndGetCookies(OTHER_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + SESSIONS_BASE + "/" + sessionId,
            HttpMethod.GET,
            null,
            authenticatedHeaders(otherCookies),
            Map.class
        )).isInstanceOf(HttpClientErrorException.NotFound.class);
    }

    @Test
    void getByBooking_existingSession_returns200() {
        String cookies = loginAndGetCookies(INSTR_EMAIL);
        Map<String, Object> createBody = buildCreateRequest(confirmedBookingId, drillId);

        httpTestClient.makeHttpRequest(
            baseUrl() + SESSIONS_BASE,
            HttpMethod.POST,
            createBody,
            authenticatedHeaders(cookies),
            Map.class
        );

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + SESSIONS_BASE + "/by-booking/" + confirmedBookingId,
            HttpMethod.GET,
            null,
            authenticatedHeaders(cookies),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("bookingId")).isEqualTo(confirmedBookingId.toString());
    }

    @Test
    void getByBooking_noSession_returns404() {
        String cookies = loginAndGetCookies(INSTR_EMAIL);
        UUID unknownBookingId = UUID.randomUUID();

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + SESSIONS_BASE + "/by-booking/" + unknownBookingId,
            HttpMethod.GET,
            null,
            authenticatedHeaders(cookies),
            Map.class
        )).isInstanceOf(HttpClientErrorException.NotFound.class);
    }

    private Map<String, Object> buildCreateRequest(UUID bookingId, UUID aDrillId) {
        return Map.of(
            "bookingId", bookingId.toString(),
            "blocks", List.of(Map.of(
                "blockType", "WARM_UP",
                "blockName", "Warm-Up",
                "durationMinutes", 10,
                "drills", List.of(Map.of("drillId", aDrillId.toString(), "order", 0))
            )),
            "developmentFocus", List.of("technical")
        );
    }

    private void insertBooking(UUID bookingId, UUID coachId, String status) {
        jdbcTemplate.update(
            "INSERT INTO booking.bookings (id, parent_id, player_id, coach_id, " +
            "requested_start_time, requested_end_time, status, canonical_timezone, " +
            "version, created_at, updated_at) " +
            "VALUES (?, 1, 1, ?, ?, ?, ?, 'Europe/London', 0, ?, ?)",
            bookingId, coachId,
            Timestamp.from(Instant.now().plusSeconds(86400)),
            Timestamp.from(Instant.now().plusSeconds(90000)),
            status,
            Timestamp.from(Instant.now()),
            Timestamp.from(Instant.now())
        );
    }
}
