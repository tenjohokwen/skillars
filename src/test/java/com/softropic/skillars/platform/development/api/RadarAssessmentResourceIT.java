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
class RadarAssessmentResourceIT {

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

    private static final long COACH_USER_ID   = 9580000001L;
    private static final long COACH2_USER_ID  = 9580000002L;
    private static final long SCOUT_USER_ID   = 9580000003L;
    private static final long PARENT_USER_ID  = 9580000010L;
    private static final long PLAYER_ID       = 9580000020L;

    private static final String COACH_EMAIL  = "coach.radar@skillars-test.com";
    private static final String COACH2_EMAIL = "coach2.radar@skillars-test.com";
    private static final String SCOUT_EMAIL  = "scout.radar@skillars-test.com";

    private UUID coachProfileId;
    private UUID coach2ProfileId;
    private UUID scoutProfileId;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        transactionTemplate.execute(status -> {
            insertAuthority(9580, "ROLE_COACH");
            insertAuthority(9581, "ROLE_PARENT");

            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH");
            grantRole(COACH_USER_ID, "ROLE_COACH");
            coachProfileId = insertCoachProfile(COACH_USER_ID);
            insertSubscription(coachProfileId, "INSTRUCTOR");

            insertUser(COACH2_USER_ID, COACH2_EMAIL, passwordHash, "COACH");
            grantRole(COACH2_USER_ID, "ROLE_COACH");
            coach2ProfileId = insertCoachProfile(COACH2_USER_ID);
            insertSubscription(coach2ProfileId, "INSTRUCTOR");

            insertUser(SCOUT_USER_ID, SCOUT_EMAIL, passwordHash, "COACH");
            grantRole(SCOUT_USER_ID, "ROLE_COACH");
            scoutProfileId = insertCoachProfile(SCOUT_USER_ID);
            insertSubscription(scoutProfileId, "SCOUT");

            insertUser(PARENT_USER_ID, "parent.radar@skillars-test.com", passwordHash, "PARENT");
            grantRole(PARENT_USER_ID, "ROLE_PARENT");

            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Radar Player', ?, 'MIDFIELDER', 'AGE_10_12', ?, false, ?, 'system')",
                PLAYER_ID, Date.valueOf(LocalDate.now().minusYears(10)),
                PARENT_USER_ID, Timestamp.from(Instant.now())
            );
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM development.player_radar_composites WHERE player_id = ?", PLAYER_ID);
            jdbcTemplate.update("DELETE FROM development.radar_assessment_entries WHERE player_id = ?", PLAYER_ID);
            jdbcTemplate.update("DELETE FROM main.player_profiles WHERE id = ?", PLAYER_ID);
            jdbcTemplate.update("DELETE FROM marketplace.coach_subscriptions WHERE coach_id IN (?, ?, ?)",
                coachProfileId, coach2ProfileId, scoutProfileId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id IN (?, ?, ?)",
                coachProfileId, coach2ProfileId, scoutProfileId);
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?, ?, ?)",
                COACH_USER_ID, COACH2_USER_ID, SCOUT_USER_ID, PARENT_USER_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?, ?, ?)",
                COACH_USER_ID, COACH2_USER_ID, SCOUT_USER_ID, PARENT_USER_ID);
            jdbcTemplate.update("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void submitAssessment_asInstructorCoach_returns204() {
        UUID groupId = UUID.randomUUID();
        Map<String, Object> payload = buildPayload(groupId, "OBJECTIVE", LocalDate.now(),
            List.of(Map.of("skillCode", "PAC", "score", 75)));

        String cookies = loginAndGetCookies(COACH_EMAIL);
        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            radarUrl(), HttpMethod.POST, payload, authenticatedHeaders(cookies), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM development.radar_assessment_entries WHERE player_id = ?",
            Long.class, PLAYER_ID);
        assertThat(count).isEqualTo(1L);

        String storedType = jdbcTemplate.queryForObject(
            "SELECT assessment_type::text FROM development.radar_assessment_entries WHERE player_id = ?",
            String.class, PLAYER_ID);
        assertThat(storedType).isEqualTo("OBJECTIVE");
    }

    @Test
    void submitAssessment_allSkillsInGroupShareAssessmentGroupId() {
        UUID groupId = UUID.randomUUID();
        Map<String, Object> payload = buildPayload(groupId, "OBJECTIVE", LocalDate.now(),
            List.of(
                Map.of("skillCode", "PAC", "score", 70),
                Map.of("skillCode", "SHO", "score", 65),
                Map.of("skillCode", "PAS", "score", 80)
            ));

        String cookies = loginAndGetCookies(COACH_EMAIL);
        httpTestClient.makeHttpRequest(radarUrl(), HttpMethod.POST, payload, authenticatedHeaders(cookies), Void.class);

        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM development.radar_assessment_entries WHERE assessment_group_id = ?",
            Long.class, groupId);
        assertThat(count).isEqualTo(3L);

        // Retry with same groupId + same coachId + same skillCode → unique constraint → 409 Conflict
        Map<String, Object> retryPayload = buildPayload(groupId, "OBJECTIVE", LocalDate.now(),
            List.of(Map.of("skillCode", "PAC", "score", 72)));

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            radarUrl(), HttpMethod.POST, retryPayload, authenticatedHeaders(cookies), Void.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void submitAssessment_transactional_rejectsPartialPayload() {
        UUID groupId = UUID.randomUUID();
        // score=150 fails @Max(100) validation — whole batch rejected
        Map<String, Object> payload = buildPayload(groupId, "OBJECTIVE", LocalDate.now(),
            List.of(
                Map.of("skillCode", "PAC", "score", 50),
                Map.of("skillCode", "SHO", "score", 150)
            ));

        String cookies = loginAndGetCookies(COACH_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            radarUrl(), HttpMethod.POST, payload, authenticatedHeaders(cookies), Void.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM development.radar_assessment_entries WHERE player_id = ?",
            Long.class, PLAYER_ID);
        assertThat(count).isEqualTo(0L);
    }

    @Test
    void submitAssessment_asScoutCoach_returns403WithFeatureGatedCode() {
        Map<String, Object> payload = buildPayload(UUID.randomUUID(), "OBJECTIVE", LocalDate.now(),
            List.of(Map.of("skillCode", "PAC", "score", 60)));

        String cookies = loginAndGetCookies(SCOUT_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            radarUrl(), HttpMethod.POST, payload, authenticatedHeaders(cookies), Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(ex -> {
                HttpClientErrorException httpEx = (HttpClientErrorException) ex;
                assertThat(httpEx.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(httpEx.getResponseBodyAsString()).contains("security.featureGated");
            });
    }

    @Test
    void submitAssessment_unauthenticated_returns401() {
        Map<String, Object> payload = buildPayload(UUID.randomUUID(), "OBJECTIVE", LocalDate.now(),
            List.of(Map.of("skillCode", "PAC", "score", 60)));

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            radarUrl(), HttpMethod.POST, payload, clientHeaders(), Void.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getMyEntries_returnsOwnEntriesOnly() {
        UUID groupA = UUID.randomUUID();
        UUID groupB = UUID.randomUUID();

        transactionTemplate.execute(status -> {
            insertEntry(groupA, coachProfileId, "PAC", 80, "OBJECTIVE");
            insertEntry(groupB, coach2ProfileId, "PAC", 90, "OBJECTIVE");
            return null;
        });

        String cookies = loginAndGetCookies(COACH_EMAIL);
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            radarUrl(), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> entries = (List<?>) response.getBody().get("entries");
        assertThat(entries).hasSize(1);
        Map<?, ?> entry = (Map<?, ?>) entries.get(0);
        assertThat(entry.get("skillCode")).isEqualTo("PAC");
        assertThat(entry.get("score")).isEqualTo(80);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getMyEntries_otherCoachCounts_showsCountNotScores() {
        UUID groupA = UUID.randomUUID();
        UUID groupB = UUID.randomUUID();

        transactionTemplate.execute(status -> {
            insertEntry(groupA, coachProfileId, "PAC", 80, "OBJECTIVE");
            insertEntry(groupB, coach2ProfileId, "PAC", 90, "OBJECTIVE");
            return null;
        });

        String cookies = loginAndGetCookies(COACH_EMAIL);
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            radarUrl(), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> otherCoachCounts = (Map<?, ?>) response.getBody().get("otherCoachCounts");
        assertThat(otherCoachCounts.get("PAC")).isNotNull();
        assertThat(Integer.parseInt(otherCoachCounts.get("PAC").toString())).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getMyEntries_emptyForNewCoach_returns200WithEmptyList() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            radarUrl(), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> entries = (List<?>) response.getBody().get("entries");
        assertThat(entries).isEmpty();
    }

    @Test
    void submitAssessment_invalidSkillCode_returns400NotServerError() {
        Map<String, Object> payload = buildPayload(UUID.randomUUID(), "OBJECTIVE", LocalDate.now(),
            List.of(Map.of("skillCode", "NOTREAL", "score", 60)));

        String cookies = loginAndGetCookies(COACH_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            radarUrl(), HttpMethod.POST, payload, authenticatedHeaders(cookies), Void.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String radarUrl() {
        return baseUrl() + "/api/development/players/" + PLAYER_ID + "/radar/entries";
    }

    private Map<String, Object> buildPayload(UUID groupId, String assessmentType, LocalDate date,
                                              List<Map<String, Object>> entries) {
        return Map.of(
            "assessmentGroupId", groupId.toString(),
            "assessmentDate", date.toString(),
            "assessmentType", assessmentType,
            "entries", entries
        );
    }

    private void insertEntry(UUID groupId, UUID coachId, String skillCode, int score, String type) {
        jdbcTemplate.update(
            "INSERT INTO development.radar_assessment_entries " +
            "(id, assessment_group_id, coach_id, player_id, skill_code, score, assessment_date, assessment_type, created_at) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, CURRENT_DATE, ?::development.assessment_type, NOW())",
            groupId, coachId, PLAYER_ID, skillCode, (short) score, type
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
