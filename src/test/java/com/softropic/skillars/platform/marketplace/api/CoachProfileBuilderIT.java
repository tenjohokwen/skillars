package com.softropic.skillars.platform.marketplace.api;

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
class CoachProfileBuilderIT {

    private static final String LOGIN_ENDPOINT    = "/api/auth/login";
    private static final String PROFILE_BASE      = "/api/marketplace/coaches/me/profile";
    private static final String CLIENT_ID         = "testClientId";
    private static final String TEST_PASSWORD     = "CoachPass@123!";

    private static final long COACH_ID = 9100000001L;
    private static final long PARENT_ID = 9100000002L;
    private static final String COACH_EMAIL  = "coach.builder@skillars-test.com";
    private static final String PARENT_EMAIL = "parent.builder@skillars-test.com";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private HttpTestClient httpTestClient;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @LocalServerPort
    private int randomServerPort;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (9100, 'ROLE_COACH', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now())
            );
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (9101, 'ROLE_PARENT', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
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
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("DELETE FROM marketplace.coach_subscriptions");
            jdbcTemplate.execute("DELETE FROM marketplace.coach_availability_windows");
            jdbcTemplate.execute("DELETE FROM marketplace.session_packs");
            jdbcTemplate.execute("DELETE FROM marketplace.coach_pricing");
            jdbcTemplate.execute("DELETE FROM marketplace.coach_age_groups");
            jdbcTemplate.execute("DELETE FROM marketplace.coach_specialties");
            jdbcTemplate.execute("DELETE FROM marketplace.coach_profiles");
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.execute("DELETE FROM main.user_authority WHERE user_id IN (" + COACH_ID + "," + PARENT_ID + ")");
            jdbcTemplate.execute("DELETE FROM main.\"user\" WHERE id IN (" + COACH_ID + "," + PARENT_ID + ")");
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id IN (9100, 9101)");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void saveStep1_validRequest_returns200AndDraftCreated() {
        String cookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/steps/1",
            HttpMethod.PUT,
            step1Payload("John Coach", "I coach youth football", "Berlin", "Mitte",
                List.of("English", "German"), "Europe/Berlin"),
            authenticatedHeaders(cookies),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKey("coachId");
        assertThat(response.getBody().get("stepSaved")).isEqualTo(1);
        assertThat(response.getBody().get("nextStep")).isEqualTo(2);
    }

    @Test
    void saveStep1_bioWithEmail_sanitizedOnPersistence() {
        String cookies = loginAndGetCookies(COACH_EMAIL);

        httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/steps/1",
            HttpMethod.PUT,
            step1Payload("John Coach", "Contact me at john@example.com", "Berlin", null,
                List.of("English"), "Europe/Berlin"),
            authenticatedHeaders(cookies),
            Map.class
        );

        String storedBio = jdbcTemplate.queryForObject(
            "SELECT bio FROM marketplace.coach_profiles WHERE user_id = ?", String.class, COACH_ID);
        assertThat(storedBio).doesNotContain("john@example.com");
        assertThat(storedBio).contains("[contact details removed]");
    }

    @Test
    void saveStep1_missingDisplayName_returns400() {
        String cookies = loginAndGetCookies(COACH_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/steps/1",
            HttpMethod.PUT,
            Map.of("bio", "bio", "languages", List.of("English"), "canonicalTimezone", "Europe/Berlin"),
            authenticatedHeaders(cookies),
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void saveStep2_validRequest_returns200() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        saveStep1(cookies);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/steps/2",
            HttpMethod.PUT,
            Map.of("specialties", List.of("Dribbling", "Shooting"), "ageGroups", List.of("U10", "AGE_10_12")),
            authenticatedHeaders(cookies),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("stepSaved")).isEqualTo(2);
    }

    @Test
    void saveStep2_noSpecialties_returns400() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        saveStep1(cookies);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/steps/2",
            HttpMethod.PUT,
            Map.of("specialties", List.of(), "ageGroups", List.of("U10")),
            authenticatedHeaders(cookies),
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void saveStep3_validRequest_returns200() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        saveStep1(cookies);
        saveStep2(cookies);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/steps/3",
            HttpMethod.PUT,
            Map.of("perSessionPrice", 50.0),
            authenticatedHeaders(cookies),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("stepSaved")).isEqualTo(3);
    }

    @Test
    void saveStep3_missingPerSessionPrice_returns400() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        saveStep1(cookies);
        saveStep2(cookies);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/steps/3",
            HttpMethod.PUT,
            Map.of("sessionPacks", List.of()),
            authenticatedHeaders(cookies),
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void saveStep4_validRequest_returns200() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        saveStep1(cookies);
        saveStep2(cookies);
        saveStep3(cookies);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/steps/4",
            HttpMethod.PUT,
            Map.of("windows", List.of(Map.of("dayOfWeek", 1, "startTime", "09:00:00", "endTime", "11:00:00",
                "canonicalTimezone", "Europe/Berlin"))),
            authenticatedHeaders(cookies),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("stepSaved")).isEqualTo(4);
    }

    @Test
    void saveStep4_noWindows_returns400() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        saveStep1(cookies);
        saveStep2(cookies);
        saveStep3(cookies);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/steps/4",
            HttpMethod.PUT,
            Map.of("windows", List.of()),
            authenticatedHeaders(cookies),
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void saveStep5_withPhoto_returns200() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        saveStep1(cookies);
        saveStep2(cookies);
        saveStep3(cookies);
        saveStep4(cookies);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/steps/5",
            HttpMethod.PUT,
            Map.of("photoUrl", "coach_profile/9100000001/2026/06/test-photo.jpg"),
            authenticatedHeaders(cookies),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("stepSaved")).isEqualTo(5);
    }

    @Test
    void saveStep5_skip_returns200WithNullPhotoUrl() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        saveStep1(cookies);
        saveStep2(cookies);
        saveStep3(cookies);
        saveStep4(cookies);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/steps/5",
            HttpMethod.PUT,
            Map.of(),
            authenticatedHeaders(cookies),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void publishProfile_allStepsComplete_returnsActiveStatus() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        saveAllSteps(cookies);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/publish",
            HttpMethod.POST,
            null,
            authenticatedHeaders(cookies),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("status")).isEqualTo("ACTIVE");
        assertThat(response.getBody().get("profileComplete")).isEqualTo(true);
    }

    @Test
    void publishProfile_missingStep_returns422() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        // Only save step 1 — missing step 2, 3, 4
        saveStep1(cookies);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/publish",
            HttpMethod.POST,
            null,
            authenticatedHeaders(cookies),
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void getStatus_noDraft_returnsStep0() {
        String cookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/status",
            HttpMethod.GET,
            null,
            authenticatedHeaders(cookies),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("lastCompletedStep")).isEqualTo(0);
        assertThat(response.getBody().get("profileComplete")).isEqualTo(false);
    }

    @Test
    void getStatus_afterStep2_returnsLastCompletedStep2() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        saveStep1(cookies);
        saveStep2(cookies);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/status",
            HttpMethod.GET,
            null,
            authenticatedHeaders(cookies),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("lastCompletedStep")).isEqualTo(2);
    }

    @Test
    void saveStep1_unauthenticated_returns401() {
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/steps/1",
            HttpMethod.PUT,
            step1Payload("John", null, null, null, List.of("English"), "UTC"),
            clientHeaders(),
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN));
    }

    @Test
    void saveStep1_asParent_returns403() {
        String cookies = loginAndGetCookies(PARENT_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/steps/1",
            HttpMethod.PUT,
            step1Payload("John", null, null, null, List.of("English"), "UTC"),
            authenticatedHeaders(cookies),
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ---- helpers ----

    private void saveStep1(String cookies) {
        httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/steps/1",
            HttpMethod.PUT,
            step1Payload("Coach Name", "Bio text", "Berlin", "Mitte", List.of("English"), "Europe/Berlin"),
            authenticatedHeaders(cookies),
            Map.class
        );
    }

    private void saveStep2(String cookies) {
        httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/steps/2",
            HttpMethod.PUT,
            Map.of("specialties", List.of("Dribbling"), "ageGroups", List.of("ADULT")),
            authenticatedHeaders(cookies),
            Map.class
        );
    }

    private void saveStep3(String cookies) {
        httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/steps/3",
            HttpMethod.PUT,
            Map.of("perSessionPrice", 50.0),
            authenticatedHeaders(cookies),
            Map.class
        );
    }

    private void saveStep4(String cookies) {
        httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/steps/4",
            HttpMethod.PUT,
            Map.of("windows", List.of(Map.of("dayOfWeek", 1, "startTime", "09:00:00", "endTime", "11:00:00",
                "canonicalTimezone", "Europe/Berlin"))),
            authenticatedHeaders(cookies),
            Map.class
        );
    }

    private void saveAllSteps(String cookies) {
        saveStep1(cookies);
        saveStep2(cookies);
        saveStep3(cookies);
        saveStep4(cookies);
        httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/steps/5",
            HttpMethod.PUT,
            Map.of(),
            authenticatedHeaders(cookies),
            Map.class
        );
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

    private Map<String, Object> step1Payload(String displayName, String bio, String city, String district,
                                              List<String> languages, String timezone) {
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        if (displayName != null) payload.put("displayName", displayName);
        if (bio != null) payload.put("bio", bio);
        if (city != null) payload.put("city", city);
        if (district != null) payload.put("district", district);
        if (languages != null) payload.put("languages", languages);
        if (timezone != null) payload.put("canonicalTimezone", timezone);
        return payload;
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
            "670" + (id % 10000000),
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
            "670" + (id % 10000000 + 1),
            email, passwordHash
        );
    }
}
