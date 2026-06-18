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
class SessionTemplateResourceIT extends BaseSessionIT {

    private static final String TEMPLATES_BASE = "/api/session/templates";

    private static final long INSTR_COACH_USER_ID = 9580000010L;
    private static final long SCOUT_COACH_USER_ID = 9580000020L;
    private static final long OTHER_COACH_USER_ID = 9580000030L;

    private static final String INSTR_EMAIL = "instr.template@skillars-test.com";
    private static final String SCOUT_EMAIL = "scout.template@skillars-test.com";
    private static final String OTHER_EMAIL = "other.template@skillars-test.com";

    private UUID instrCoachId;
    private UUID scoutCoachId;
    private UUID otherCoachId;

    private UUID confirmedBookingId;
    private UUID otherCoachBookingId;
    private UUID cancelledBookingId;
    private UUID drillId;
    private UUID sessionId;

    @MockitoBean
    VideoProviderAdapter videoProviderAdapter;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (9580, 'ROLE_COACH', 'ACTIVE', 'system', NOW()) ON CONFLICT (name) DO NOTHING"
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
            insertDrill(drillId, "Template Drill", "PLATFORM", null, "ACTIVE");

            confirmedBookingId = UUID.randomUUID();
            insertBooking(confirmedBookingId, instrCoachId, "CONFIRMED");

            otherCoachBookingId = UUID.randomUUID();
            insertBooking(otherCoachBookingId, otherCoachId, "CONFIRMED");

            cancelledBookingId = UUID.randomUUID();
            insertBooking(cancelledBookingId, instrCoachId, "CANCELLED");

