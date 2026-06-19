package com.softropic.skillars.platform.development.api;

import com.softropic.skillars.config.TestConfig;
import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.security.SecurityIT;
import org.springframework.http.HttpEntity;
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
import org.springframework.web.client.RestTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.util.HashMap;
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
class SkillExposureResourceIT {

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

    private static final long COACH_USER_ID   = 9570000001L;
    private static final long COACH2_USER_ID  = 9570000002L;
    private static final long PARENT_USER_ID  = 9570000010L;
    private static final long WRONG_PARENT_ID = 9570000011L;
    private static final long PLAYER_ID       = 9570000020L;
    private static final long WRONG_PLAYER_ID = 9570000021L;

    private static final String COACH_EMAIL        = "coach.exposure@skillars-test.com";
    private static final String COACH2_EMAIL       = "coach2.exposure@skillars-test.com";
    private static final String PARENT_EMAIL       = "parent.exposure@skillars-test.com";
    private static final String WRONG_PARENT_EMAIL = "wrongparent.exposure@skillars-test.com";

    private UUID coachProfileId;
    private UUID coach2ProfileId;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        transactionTemplate.execute(status -> {
            insertAuthority(9570, "ROLE_COACH");
            insertAuthority(9571, "ROLE_PARENT");

            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH");
            grantRole(COACH_USER_ID, "ROLE_COACH");
            coachProfileId = insertCoachProfile(COACH_USER_ID);
            insertSubscription(coachProfileId, "INSTRUCTOR");

            insertUser(COACH2_USER_ID, COACH2_EMAIL, passwordHash, "COACH");
            grantRole(COACH2_USER_ID, "ROLE_COACH");
            coach2ProfileId = insertCoachProfile(COACH2_USER_ID);
            insertSubscription(coach2ProfileId, "INSTRUCTOR");

            insertUser(PARENT_USER_ID, PARENT_EMAIL, passwordHash, "PARENT");
            grantRole(PARENT_USER_ID, "ROLE_PARENT");

            insertUser(WRONG_PARENT_ID, WRONG_PARENT_EMAIL, passwordHash, "PARENT");
            grantRole(WRONG_PARENT_ID, "ROLE_PARENT");

            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Exposure Player', ?, 'MIDFIELDER', 'AGE_10_12', ?, false, ?, 'system')",
                PLAYER_ID, Date.valueOf(LocalDate.now().minusYears(10)),
                PARENT_USER_ID, Timestamp.from(Instant.now())
            );
            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Wrong Player', ?, 'MIDFIELDER', 'AGE_10_12', ?, false, ?, 'system')",
                WRONG_PLAYER_ID, Date.valueOf(LocalDate.now().minusYears(10)),
                WRONG_PARENT_ID, Timestamp.from(Instant.now())
            );
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM development.neglected_skill_flags WHERE player_id IN (?, ?)", PLAYER_ID, WRONG_PLAYER_ID);
            jdbcTemplate.update("DELETE FROM development.player_slu_targets WHERE player_id IN (?, ?)", PLAYER_ID, WRONG_PLAYER_ID);
            jdbcTemplate.update("DELETE FROM development.player_slu_weekly_snapshot WHERE player_id IN (?, ?)", PLAYER_ID, WRONG_PLAYER_ID);
            jdbcTemplate.update("DELETE FROM main.player_profiles WHERE id IN (?, ?)", PLAYER_ID, WRONG_PLAYER_ID);
            jdbcTemplate.update("DELETE FROM marketplace.coach_subscriptions WHERE coach_id IN (?, ?)", coachProfileId, coach2ProfileId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id IN (?, ?)", coachProfileId, coach2ProfileId);
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?, ?, ?)",
                COACH_USER_ID, COACH2_USER_ID, PARENT_USER_ID, WRONG_PARENT_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?, ?, ?)",
                COACH_USER_ID, COACH2_USER_ID, PARENT_USER_ID, WRONG_PARENT_ID);
            jdbcTemplate.update("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void getExposure_asCoach_returnsSnapshotData() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        short curYear = (short) now.get(IsoFields.WEEK_BASED_YEAR);
        short curWeek = (short) now.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        insertSnapshot(PLAYER_ID, "PAC", curYear, curWeek, new BigDecimal("12.50"));
        insertSnapshot(PLAYER_ID, "SHO", curYear, curWeek, new BigDecimal("6.00"));

        String cookies = loginAndGetCookies(COACH_EMAIL);
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/development/players/" + PLAYER_ID + "/exposure",
            HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> currentWeek = (Map<String, Object>) response.getBody().get("currentWeek");
        assertThat(currentWeek).containsKey("PAC");
    }

    @Test
    void getExposure_asParent_withOwnedPlayer_returns200() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        short curYear = (short) now.get(IsoFields.WEEK_BASED_YEAR);
        short curWeek = (short) now.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        insertSnapshot(PLAYER_ID, "PAC", curYear, curWeek, new BigDecimal("5.00"));

        String cookies = loginAndGetCookies(PARENT_EMAIL);
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/development/players/" + PLAYER_ID + "/exposure",
            HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getExposure_asParent_withUnownedPlayer_returns403() {
        String cookies = loginAndGetCookies(WRONG_PARENT_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/development/players/" + PLAYER_ID + "/exposure",
            HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class
        )).isInstanceOf(HttpClientErrorException.class)
          .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void getExposure_unauthenticated_returns401() {
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/development/players/" + PLAYER_ID + "/exposure",
            HttpMethod.GET, null, clientHeaders(), Map.class
        )).isInstanceOf(HttpClientErrorException.class)
          .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void setTargets_asCoach_persistsTargets() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        List<Map<String, Object>> payload = List.of(Map.of("skillCode", "PAC", "weeklyTargetSlu", 10.0));

        ResponseEntity<List> putResponse = putTargets(PLAYER_ID, payload, cookies);
        assertThat(putResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<List> getResponse = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/development/players/" + PLAYER_ID + "/targets",
            HttpMethod.GET, null, authenticatedHeaders(cookies), List.class
        );
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody()).hasSize(1);
        assertThat(((Map<?, ?>) getResponse.getBody().get(0)).get("skillCode")).isEqualTo("PAC");
    }

    @Test
    void setTargets_twoCoaches_independentTargets() {
        String cookies1 = loginAndGetCookies(COACH_EMAIL);
        String cookies2 = loginAndGetCookies(COACH2_EMAIL);

        putTargets(PLAYER_ID, List.of(Map.of("skillCode", "PAC", "weeklyTargetSlu", 10.0)), cookies1);
        putTargets(PLAYER_ID, List.of(Map.of("skillCode", "SHO", "weeklyTargetSlu", 8.0)), cookies2);

        ResponseEntity<List> c1Get = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/development/players/" + PLAYER_ID + "/targets",
            HttpMethod.GET, null, authenticatedHeaders(cookies1), List.class
        );
        ResponseEntity<List> c2Get = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/development/players/" + PLAYER_ID + "/targets",
            HttpMethod.GET, null, authenticatedHeaders(cookies2), List.class
        );

        assertThat(c1Get.getBody()).hasSize(1);
        assertThat(((Map<?, ?>) c1Get.getBody().get(0)).get("skillCode")).isEqualTo("PAC");
        assertThat(c2Get.getBody()).hasSize(1);
        assertThat(((Map<?, ?>) c2Get.getBody().get(0)).get("skillCode")).isEqualTo("SHO");
    }

    @Test
    void setTargets_nullTarget_removesExistingTarget() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        putTargets(PLAYER_ID, List.of(Map.of("skillCode", "PAC", "weeklyTargetSlu", 10.0)), cookies);

        Map<String, Object> removePayload = new HashMap<>();
        removePayload.put("skillCode", "PAC");
        removePayload.put("weeklyTargetSlu", null);
        putTargets(PLAYER_ID, List.of(removePayload), cookies);

        ResponseEntity<List> getResponse = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/development/players/" + PLAYER_ID + "/targets",
            HttpMethod.GET, null, authenticatedHeaders(cookies), List.class
        );
        assertThat(getResponse.getBody()).isEmpty();
    }

    @Test
    void getNeglectedSkills_returnsOpenFlags() {
        UUID openFlagId = UUID.randomUUID();
        UUID resolvedFlagId = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO development.neglected_skill_flags (id, player_id, skill_code, detected_at) " +
                "VALUES (?, ?, 'PAC', ?)",
                openFlagId, PLAYER_ID, Timestamp.from(Instant.now())
            );
            jdbcTemplate.update(
                "INSERT INTO development.neglected_skill_flags (id, player_id, skill_code, detected_at, resolved_at) " +
                "VALUES (?, ?, 'SHO', ?, ?)",
                resolvedFlagId, PLAYER_ID, Timestamp.from(Instant.now()), Timestamp.from(Instant.now())
            );
            return null;
        });

        String cookies = loginAndGetCookies(COACH_EMAIL);
        ResponseEntity<List> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/development/players/" + PLAYER_ID + "/neglected-skills",
            HttpMethod.GET, null, authenticatedHeaders(cookies), List.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(((Map<?, ?>) response.getBody().get(0)).get("skillCode")).isEqualTo("PAC");
    }

    @Test
    void getNarrative_asParent_returns200WithKeys() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        for (int i = 7; i >= 0; i--) {
            ZonedDateTime weekDt = now.minusWeeks(i);
            short yr = (short) weekDt.get(IsoFields.WEEK_BASED_YEAR);
            short wk = (short) weekDt.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            BigDecimal slu = i < 4 ? new BigDecimal("20.00") : new BigDecimal("5.00");
            insertSnapshot(PLAYER_ID, "PAC", yr, wk, slu);
        }

        String cookies = loginAndGetCookies(PARENT_EMAIL);
        ResponseEntity<List> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/development/players/" + PLAYER_ID + "/narrative",
            HttpMethod.GET, null, authenticatedHeaders(cookies), List.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotEmpty();
        assertThat(((Map<?, ?>) response.getBody().get(0)).get("key").toString())
            .startsWith("development.narrative.");
    }

    @Test
    void setTargets_asCoach_updatesExistingTarget() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        putTargets(PLAYER_ID, List.of(Map.of("skillCode", "PAC", "weeklyTargetSlu", 10.0)), cookies);
        putTargets(PLAYER_ID, List.of(Map.of("skillCode", "PAC", "weeklyTargetSlu", 20.0)), cookies);

        ResponseEntity<List> getResponse = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/development/players/" + PLAYER_ID + "/targets",
            HttpMethod.GET, null, authenticatedHeaders(cookies), List.class
        );
        assertThat(getResponse.getBody()).hasSize(1);
        assertThat(((Map<?, ?>) getResponse.getBody().get(0)).get("weeklyTargetSlu").toString())
            .startsWith("20");
    }

    @Test
    void getNarrative_asParent_withUnownedPlayer_returns403() {
        String cookies = loginAndGetCookies(WRONG_PARENT_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/development/players/" + PLAYER_ID + "/narrative",
            HttpMethod.GET, null, authenticatedHeaders(cookies), List.class
        )).isInstanceOf(HttpClientErrorException.class)
          .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void getNarrative_unauthenticated_returns401() {
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/development/players/" + PLAYER_ID + "/narrative",
            HttpMethod.GET, null, clientHeaders(), List.class
        )).isInstanceOf(HttpClientErrorException.class)
          .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ResponseEntity<List> putTargets(long playerId, List<Map<String, Object>> body, String cookies) {
        HttpEntity<List<Map<String, Object>>> entity = new HttpEntity<>(body, authenticatedHeaders(cookies));
        return restTemplate.exchange(
            baseUrl() + "/api/development/players/" + playerId + "/targets",
            HttpMethod.PUT, entity, List.class
        );
    }

    private void insertSnapshot(long playerId, String skillCode, short isoYear, short isoWeek, BigDecimal slu) {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO development.player_slu_weekly_snapshot " +
                "(player_id, skill_code, iso_year, iso_week, total_slu) " +
                "VALUES (?, ?, ?, ?, ?) " +
                "ON CONFLICT (player_id, skill_code, iso_year, iso_week) " +
                "DO UPDATE SET total_slu = development.player_slu_weekly_snapshot.total_slu + EXCLUDED.total_slu",
                playerId, skillCode, isoYear, isoWeek, slu
            );
            return null;
        });
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
