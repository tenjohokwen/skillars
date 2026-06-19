package com.softropic.skillars.platform.development.api;

import com.softropic.skillars.config.TestConfig;
import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.filestorage.service.FileStorageService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestConfig.class)
@TestPropertySource(properties = {
    "spring.cloud.compatibility-verifier.enabled=false",
    "rate.limiting.enabled=false",
    "allowed.clients=testClientId"
})
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class PlayerTimelineResourceIT {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String CLIENT_ID      = "testClientId";
    private static final String TEST_PASSWORD  = "TestPass@123!";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private RestTemplate restTemplate;
    @Autowired private PasswordEncoder passwordEncoder;
    @LocalServerPort private int randomServerPort;

    @MockitoBean VideoProviderAdapter videoProviderAdapter;
    @MockitoBean FileStorageService fileStorageService;

    private static final long COACH_USER_ID   = 9600000001L;
    private static final long ACADEMY_USER_ID = 9600000002L;
    private static final long SCOUT_USER_ID   = 9600000003L;
    private static final long PARENT_USER_ID  = 9600000010L;
    private static final long PLAYER_ID       = 9600000020L;

    private static final String COACH_EMAIL   = "coach.timeline@skillars-test.com";
    private static final String ACADEMY_EMAIL = "academy.timeline@skillars-test.com";
    private static final String SCOUT_EMAIL   = "scout.timeline@skillars-test.com";
    private static final String PARENT_EMAIL  = "parent.timeline@skillars-test.com";

    private UUID coachProfileId;
    private UUID academyProfileId;
    private UUID scoutProfileId;

    @BeforeEach
    void setUp() {
        when(fileStorageService.storeBytes(any(), anyString(), anyString())).thenReturn("reports/test/report.pdf");
        when(fileStorageService.signedDownloadUrl(anyString())).thenReturn("https://s3.test/signed-url");

        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        transactionTemplate.execute(status -> {
            insertAuthority(9600, "ROLE_COACH");
            insertAuthority(9601, "ROLE_PARENT");

            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH");
            grantRole(COACH_USER_ID, "ROLE_COACH");
            coachProfileId = insertCoachProfile(COACH_USER_ID);
            insertSubscription(coachProfileId, "INSTRUCTOR");

            insertUser(ACADEMY_USER_ID, ACADEMY_EMAIL, passwordHash, "COACH");
            grantRole(ACADEMY_USER_ID, "ROLE_COACH");
            academyProfileId = insertCoachProfile(ACADEMY_USER_ID);
            insertSubscription(academyProfileId, "ACADEMY");

            insertUser(SCOUT_USER_ID, SCOUT_EMAIL, passwordHash, "COACH");
            grantRole(SCOUT_USER_ID, "ROLE_COACH");
            scoutProfileId = insertCoachProfile(SCOUT_USER_ID);
            insertSubscription(scoutProfileId, "SCOUT");

            insertUser(PARENT_USER_ID, PARENT_EMAIL, passwordHash, "PARENT");
            grantRole(PARENT_USER_ID, "ROLE_PARENT");

            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Timeline Player', ?, 'MIDFIELDER', 'AGE_10_12', ?, false, ?, 'system')",
                PLAYER_ID, Date.valueOf(LocalDate.now().minusYears(10)),
                PARENT_USER_ID, Timestamp.from(Instant.now())
            );
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM development.player_timeline_events WHERE player_id = ?", PLAYER_ID);
            jdbcTemplate.update("DELETE FROM development.performance_reports WHERE player_id = ?", PLAYER_ID);
            jdbcTemplate.update("DELETE FROM development.coach_branding WHERE coach_id IN (?, ?, ?)",
                coachProfileId, academyProfileId, scoutProfileId);
            jdbcTemplate.update("DELETE FROM development.player_skill_stats WHERE player_id = ?", PLAYER_ID);
            jdbcTemplate.update("DELETE FROM main.player_profiles WHERE id = ?", PLAYER_ID);
            jdbcTemplate.update("DELETE FROM marketplace.coach_subscriptions WHERE coach_id IN (?, ?, ?)",
                coachProfileId, academyProfileId, scoutProfileId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id IN (?, ?, ?)",
                coachProfileId, academyProfileId, scoutProfileId);
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?, ?, ?)",
                COACH_USER_ID, ACADEMY_USER_ID, SCOUT_USER_ID, PARENT_USER_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?, ?, ?)",
                COACH_USER_ID, ACADEMY_USER_ID, SCOUT_USER_ID, PARENT_USER_ID);
            jdbcTemplate.update("DELETE FROM main.sec");
            return null;
        });
    }

    // ── Timeline Tests ──────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void getTimeline_asCoach_withActiveAccess_returns200WithEvents() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO development.player_skill_stats " +
                "(id, player_id, coach_id, skill_code, slu_value, session_id, calculated_at) " +
                "VALUES (gen_random_uuid(), ?, ?, 'PAC', 10.00, gen_random_uuid(), NOW())",
                PLAYER_ID, coachProfileId
            );
            insertTimelineEvent(PLAYER_ID, "SESSION_COMPLETED", "booking");
            return null;
        });

        String cookies = loginAndGetCookies(COACH_EMAIL);
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            timelineUrl(), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Boolean) response.getBody().get("accessExpired")).isFalse();
        assertThat((List<?>) response.getBody().get("events")).isNotEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void getTimeline_asCoach_withExpiredAccess_returns200WithAccessExpired() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO development.player_skill_stats " +
                "(id, player_id, coach_id, skill_code, slu_value, session_id, calculated_at) " +
                "VALUES (gen_random_uuid(), ?, ?, 'PAC', 10.00, gen_random_uuid(), NOW() - INTERVAL '100 days')",
                PLAYER_ID, coachProfileId
            );
            return null;
        });

        String cookies = loginAndGetCookies(COACH_EMAIL);
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            timelineUrl(), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Boolean) response.getBody().get("accessExpired")).isTrue();
        assertThat((List<?>) response.getBody().get("events")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void getTimeline_asCoach_withNoSluHistory_returns200WithAccessExpired() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            timelineUrl(), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Boolean) response.getBody().get("accessExpired")).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void getTimeline_asParent_returns200WithFullTimeline() {
        transactionTemplate.execute(status -> {
            insertTimelineEvent(PLAYER_ID, "SESSION_COMPLETED", "booking");
            return null;
        });

        String cookies = loginAndGetCookies(PARENT_EMAIL);
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            timelineUrl(), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Boolean) response.getBody().get("accessExpired")).isFalse();
        assertThat((List<?>) response.getBody().get("events")).isNotEmpty();
    }

    @Test
    void getTimeline_asParentForUnlinkedPlayer_returns403() {
        // Insert a different parent's player
        long otherParentId = 9600000099L;
        long otherPlayerId = 9600000098L;
        transactionTemplate.execute(status -> {
            insertUser(otherParentId, "other.parent.timeline@skillars-test.com",
                passwordEncoder.encode(TEST_PASSWORD), "PARENT");
            grantRole(otherParentId, "ROLE_PARENT");
            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Other Player', ?, 'MIDFIELDER', 'AGE_10_12', ?, false, ?, 'system')",
                otherPlayerId, Date.valueOf(LocalDate.now().minusYears(10)),
                otherParentId, Timestamp.from(Instant.now())
            );
            return null;
        });
        try {
            String cookies = loginAndGetCookies(PARENT_EMAIL);
            String otherPlayerUrl = baseUrl() + "/api/development/players/" + otherPlayerId + "/timeline";

            assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
                otherPlayerUrl, HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
        } finally {
            transactionTemplate.execute(status -> {
                jdbcTemplate.update("DELETE FROM main.player_profiles WHERE id = ?", otherPlayerId);
                jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id = ?", otherParentId);
                jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id = ?", otherParentId);
                return null;
            });
        }
    }

    @Test
    void getTimeline_unauthenticated_returns401() {
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            timelineUrl(), HttpMethod.GET, null, clientHeaders(), Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    // ── Report Tests ──────────────────────────────────────────────────────────

    @Test
    void generateReport_asInstructorCoach_returns204() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO development.player_skill_stats " +
                "(id, player_id, coach_id, skill_code, slu_value, session_id, calculated_at) " +
                "VALUES (gen_random_uuid(), ?, ?, 'PAC', 10.00, gen_random_uuid(), NOW())",
                PLAYER_ID, coachProfileId
            );
            return null;
        });

        String cookies = loginAndGetCookies(COACH_EMAIL);
        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            reportsUrl(), HttpMethod.POST,
            Map.of("nextSteps", "Keep working on your first touch and ball control."),
            authenticatedHeaders(cookies), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        int count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM development.performance_reports WHERE player_id = ?",
            Integer.class, PLAYER_ID);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void generateReport_asScoutCoach_returns403WithFeatureGatedCode() {
        String cookies = loginAndGetCookies(SCOUT_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            reportsUrl(), HttpMethod.POST,
            Map.of("nextSteps", "Keep it up!"),
            authenticatedHeaders(cookies), Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(ex -> {
                HttpClientErrorException httpEx = (HttpClientErrorException) ex;
                assertThat(httpEx.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(httpEx.getResponseBodyAsString()).contains("security.featureGated");
            });
    }

    @Test
    @SuppressWarnings("unchecked")
    void listReports_asParent_returns200WithReports() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO development.performance_reports " +
                "(id, coach_id, player_id, generated_at, storage_key, next_steps, version) " +
                "VALUES (gen_random_uuid(), ?, ?, NOW(), 'reports/test/report.pdf', 'Keep it up', 1)",
                coachProfileId, PLAYER_ID
            );
            return null;
        });

        String cookies = loginAndGetCookies(PARENT_EMAIL);
        ResponseEntity<List> response = httpTestClient.makeHttpRequest(
            reportsUrl(), HttpMethod.GET, null, authenticatedHeaders(cookies), List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
    }

    @Test
    void listReports_asParentForUnlinkedPlayer_returns403() {
        long otherParentId = 9600000097L;
        long otherPlayerId = 9600000096L;
        transactionTemplate.execute(status -> {
            insertUser(otherParentId, "other.parent2.timeline@skillars-test.com",
                passwordEncoder.encode(TEST_PASSWORD), "PARENT");
            grantRole(otherParentId, "ROLE_PARENT");
            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Other Player 2', ?, 'MIDFIELDER', 'AGE_10_12', ?, false, ?, 'system')",
                otherPlayerId, Date.valueOf(LocalDate.now().minusYears(10)),
                otherParentId, Timestamp.from(Instant.now())
            );
            return null;
        });
        try {
            String cookies = loginAndGetCookies(PARENT_EMAIL);
            String otherPlayerReportsUrl = baseUrl() + "/api/development/players/" + otherPlayerId + "/reports";

            assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
                otherPlayerReportsUrl, HttpMethod.GET, null, authenticatedHeaders(cookies), List.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
        } finally {
            transactionTemplate.execute(status -> {
                jdbcTemplate.update("DELETE FROM main.player_profiles WHERE id = ?", otherPlayerId);
                jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id = ?", otherParentId);
                jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id = ?", otherParentId);
                return null;
            });
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String timelineUrl() {
        return baseUrl() + "/api/development/players/" + PLAYER_ID + "/timeline";
    }

    private String reportsUrl() {
        return baseUrl() + "/api/development/players/" + PLAYER_ID + "/reports";
    }

    private String baseUrl() {
        return "http://localhost:" + randomServerPort;
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

    private void insertTimelineEvent(long playerId, String eventType, String referenceModule) {
        jdbcTemplate.update(
            "INSERT INTO development.player_timeline_events " +
            "(id, player_id, event_type, reference_module, occurred_at) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, NOW())",
            playerId, eventType, referenceModule
        );
    }
}