            // Create a saved session to use as template source
            sessionId = UUID.randomUUID();
            insertSession(sessionId, instrCoachId);

            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM session.session_templates WHERE coach_id IN (?, ?)",
                instrCoachId, otherCoachId);
            jdbcTemplate.update("DELETE FROM session.sessions WHERE booking_id IN (?, ?, ?)",
                confirmedBookingId, otherCoachBookingId, cancelledBookingId);
            jdbcTemplate.update("DELETE FROM session.sessions WHERE id = ?", sessionId);
            jdbcTemplate.update("DELETE FROM session.drills WHERE id = ?", drillId);
            jdbcTemplate.update("DELETE FROM booking.bookings WHERE id IN (?, ?, ?)",
                confirmedBookingId, otherCoachBookingId, cancelledBookingId);
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

    // --- createTemplate ---

    @Test
    void createTemplate_instructorCoach_validSessionId_returns201WithTemplate() {
        String cookies = loginAndGetCookies(INSTR_EMAIL);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + TEMPLATES_BASE,
            HttpMethod.POST,
            Map.of("sessionId", sessionId.toString(), "name", "My First Template"),
            authenticatedHeaders(cookies),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("id")).isNotNull();
        assertThat(response.getBody().get("name")).isEqualTo("My First Template");
    }

    @Test
    void createTemplate_scoutCoach_returns403FeatureGated() {
        String cookies = loginAndGetCookies(SCOUT_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + TEMPLATES_BASE,
            HttpMethod.POST,
            Map.of("sessionId", sessionId.toString(), "name", "Scout Template"),
            authenticatedHeaders(cookies),
            Map.class
        )).isInstanceOf(HttpClientErrorException.Forbidden.class);
    }

    @Test
    void createTemplate_sessionOwnedByOtherCoach_returns404() {
        // Create a session owned by otherCoach
        UUID otherSession = UUID.randomUUID();
        transactionTemplate.execute(s -> {
            insertSession(otherSession, otherCoachId);
            return null;
        });

        String cookies = loginAndGetCookies(INSTR_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + TEMPLATES_BASE,
            HttpMethod.POST,
            Map.of("sessionId", otherSession.toString(), "name", "Stolen Template"),
            authenticatedHeaders(cookies),
            Map.class
        )).isInstanceOf(HttpClientErrorException.NotFound.class);

        transactionTemplate.execute(s -> {
            jdbcTemplate.update("DELETE FROM session.sessions WHERE id = ?", otherSession);
            return null;
        });
    }

    // --- listTemplates ---

    @Test
    void listTemplates_emptyVault_returns200EmptyList() {
        String cookies = loginAndGetCookies(INSTR_EMAIL);

        ResponseEntity<List> response = httpTestClient.makeHttpRequest(
            baseUrl() + TEMPLATES_BASE,
            HttpMethod.GET,
            null,
            authenticatedHeaders(cookies),
            List.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    void listTemplates_scoutCoach_returns403FeatureGated() {
        String cookies = loginAndGetCookies(SCOUT_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + TEMPLATES_BASE,
            HttpMethod.GET,
            null,
            authenticatedHeaders(cookies),
            List.class
        )).isInstanceOf(HttpClientErrorException.Forbidden.class);
    }

    @Test
    void listTemplates_afterCreate_returnsOneTemplate() {
        String cookies = loginAndGetCookies(INSTR_EMAIL);
        createTemplateViaApi(cookies, sessionId, "Listed Template");

        ResponseEntity<List> response = httpTestClient.makeHttpRequest(
            baseUrl() + TEMPLATES_BASE,
            HttpMethod.GET,
            null,
            authenticatedHeaders(cookies),
            List.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
    }

    // --- renameTemplate ---

    @Test
    void renameTemplate_ownerCoach_returns204() {
        String cookies = loginAndGetCookies(INSTR_EMAIL);
        String templateId = createTemplateViaApi(cookies, sessionId, "Old Name");

        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + TEMPLATES_BASE + "/" + templateId,
            HttpMethod.PUT,
            Map.of("name", "New Name"),
            authenticatedHeaders(cookies),
            Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void renameTemplate_otherCoach_returns403() {
        String instrCookies = loginAndGetCookies(INSTR_EMAIL);
        String templateId = createTemplateViaApi(instrCookies, sessionId, "Other Coach Rename");

        String otherCookies = loginAndGetCookies(OTHER_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + TEMPLATES_BASE + "/" + templateId,
            HttpMethod.PUT,
            Map.of("name", "Hijacked"),
            authenticatedHeaders(otherCookies),
            Void.class
        )).isInstanceOf(HttpClientErrorException.Forbidden.class);
    }

    @Test
    void renameTemplate_scoutCoach_returns403FeatureGated() {
        String instrCookies = loginAndGetCookies(INSTR_EMAIL);
        String templateId = createTemplateViaApi(instrCookies, sessionId, "Scout Rename");

        String scoutCookies = loginAndGetCookies(SCOUT_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + TEMPLATES_BASE + "/" + templateId,
            HttpMethod.PUT,
            Map.of("name", "Scout"),
            authenticatedHeaders(scoutCookies),
            Void.class
        )).isInstanceOf(HttpClientErrorException.Forbidden.class);
    }

    @Test
    void renameTemplate_archivedTemplate_returns403() {
        String cookies = loginAndGetCookies(INSTR_EMAIL);
        String templateId = createTemplateViaApi(cookies, sessionId, "To Archive");

        // Delete (archive) it
        httpTestClient.makeHttpRequest(
            baseUrl() + TEMPLATES_BASE + "/" + templateId,
            HttpMethod.DELETE,
            null,
            authenticatedHeaders(cookies),
            Void.class
        );

        // Now try to rename the archived template
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + TEMPLATES_BASE + "/" + templateId,
            HttpMethod.PUT,
            Map.of("name", "Renamed Archived"),
            authenticatedHeaders(cookies),
            Void.class
        )).isInstanceOf(HttpClientErrorException.Forbidden.class);
    }

    // --- deleteTemplate ---

    @Test
    void deleteTemplate_ownerCoach_returns204_disappearsFromList() {
        String cookies = loginAndGetCookies(INSTR_EMAIL);
        String templateId = createTemplateViaApi(cookies, sessionId, "To Delete");

        ResponseEntity<Void> deleteResp = httpTestClient.makeHttpRequest(
            baseUrl() + TEMPLATES_BASE + "/" + templateId,
            HttpMethod.DELETE,
            null,
            authenticatedHeaders(cookies),
            Void.class
        );
        assertThat(deleteResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<List> listResp = httpTestClient.makeHttpRequest(
            baseUrl() + TEMPLATES_BASE,
            HttpMethod.GET,
            null,
            authenticatedHeaders(cookies),
            List.class
        );
        assertThat(listResp.getBody()).isEmpty();
    }

    @Test
    void deleteTemplate_otherCoach_returns403() {
        String instrCookies = loginAndGetCookies(INSTR_EMAIL);
        String templateId = createTemplateViaApi(instrCookies, sessionId, "Other Delete");

        String otherCookies = loginAndGetCookies(OTHER_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + TEMPLATES_BASE + "/" + templateId,
            HttpMethod.DELETE,
            null,
            authenticatedHeaders(otherCookies),
            Void.class
        )).isInstanceOf(HttpClientErrorException.Forbidden.class);
    }

    @Test
    void deleteTemplate_scoutCoach_returns403FeatureGated() {
        String instrCookies = loginAndGetCookies(INSTR_EMAIL);
        String templateId = createTemplateViaApi(instrCookies, sessionId, "Scout Delete");

        String scoutCookies = loginAndGetCookies(SCOUT_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + TEMPLATES_BASE + "/" + templateId,
            HttpMethod.DELETE,
            null,
            authenticatedHeaders(scoutCookies),
            Void.class
        )).isInstanceOf(HttpClientErrorException.Forbidden.class);
    }

    // --- deployTemplate ---

    @Test
    void deployTemplate_validTemplate_validBooking_returns201WithSessionPlan() {
        String cookies = loginAndGetCookies(INSTR_EMAIL);
        String templateId = createTemplateViaApi(cookies, sessionId, "Deploy Template");

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + TEMPLATES_BASE + "/" + templateId + "/deploy?bookingId=" + confirmedBookingId,
            HttpMethod.POST,
            null,
            authenticatedHeaders(cookies),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("status")).isEqualTo("DRAFT");
        assertThat(response.getBody().get("sourceTemplateName")).isEqualTo("Deploy Template");
    }

    @Test
    void deployTemplate_cancelledBooking_returns403SessionBookingNotOwned() {
        String cookies = loginAndGetCookies(INSTR_EMAIL);
        String templateId = createTemplateViaApi(cookies, sessionId, "Deploy Cancelled");

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + TEMPLATES_BASE + "/" + templateId + "/deploy?bookingId=" + cancelledBookingId,
            HttpMethod.POST,
            null,
            authenticatedHeaders(cookies),
            Map.class
        )).isInstanceOf(HttpClientErrorException.Forbidden.class);
    }

    @Test
    void deployTemplate_duplicateBooking_returns403SessionAlreadyExists() {
        String cookies = loginAndGetCookies(INSTR_EMAIL);
        String templateId = createTemplateViaApi(cookies, sessionId, "Deploy Dup");

        // First deploy
        httpTestClient.makeHttpRequest(
            baseUrl() + TEMPLATES_BASE + "/" + templateId + "/deploy?bookingId=" + confirmedBookingId,
            HttpMethod.POST,
            null,
            authenticatedHeaders(cookies),
            Map.class
        );

        // Second deploy to same booking
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + TEMPLATES_BASE + "/" + templateId + "/deploy?bookingId=" + confirmedBookingId,
            HttpMethod.POST,
            null,
            authenticatedHeaders(cookies),
            Map.class
        )).isInstanceOf(HttpClientErrorException.Forbidden.class);
    }

    @Test
    void deployTemplate_scoutCoach_returns403FeatureGated() {
        String instrCookies = loginAndGetCookies(INSTR_EMAIL);
        String templateId = createTemplateViaApi(instrCookies, sessionId, "Scout Deploy");

        String scoutCookies = loginAndGetCookies(SCOUT_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + TEMPLATES_BASE + "/" + templateId + "/deploy?bookingId=" + confirmedBookingId,
            HttpMethod.POST,
            null,
            authenticatedHeaders(scoutCookies),
            Map.class
        )).isInstanceOf(HttpClientErrorException.Forbidden.class);
    }

    @Test
    void deployTemplate_otherCoach_returns403TemplateNotOwned() {
        String instrCookies = loginAndGetCookies(INSTR_EMAIL);
        String templateId = createTemplateViaApi(instrCookies, sessionId, "Other Deploy");

        String otherCookies = loginAndGetCookies(OTHER_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + TEMPLATES_BASE + "/" + templateId + "/deploy?bookingId=" + otherCoachBookingId,
            HttpMethod.POST,
            null,
            authenticatedHeaders(otherCookies),
            Map.class
        )).isInstanceOf(HttpClientErrorException.Forbidden.class);
    }

    // --- helpers ---

    private String createTemplateViaApi(String cookies, UUID srcSessionId, String name) {
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + TEMPLATES_BASE,
            HttpMethod.POST,
            Map.of("sessionId", srcSessionId.toString(), "name", name),
            authenticatedHeaders(cookies),
            Map.class
        );
        return (String) response.getBody().get("id");
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

    private void insertSession(UUID id, UUID coachId) {
        jdbcTemplate.update(
            "INSERT INTO session.sessions (id, booking_id, coach_id, player_id, blocks, " +
            "equipment_list, development_focus, status, created_at, updated_at) " +
            "VALUES (?, gen_random_uuid(), ?, 1, '[]'::jsonb, '[]'::jsonb, '[\"technical\"]'::jsonb, 'SAVED', NOW(), NOW())",
            id, coachId
        );
    }
}
