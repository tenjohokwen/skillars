package com.softropic.skillars.platform.development.api;

import com.softropic.skillars.config.TestConfig;
import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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
class RadarDisplayResourceIT {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String CLIENT_ID      = "testClientId";
    private static final String TEST_PASSWORD  = "TestPass@123!";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private RestTemplate restTemplate;
    @Autowired private PasswordEncoder passwordEncoder;
    @LocalServerPort private int randomServerPort;

    @MockitoBean
    VideoProviderAdapter videoProviderAdapter;

    private static final long COACH_USER_ID   = 9590000001L;
    private static final long ACADEMY_USER_ID = 9590000002L;
    private static final long PARENT_USER_ID  = 9590000010L;
    private static final long PLAYER_ID       = 9590000020L;

    private static final String COACH_EMAIL   = "coach.display@skillars-test.com";
    private static final String ACADEMY_EMAIL = "academy.display@skillars-test.com";
    private static final String PARENT_EMAIL  = "parent.display@skillars-test.com";

    private UUID coachProfileId;
    private UUID academyProfileId;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        transactionTemplate.execute(status -> {
            insertAuthority(9590, "ROLE_COACH");
            insertAuthority(9591, "ROLE_PARENT");

            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH");
            grantRole(COACH_USER_ID, "ROLE_COACH");
            coachProfileId = insertCoachProfile(COACH_USER_ID);
            insertSubscription(coachProfileId, "INSTRUCTOR");

            insertUser(ACADEMY_USER_ID, ACADEMY_EMAIL, passwordHash, "COACH");
            grantRole(ACADEMY_USER_ID, "ROLE_COACH");
            academyProfileId = insertCoachProfile(ACADEMY_USER_ID);
            insertSubscription(academyProfileId, "ACADEMY");

            insertUser(PARENT_USER_ID, PARENT_EMAIL, passwordHash, "PARENT");
            grantRole(PARENT_USER_ID, "ROLE_PARENT");

            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Display Player', ?, 'MIDFIELDER', 'AGE_10_12', ?, false, ?, 'system')",
                PLAYER_ID, Date.valueOf(LocalDate.now().minusYears(10)),
                PARENT_USER_ID, Timestamp.from(Instant.now())
            );
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM development.player_radar_baselines WHERE player_id = ?", PLAYER_ID);
            jdbcTemplate.update("DELETE FROM development.coach_radar_preferences WHERE player_id = ?", PLAYER_ID);
            jdbcTemplate.update("DELETE FROM development.player_radar_composites WHERE player_id = ?", PLAYER_ID);
            jdbcTemplate.update("DELETE FROM development.radar_assessment_entries WHERE player_id = ?", PLAYER_ID);
            jdbcTemplate.update("DELETE FROM development.player_skill_stats WHERE player_id = ?", PLAYER_ID);
            jdbcTemplate.update("DELETE FROM main.player_profiles WHERE id = ?", PLAYER_ID);
            jdbcTemplate.update("DELETE FROM marketplace.coach_subscriptions WHERE coach_id IN (?, ?)",
                coachProfileId, academyProfileId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id IN (?, ?)",
                coachProfileId, academyProfileId);
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?, ?)",
                COACH_USER_ID, ACADEMY_USER_ID, PARENT_USER_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?, ?)",
                COACH_USER_ID, ACADEMY_USER_ID, PARENT_USER_ID);
            jdbcTemplate.update("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void getDisplay_asCoach_returns200WithAllSkills() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            displayUrl(), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> skills = (List<?>) response.getBody().get("skills");
        assertThat(skills).isNotNull();
        assertThat(skills).hasSizeGreaterThanOrEqualTo(1);
    }

    @Test
    void getDisplay_asParent_returns200() {
        String cookies = loginAndGetCookies(PARENT_EMAIL);
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            displayUrl(), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getDisplay_asParentForOtherPlayersChild_returns403() {
        // Insert another parent who does NOT own PLAYER_ID
        long otherParentId = 9590000011L;
        String otherEmail  = "other.parent.display@skillars-test.com";
        transactionTemplate.execute(status -> {
            insertUser(otherParentId, otherEmail, passwordEncoder.encode(TEST_PASSWORD), "PARENT");
            grantRole(otherParentId, "ROLE_PARENT");
            return null;
        });
        try {
            String cookies = loginAndGetCookies(otherEmail);
            assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
                displayUrl(), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
        } finally {
            transactionTemplate.execute(status -> {
                jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id = ?", otherParentId);
                jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id = ?", otherParentId);
                return null;
            });
        }
    }

    @Test
    void getDisplay_unauthenticated_returns401() {
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            displayUrl(), HttpMethod.GET, null, clientHeaders(), Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getDisplay_newPlayer_returnsAllNullScores() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            displayUrl(), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> skills = (List<Map<String, Object>>) response.getBody().get("skills");
        assertThat(skills).isNotEmpty();
        assertThat(skills).allSatisfy(s -> {
            assertThat(s.get("compositeScore")).isNull();
            assertThat(s.get("baselineScore")).isNull();
            assertThat(s.get("entryCount")).isNull();
            assertThat(s.get("lastUpdatedAt")).isNull();
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void getDisplay_compositeAndBaselinePresent_returnsBothScores() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO development.player_radar_composites " +
                "(player_id, skill_code, composite_score, entry_count, last_updated_at) " +
                "VALUES (?, 'PAC', 65.00, 1, NOW()) ON CONFLICT DO NOTHING",
                PLAYER_ID
            );
            jdbcTemplate.update(
                "INSERT INTO development.player_radar_baselines " +
                "(player_id, skill_code, baseline_score, recorded_at) " +
                "VALUES (?, 'PAC', 50.00, NOW()) ON CONFLICT DO NOTHING",
                PLAYER_ID
            );
            return null;
        });

        String cookies = loginAndGetCookies(COACH_EMAIL);
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            displayUrl(), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> skills = (List<Map<String, Object>>) response.getBody().get("skills");
        Map<String, Object> pac = skills.stream()
            .filter(s -> "PAC".equals(s.get("skillCode"))).findFirst().orElseThrow();
        assertThat(((Number) pac.get("compositeScore")).doubleValue()).isEqualTo(65.0);
        assertThat(((Number) pac.get("baselineScore")).doubleValue()).isEqualTo(50.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void savePreferences_persistsSelectedSkills() {
        String cookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Void> putResponse = httpTestClient.makeHttpRequest(
            preferencesUrl(), HttpMethod.PUT,
            Map.of("selectedSkillCodes", List.of("PAC", "SHO", "PAS")),
            authenticatedHeaders(cookies), Void.class);
        assertThat(putResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map> getResponse = httpTestClient.makeHttpRequest(
            preferencesUrl(), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<String> codes = (List<String>) getResponse.getBody().get("selectedSkillCodes");
        assertThat(codes).containsExactlyInAnyOrder("PAC", "SHO", "PAS");
    }

    @Test
    @SuppressWarnings("unchecked")
    void savePreferences_overwritesPreviousPreference() {
        String cookies = loginAndGetCookies(COACH_EMAIL);

        httpTestClient.makeHttpRequest(preferencesUrl(), HttpMethod.PUT,
            Map.of("selectedSkillCodes", List.of("PAC")),
            authenticatedHeaders(cookies), Void.class);

        httpTestClient.makeHttpRequest(preferencesUrl(), HttpMethod.PUT,
            Map.of("selectedSkillCodes", List.of("SHO", "DRI")),
            authenticatedHeaders(cookies), Void.class);

        ResponseEntity<Map> getResponse = httpTestClient.makeHttpRequest(
            preferencesUrl(), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);
        @SuppressWarnings("unchecked")
        List<String> codes = (List<String>) getResponse.getBody().get("selectedSkillCodes");
        assertThat(codes).containsExactlyInAnyOrder("SHO", "DRI");
        assertThat(codes).doesNotContain("PAC");
    }

    @Test
    @SuppressWarnings("unchecked")
    void getPreferences_noPreviousPreference_returnsEmptyList() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            preferencesUrl(), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<String> codes = (List<String>) response.getBody().get("selectedSkillCodes");
        assertThat(codes).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void getCorrelation_academyCoach_sufficientData_returnsInsights() {
        transactionTemplate.execute(status -> {
            // Insert 6 player_skill_stats rows with distinct session_ids to pass the minSessionCount gate
            for (int i = 0; i < 6; i++) {
                jdbcTemplate.update(
                    "INSERT INTO development.player_skill_stats " +
                    "(id, player_id, coach_id, skill_code, slu_value, session_id, calculated_at) " +
                    "VALUES (gen_random_uuid(), ?, ?, 'PAC', 10.00, ?, NOW())",
                    PLAYER_ID, academyProfileId, UUID.randomUUID()
                );
            }
            // Insert composite so PAC is classifiable
            jdbcTemplate.update(
                "INSERT INTO development.player_radar_composites " +
                "(player_id, skill_code, composite_score, entry_count, last_updated_at) " +
                "VALUES (?, 'PAC', 65.00, 1, NOW()) ON CONFLICT DO NOTHING",
                PLAYER_ID
            );
            return null;
        });

        String cookies = loginAndGetCookies(ACADEMY_EMAIL);
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            correlationUrl(), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Boolean) response.getBody().get("insufficientData")).isFalse();
        List<?> insights = (List<?>) response.getBody().get("insights");
        assertThat(insights).isNotEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void getCorrelation_academyCoach_insufficientData_returnsInsufficientDataResponse() {
        String cookies = loginAndGetCookies(ACADEMY_EMAIL);
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            correlationUrl(), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Boolean) response.getBody().get("insufficientData")).isTrue();
        assertThat(((Number) response.getBody().get("minimumSessionCount")).longValue()).isEqualTo(5L);
    }

    @Test
    void getCorrelation_nonAcademyCoach_returns403WithFeatureGatedCode() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            correlationUrl(), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(ex -> {
                HttpClientErrorException httpEx = (HttpClientErrorException) ex;
                assertThat(httpEx.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(httpEx.getResponseBodyAsString()).contains("security.featureGated");
            });
    }

    @Test
    void getCorrelation_unauthenticated_returns401() {
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            correlationUrl(), HttpMethod.GET, null, clientHeaders(), Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String displayUrl() {
        return baseUrl() + "/api/development/players/" + PLAYER_ID + "/radar/display";
    }

    private String preferencesUrl() {
        return baseUrl() + "/api/development/players/" + PLAYER_ID + "/radar/preferences";
    }

    private String correlationUrl() {
        return baseUrl() + "/api/development/players/" + PLAYER_ID + "/radar/correlation";
    }

    private String baseUrl() {
        return "http://localhost:" + randomServerPort;
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

    private void insertAuthority(int id, String name) {
        jdbcTemplate.update(
            "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
            "VALUES (?, ?, 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
            id, name, Timestamp.from(Instant.now())
        );
    }

    private void insertUser(long id, String email, String passwordHash, String role) {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" " +
            "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, " +
            "status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, " +
            "activated, locked, login, login_id_type, password_hash, otp_enabled, " +
            "skillars_role, verification_status) " +
            "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, " +
            "'ACTIVE', '1990-01-01', ?, 'Test', 'OTHER', 'en', ?, 'DE', ?, " +
            "true, false, ?, 'EMAIL', ?, false, " +
            "?, 'BASIC_VERIFIED')",
            id,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            email, role,
            "69" + (id % 100000000L),
            email, passwordHash, role
        );
    }

    private void grantRole(long userId, String roleName) {
        jdbcTemplate.update(
            "INSERT INTO main.user_authority (user_id, authority_id) " +
            "VALUES (?, (SELECT id FROM main.authority WHERE name = ?)) ON CONFLICT DO NOTHING",
            userId, roleName
        );
    }

    private UUID insertCoachProfile(long userId) {
        UUID profileId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO marketplace.coach_profiles " +
            "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
            "VALUES (?, ?, 'Test Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
            profileId, userId
        );
        return profileId;
    }

    private void insertSubscription(UUID coachId, String tier) {
        jdbcTemplate.update(
            "INSERT INTO marketplace.coach_subscriptions (coach_id, tier, active_since) " +
            "VALUES (?, ?, NOW()) ON CONFLICT DO NOTHING",
            coachId, tier
        );
    }
}
