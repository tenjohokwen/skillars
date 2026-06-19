package com.softropic.skillars.platform.development.api;

import com.softropic.skillars.config.TestConfig;
import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.filestorage.service.FileStorageService;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
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
import static org.mockito.ArgumentMatchers.any;
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
class ParentDevelopmentPortalResourceIT {

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
    @MockitoBean CoachProfileService coachProfileService;

    private static final long PARENT_USER_ID  = 9610000010L;
    private static final long OTHER_PARENT_ID = 9610000011L;
    private static final long PLAYER_ID       = 9610000020L;
    private static final long OTHER_PLAYER_ID = 9610000021L;
    private static final UUID COACH_A_ID      = UUID.fromString("00000000-0000-0000-0000-000000009610");
    private static final UUID COACH_B_ID      = UUID.fromString("00000000-0000-0000-0000-000000009611");

    private static final String PARENT_EMAIL       = "parent.portal@skillars-test.com";
    private static final String OTHER_PARENT_EMAIL  = "other.parent.portal@skillars-test.com";

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        transactionTemplate.execute(status -> {
            insertAuthority(9610, "ROLE_PARENT");

            insertUser(PARENT_USER_ID, PARENT_EMAIL, passwordHash, "PARENT");
            grantRole(PARENT_USER_ID, "ROLE_PARENT");

            insertUser(OTHER_PARENT_ID, OTHER_PARENT_EMAIL, passwordHash, "PARENT");
            grantRole(OTHER_PARENT_ID, "ROLE_PARENT");

            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Portal Player', ?, 'MIDFIELDER', 'AGE_10_12', ?, false, ?, 'system')",
                PLAYER_ID, Date.valueOf(LocalDate.now().minusYears(10)),
                PARENT_USER_ID, Timestamp.from(Instant.now())
            );

            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Other Player', ?, 'DEFENDER', 'AGE_10_12', ?, false, ?, 'system')",
                OTHER_PLAYER_ID, Date.valueOf(LocalDate.now().minusYears(11)),
                OTHER_PARENT_ID, Timestamp.from(Instant.now())
            );
            return null;
        });

        when(coachProfileService.getDisplayNamesByIds(any()))
            .thenReturn(Map.of(COACH_A_ID, "Marcus Alves", COACH_B_ID, "Janet Rose"));
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM development.player_skill_stats WHERE player_id IN (?, ?)",
                PLAYER_ID, OTHER_PLAYER_ID);
            jdbcTemplate.update("DELETE FROM main.player_profiles WHERE id IN (?, ?)",
                PLAYER_ID, OTHER_PLAYER_ID);
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?)",
                PARENT_USER_ID, OTHER_PARENT_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?)",
                PARENT_USER_ID, OTHER_PARENT_ID);
            jdbcTemplate.update("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void getCoachContributions_asParent_ownPlayer_returns200WithContributions() {
        transactionTemplate.execute(status -> {
            seedSluRow(PLAYER_ID, COACH_A_ID, "DRI", new BigDecimal("60.0"));
            seedSluRow(PLAYER_ID, COACH_B_ID, "DRI", new BigDecimal("40.0"));
            return null;
        });

        String cookies = loginAndGetCookies(PARENT_EMAIL);
        ResponseEntity<List> response = httpTestClient.makeHttpRequest(
            contributionsUrl(PLAYER_ID), HttpMethod.GET, null, authenticatedHeaders(cookies), List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> contributions = (List<Map<String, Object>>) response.getBody();
        assertThat(contributions).hasSize(2);

        Map<String, Object> coachA = contributions.stream()
            .filter(c -> "Marcus Alves".equals(c.get("coachDisplayName")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected Marcus Alves in response"));
        assertThat(coachA.get("skillCode")).isEqualTo("DRI");
        assertThat(((Number) coachA.get("percentageContribution")).doubleValue())
            .isCloseTo(60.0, org.assertj.core.data.Offset.offset(0.2));

        Map<String, Object> coachB = contributions.stream()
            .filter(c -> "Janet Rose".equals(c.get("coachDisplayName")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected Janet Rose in response"));
        assertThat(coachB.get("skillCode")).isEqualTo("DRI");
        assertThat(((Number) coachB.get("percentageContribution")).doubleValue())
            .isCloseTo(40.0, org.assertj.core.data.Offset.offset(0.2));
    }

    @Test
    void getCoachContributions_asParent_returns200WithEmptyListForNoHistory() {
        String cookies = loginAndGetCookies(PARENT_EMAIL);
        ResponseEntity<List> response = httpTestClient.makeHttpRequest(
            contributionsUrl(PLAYER_ID), HttpMethod.GET, null, authenticatedHeaders(cookies), List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    void getCoachContributions_asParentForUnlinkedPlayer_returns403() {
        String cookies = loginAndGetCookies(PARENT_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            contributionsUrl(OTHER_PLAYER_ID), HttpMethod.GET, null, authenticatedHeaders(cookies), List.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void getCoachContributions_unauthenticated_returns401() {
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            contributionsUrl(PLAYER_ID), HttpMethod.GET, null, clientHeaders(), List.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getCoachContributions_asCoach_returns200() {
        long coachUserId = 9610000001L;
        String coachEmail = "coach.portal@skillars-test.com";
        UUID coachProfileId = UUID.randomUUID();
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);

        transactionTemplate.execute(status -> {
            insertAuthority(9612, "ROLE_COACH");
            insertUser(coachUserId, coachEmail, passwordHash, "COACH");
            grantRole(coachUserId, "ROLE_COACH");
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles " +
                "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, 'Coach Portal', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
                coachProfileId, coachUserId
            );
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_subscriptions (coach_id, tier, active_since) " +
                "VALUES (?, ?, NOW()) ON CONFLICT DO NOTHING",
                coachProfileId, "INSTRUCTOR"
            );
            return null;
        });

        try {
            String cookies = loginAndGetCookies(coachEmail);
            ResponseEntity<List> response = httpTestClient.makeHttpRequest(
                contributionsUrl(PLAYER_ID), HttpMethod.GET, null, authenticatedHeaders(cookies), List.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        } finally {
            transactionTemplate.execute(status -> {
                jdbcTemplate.update("DELETE FROM marketplace.coach_subscriptions WHERE coach_id = ?", coachProfileId);
                jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id = ?", coachProfileId);
                jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id = ?", coachUserId);
                jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id = ?", coachUserId);
                return null;
            });
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void getCoachContributions_percentages_sumTo100PerSkill() {
        transactionTemplate.execute(status -> {
            // 3 coaches with equal SLU for DRI — percentages should sum to 100 ±0.2
            UUID coachC = UUID.fromString("00000000-0000-0000-0000-000000009612");
            when(coachProfileService.getDisplayNamesByIds(any()))
                .thenReturn(Map.of(
                    COACH_A_ID, "Marcus Alves",
                    COACH_B_ID, "Janet Rose",
                    coachC, "Sam Lee"
                ));
            seedSluRow(PLAYER_ID, COACH_A_ID, "DRI", new BigDecimal("10.0"));
            seedSluRow(PLAYER_ID, COACH_B_ID, "DRI", new BigDecimal("10.0"));
            jdbcTemplate.update(
                "INSERT INTO development.player_skill_stats " +
                "(id, player_id, coach_id, skill_code, slu_value, calculated_at) " +
                "VALUES (gen_random_uuid(), ?, ?, 'DRI', 10.0, NOW())",
                PLAYER_ID, coachC
            );
            return null;
        });

        String cookies = loginAndGetCookies(PARENT_EMAIL);
        ResponseEntity<List> response = httpTestClient.makeHttpRequest(
            contributionsUrl(PLAYER_ID), HttpMethod.GET, null, authenticatedHeaders(cookies), List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> contributions = (List<Map<String, Object>>) response.getBody();

        double sumDri = contributions.stream()
            .filter(c -> "DRI".equals(c.get("skillCode")))
            .mapToDouble(c -> ((Number) c.get("percentageContribution")).doubleValue())
            .sum();
        assertThat(sumDri).isCloseTo(100.0, org.assertj.core.data.Offset.offset(0.2));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void seedSluRow(long playerId, UUID coachId, String skillCode, BigDecimal sluValue) {
        jdbcTemplate.update(
            "INSERT INTO development.player_skill_stats " +
            "(id, player_id, coach_id, skill_code, slu_value, calculated_at) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, ?, NOW())",
            playerId, coachId, skillCode, sluValue
        );
    }

    private String contributionsUrl(long playerId) {
        return baseUrl() + "/api/development/players/" + playerId + "/slu/coach-contributions";
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
}
