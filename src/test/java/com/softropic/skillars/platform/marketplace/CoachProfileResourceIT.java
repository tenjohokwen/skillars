package com.softropic.skillars.platform.marketplace;

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
class CoachProfileResourceIT {

    private static final String PROFILE_BASE  = "/api/marketplace/coaches/";
    private static final String CLIENT_ID     = "testClientId";

    // Unique ID ranges to avoid conflicts with other ITs
    private static final long ACTIVE_COACH_ID = 9300000001L;
    private static final long DRAFT_COACH_ID  = 9300000002L;

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;

    @LocalServerPort private int randomServerPort;

    private UUID activeCoachProfileId;
    private UUID draftCoachProfileId;

    @BeforeEach
    void setUp() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (9300, 'ROLE_COACH', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now())
            );

            insertCoachUser(ACTIVE_COACH_ID, "profile.active@skillars-test.com");
            insertCoachUser(DRAFT_COACH_ID,  "profile.draft@skillars-test.com");

            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "SELECT u.id, a.id FROM main.\"user\" u, main.authority a " +
                "WHERE u.id IN (?,?) AND a.name = 'ROLE_COACH' ON CONFLICT DO NOTHING",
                ACTIVE_COACH_ID, DRAFT_COACH_ID
            );

            activeCoachProfileId = insertCoachProfile(ACTIVE_COACH_ID, "Active Coach", "Frankfurt", "Sachsenhausen", "ACTIVE", "TRUSTED");
            draftCoachProfileId  = insertCoachProfile(DRAFT_COACH_ID,  "Draft Coach",  "Frankfurt", null,            "DRAFT",  "BASIC");

            // Pricing for active coach
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_pricing (coach_id, per_session_price, currency) VALUES (?, 50.00, 'EUR')",
                activeCoachProfileId
            );

            // Availability window for active coach (makes available = true)
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_availability_windows (id, coach_id, day_of_week, start_time, end_time, canonical_timezone) " +
                "VALUES (gen_random_uuid(), ?, 1, '09:00', '12:00', 'Europe/Berlin')",
                activeCoachProfileId
            );

            // Specialties
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_specialties (id, coach_id, skill) VALUES (gen_random_uuid(), ?, 'Dribbling')",
                activeCoachProfileId
            );

            // Age groups
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_age_groups (id, coach_id, age_tier) VALUES (gen_random_uuid(), ?, 'U10')",
                activeCoachProfileId
            );

            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("DELETE FROM marketplace.coach_media");
            jdbcTemplate.execute("DELETE FROM marketplace.coach_reliability_strikes");
            jdbcTemplate.execute("DELETE FROM marketplace.coach_subscriptions");
            jdbcTemplate.execute("DELETE FROM marketplace.coach_availability_windows");
            jdbcTemplate.execute("DELETE FROM marketplace.session_packs");
            jdbcTemplate.execute("DELETE FROM marketplace.coach_pricing");
            jdbcTemplate.execute("DELETE FROM marketplace.coach_age_groups");
            jdbcTemplate.execute("DELETE FROM marketplace.coach_specialties");
            jdbcTemplate.execute("DELETE FROM marketplace.coach_profiles");
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?,?)",
                ACTIVE_COACH_ID, DRAFT_COACH_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?,?)",
                ACTIVE_COACH_ID, DRAFT_COACH_ID);
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id = 9300");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    // ======================== TEST CASES ========================

    @Test
    void getCoachProfile_activeCoach_returns200WithFullDto() {
        ResponseEntity<Map> response = getProfile(activeCoachProfileId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("id")).isEqualTo(activeCoachProfileId.toString());
        assertThat(body.get("displayName")).isEqualTo("Active Coach");
        assertThat(body.get("verificationTier")).isEqualTo("TRUSTED");
        assertThat(body.get("city")).isEqualTo("Frankfurt");
        assertThat(body.get("district")).isEqualTo("Sachsenhausen");
    }

    @Test
    void getCoachProfile_unknownId_returns404() {
        assertThatThrownBy(() -> getProfile(UUID.randomUUID()))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void getCoachProfile_draftCoach_returns404() {
        assertThatThrownBy(() -> getProfile(draftCoachProfileId))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void getCoachProfile_unauthenticated_returns200() {
        HttpHeaders anonHeaders = new HttpHeaders();
        anonHeaders.setAccept(List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + activeCoachProfileId,
            HttpMethod.GET, null, anonHeaders, Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("id")).isEqualTo(activeCoachProfileId.toString());
    }

    @Test
    void getCoachProfile_capabilityBadges_emptyAtThisStage() {
        ResponseEntity<Map> response = getProfile(activeCoachProfileId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> badges = (List<?>) response.getBody().get("capabilityBadges");
        assertThat(badges).isNotNull().isEmpty();
    }

    @Test
    void getCoachProfile_aggregateRating_stubZero() {
        ResponseEntity<Map> response = getProfile(activeCoachProfileId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) response.getBody().get("averageRating")).doubleValue()).isEqualTo(0.0);
        assertThat(((Integer) response.getBody().get("reviewCount"))).isEqualTo(0);
    }

    @Test
    void getCoachProfile_availabilityWindow_reflectsAvailable() {
        ResponseEntity<Map> response = getProfile(activeCoachProfileId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("available")).isEqualTo(true);
    }

    @Test
    void getCoachProfile_mediaGallery_emptyWhenNoneUploaded() {
        ResponseEntity<Map> response = getProfile(activeCoachProfileId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> gallery = (List<?>) response.getBody().get("mediaGallery");
        assertThat(gallery).isNotNull().isEmpty();
    }

    // ======================== HELPERS ========================

    private ResponseEntity<Map> getProfile(UUID coachId) {
        return httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + coachId,
            HttpMethod.GET, null, clientHeaders(), Map.class
        );
    }

    private UUID insertCoachProfile(long userId, String displayName, String city, String district,
                                    String status, String tier) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO marketplace.coach_profiles " +
            "(id, user_id, display_name, bio, city, district, canonical_timezone, status, verification_tier, created_at) " +
            "VALUES (?, ?, ?, 'Test bio', ?, ?, 'Europe/Berlin', ?, ?, now())",
            id, userId, displayName, city, district, status, tier
        );
        return id;
    }

    private void insertCoachUser(long id, String email) {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" " +
            "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, " +
            "status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, " +
            "activated, locked, login, login_id_type, password_hash, otp_enabled, " +
            "skillars_role, verification_status) " +
            "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, " +
            "'ACTIVE', '1990-03-15', ?, 'Test', 'OTHER', 'en', 'Coach', 'DE', ?, " +
            "true, false, ?, 'EMAIL', 'hash', false, " +
            "'COACH', 'BASIC_VERIFIED')",
            id,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            email,
            "671" + (id % 10000000),
            email
        );
    }

    private HttpHeaders clientHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(SecurityConstants.API_KEY_HEADER, CLIENT_ID);
        return headers;
    }

    private String baseUrl() {
        return "http://localhost:" + randomServerPort;
    }
}
