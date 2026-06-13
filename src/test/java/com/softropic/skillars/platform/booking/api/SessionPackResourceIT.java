package com.softropic.skillars.platform.booking.api;

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
class SessionPackResourceIT {

    private static final String LOGIN_ENDPOINT  = "/api/auth/login";
    private static final String PACKS_BASE      = "/api/bookings/players";
    private static final String CLIENT_ID       = "testClientId";
    private static final String TEST_PASSWORD   = "ParentPass@123!";

    private static final long PARENT_ID  = 9400000001L;
    private static final long PLAYER_ID  = 9400000002L;
    private static final long PARENT2_ID = 9400000003L;
    private static final String PARENT_EMAIL  = "parent.packs@skillars-test.com";
    private static final String PARENT2_EMAIL = "parent2.packs@skillars-test.com";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private PasswordEncoder passwordEncoder;

    @LocalServerPort private int randomServerPort;

    private UUID coachProfileId;
    private UUID sessionPackId;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (9400, 'ROLE_PARENT', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now())
            );
            insertParentUser(PARENT_ID, PARENT_EMAIL, passwordHash);
            insertParentUser(PARENT2_ID, PARENT2_EMAIL, passwordHash);

            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_PARENT')) ON CONFLICT DO NOTHING",
                PARENT_ID
            );
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_PARENT')) ON CONFLICT DO NOTHING",
                PARENT2_ID
            );

            // Insert player profile linked to PARENT_ID
            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Test Player', ?, 'MIDFIELDER', 'ADULT', ?, true, ?, 'system')",
                PLAYER_ID, Date.valueOf(LocalDate.now().minusYears(16)),
                PARENT_ID, Timestamp.from(Instant.now())
            );

            // Insert coach user + profile
            long coachUserId = 9400000010L;
            jdbcTemplate.update(
                "INSERT INTO main.\"user\" " +
                "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, " +
                "status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, " +
                "activated, locked, login, login_id_type, password_hash, otp_enabled, " +
                "skillars_role, verification_status) " +
                "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, " +
                "'ACTIVE', '1985-01-01', 'coach.packs@skillars-test.com', 'Pack', 'OTHER', 'en', 'Coach', 'DE', '6800000010', " +
                "true, false, 'coach.packs@skillars-test.com', 'EMAIL', ?, false, " +
                "'COACH', 'BASIC_VERIFIED')",
                coachUserId,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
                passwordHash
            );

            coachProfileId = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles " +
                "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, 'Pack Coach', 'Test bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
                coachProfileId, coachUserId
            );

            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_pricing (coach_id, per_session_price, currency) VALUES (?, 25.00, 'EUR')",
                coachProfileId
            );

            // Insert a session pack offered by the coach
            sessionPackId = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO marketplace.session_packs (id, coach_id, session_count, total_price, label) " +
                "VALUES (?, ?, 5, 100.00, '5-session bundle')",
                sessionPackId, coachProfileId
            );

            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM booking.session_packs_purchased WHERE parent_id = ?", PARENT_ID);
            jdbcTemplate.update("DELETE FROM booking.session_packs_purchased WHERE parent_id = ?", PARENT2_ID);
            jdbcTemplate.update("DELETE FROM marketplace.session_packs WHERE coach_id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_pricing WHERE coach_id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id = 9400000010");
            jdbcTemplate.update("DELETE FROM main.player_profiles WHERE id = ?", PLAYER_ID);
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?)", PARENT_ID, PARENT2_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?)", PARENT_ID, PARENT2_ID);
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id = 9400");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void getPlayerPacks_noPacks_returnsEmptyList() {
        String cookies = loginAndGetCookies(PARENT_EMAIL);

        ResponseEntity<List> response = httpTestClient.makeHttpRequest(
            baseUrl() + PACKS_BASE + "/" + PLAYER_ID + "/packs",
            HttpMethod.GET,
            null,
            authenticatedHeaders(cookies),
            List.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    void purchasePack_validRequest_returns201AndPersistsRecord() {
        String cookies = loginAndGetCookies(PARENT_EMAIL);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + PACKS_BASE + "/" + PLAYER_ID + "/packs/purchase",
            HttpMethod.POST,
            Map.of("coachId", coachProfileId.toString(), "sessionPackId", sessionPackId.toString()),
            authenticatedHeaders(cookies),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsKey("id");
        assertThat(response.getBody().get("creditsRemaining")).isEqualTo(5);
        assertThat(response.getBody().get("sessionCount")).isEqualTo(5);
        assertThat(response.getBody().get("status")).isEqualTo("ACTIVE");

        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM booking.session_packs_purchased WHERE parent_id = ?",
            Integer.class, PARENT_ID);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void getPlayerPacks_afterPurchase_returnsPackWithCorrectCredits() {
        String cookies = loginAndGetCookies(PARENT_EMAIL);

        // Purchase
        httpTestClient.makeHttpRequest(
            baseUrl() + PACKS_BASE + "/" + PLAYER_ID + "/packs/purchase",
            HttpMethod.POST,
            Map.of("coachId", coachProfileId.toString(), "sessionPackId", sessionPackId.toString()),
            authenticatedHeaders(cookies),
            Map.class
        );

        // List
        ResponseEntity<List> response = httpTestClient.makeHttpRequest(
            baseUrl() + PACKS_BASE + "/" + PLAYER_ID + "/packs",
            HttpMethod.GET,
            null,
            authenticatedHeaders(cookies),
            List.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        Map<?, ?> pack = (Map<?, ?>) response.getBody().get(0);
        assertThat(pack.get("creditsRemaining")).isEqualTo(5);
        assertThat(pack.get("status")).isEqualTo("ACTIVE");
    }

    @Test
    void purchasePack_playerNotOwnedByParent_returns403() {
        String cookies = loginAndGetCookies(PARENT2_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + PACKS_BASE + "/" + PLAYER_ID + "/packs/purchase",
            HttpMethod.POST,
            Map.of("coachId", coachProfileId.toString(), "sessionPackId", sessionPackId.toString()),
            authenticatedHeaders(cookies),
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void purchasePack_unknownSessionPack_returns404() {
        String cookies = loginAndGetCookies(PARENT_EMAIL);
        UUID unknownPackId = UUID.randomUUID();

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + PACKS_BASE + "/" + PLAYER_ID + "/packs/purchase",
            HttpMethod.POST,
            Map.of("coachId", coachProfileId.toString(), "sessionPackId", unknownPackId.toString()),
            authenticatedHeaders(cookies),
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ----- helpers -----

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
            "680" + (id % 10000000),
            email, passwordHash
        );
    }
}
