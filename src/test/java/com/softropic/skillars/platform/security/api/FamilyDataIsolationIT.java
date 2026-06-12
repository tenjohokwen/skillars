package com.softropic.skillars.platform.security.api;

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
class FamilyDataIsolationIT {

    private static final String LOGIN_ENDPOINT   = "/api/auth/login";
    private static final String PLAYERS_ENDPOINT = "/api/security/players";
    private static final String CLIENT_ID        = "testClientId";
    private static final String TEST_PASSWORD    = "TestPass@123!";

    private static final long AUTHORITY_PARENT_ID = 903L;
    private static final long PARENT_A_ID         = 9000000010L;
    private static final long PARENT_B_ID         = 9000000011L;
    private static final long PLAYER_A1_ID        = 9000000020L;
    private static final long PLAYER_B1_ID        = 9000000021L;

    private static final String PARENT_A_EMAIL = "family.a@skillars-test.com";
    private static final String PARENT_B_EMAIL = "family.b@skillars-test.com";

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
                "VALUES (?, 'ROLE_PARENT', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                AUTHORITY_PARENT_ID, Timestamp.from(Instant.now())
            );
            insertUser(PARENT_A_ID, PARENT_A_EMAIL, passwordHash);
            insertUser(PARENT_B_ID, PARENT_B_EMAIL, passwordHash);
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_PARENT')) ON CONFLICT DO NOTHING",
                PARENT_A_ID
            );
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_PARENT')) ON CONFLICT DO NOTHING",
                PARENT_B_ID
            );
            insertPlayerProfile(PLAYER_A1_ID, "Player A1", PARENT_A_ID);
            insertPlayerProfile(PLAYER_B1_ID, "Player B1", PARENT_B_ID);
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("DELETE FROM main.player_profiles");
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.execute("DELETE FROM main.user_authority");
            jdbcTemplate.execute("DELETE FROM main.\"user\"");
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id = " + AUTHORITY_PARENT_ID);
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void getPlayerProfile_ownPlayer_returns200() {
        String authCookies = loginAndGetCookies(PARENT_A_EMAIL);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + PLAYERS_ENDPOINT + "/" + PLAYER_A1_ID,
            HttpMethod.GET,
            null,
            authenticatedHeaders(authCookies),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKey("id");
        assertThat(Long.parseLong((String) response.getBody().get("id"))).isEqualTo(PLAYER_A1_ID);
    }

    @Test
    void getPlayerProfile_crossFamily_returns403() {
        String parentBCookies = loginAndGetCookies(PARENT_B_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + PLAYERS_ENDPOINT + "/" + PLAYER_A1_ID,
            HttpMethod.GET,
            null,
            authenticatedHeaders(parentBCookies),
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void getPlayerProfile_crossFamily_neverReturns404() {
        String parentBCookies = loginAndGetCookies(PARENT_B_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + PLAYERS_ENDPOINT + "/" + PLAYER_A1_ID,
            HttpMethod.GET,
            null,
            authenticatedHeaders(parentBCookies),
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isNotEqualTo(HttpStatus.NOT_FOUND);
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            });
    }

    @Test
    void getPlayerProfile_unauthenticated_returns401() {
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + PLAYERS_ENDPOINT + "/" + PLAYER_A1_ID,
            HttpMethod.GET,
            null,
            clientHeaders(),
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN));
    }

    @Test
    void listPlayerProfiles_parentSeeOwnPlayersOnly() {
        String parentACookies = loginAndGetCookies(PARENT_A_EMAIL);

        ResponseEntity<List> response = httpTestClient.makeHttpRequest(
            baseUrl() + PLAYERS_ENDPOINT,
            HttpMethod.GET,
            null,
            authenticatedHeaders(parentACookies),
            List.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> players = response.getBody();
        assertThat(players).isNotNull().hasSize(1);

        Map<?, ?> player = (Map<?, ?>) players.get(0);
        assertThat(player.get("name")).isEqualTo("Player A1");
        assertThat(Long.parseLong((String) player.get("id"))).isEqualTo(PLAYER_A1_ID);
    }

    // ---- helpers ----

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

    private void insertUser(long id, String email, String passwordHash) {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" " +
            "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, " +
            "status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, " +
            "activated, locked, login, login_id_type, password_hash, otp_enabled, " +
            "skillars_role, verification_status) " +
            "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, " +
            "'ACTIVE', '1985-06-01', ?, 'Test', 'OTHER', 'en', 'Parent', 'CM', ?, " +
            "true, false, ?, 'EMAIL', ?, false, " +
            "'PARENT', 'BASIC_VERIFIED')",
            id,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            email,
            "657" + (id % 10000000),
            email, passwordHash
        );
    }

    private void insertPlayerProfile(long id, String name, long parentId) {
        jdbcTemplate.update(
            "INSERT INTO main.player_profiles " +
            "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
            "VALUES (?, ?, ?, 'MIDFIELDER', 'ADULT', ?, true, ?, 'system')",
            id, name, Date.valueOf(LocalDate.now().minusYears(20)),
            parentId, Timestamp.from(Instant.now())
        );
    }
}
