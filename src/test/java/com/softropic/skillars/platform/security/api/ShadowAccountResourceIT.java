package com.softropic.skillars.platform.security.api;

import com.softropic.skillars.config.AbstractIntegrationTest;

import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.SecurityIT;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AC4/AC5 — no resource-level IT existed yet for {@link ShadowAccountResource} (confirmed: only the
 * service-level {@code ShadowAccountServiceIT} existed before this story), unlike the sibling
 * {@code PlayerRegistrationResourceIT}. Covers the new {@code PATCH /me/position} (AC4) and
 * {@code PATCH /{playerId}/position} (AC5) endpoints end-to-end, including the cross-family
 * isolation 403.
 */
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class ShadowAccountResourceIT extends AbstractIntegrationTest {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String PLAYERS_BASE = "/api/security/players";
    private static final String CLIENT_ID = "testClientId";
    private static final String TEST_PASSWORD = "PlayerPass@123!";

    private static final long PARENT_A_ID = 9200000001L;
    private static final long PARENT_B_ID = 9200000002L;
    private static final long SELF_PLAYER_ID = 9200000003L;
    private static final long COACH_ID = 9200000004L;
    private static final String PARENT_A_EMAIL = "parent.a.shadow@skillars-test.com";
    private static final String PARENT_B_EMAIL = "parent.b.shadow@skillars-test.com";
    private static final String SELF_PLAYER_EMAIL = "self.player.shadow@skillars-test.com";
    private static final String COACH_EMAIL = "coach.shadow@skillars-test.com";

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
                "VALUES (9200, 'ROLE_PARENT', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now())
            );
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (9201, 'ROLE_PLAYER', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now())
            );
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (9202, 'ROLE_COACH', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now())
            );
            insertUser(PARENT_A_ID, PARENT_A_EMAIL, passwordHash, "PARENT");
            insertUser(PARENT_B_ID, PARENT_B_EMAIL, passwordHash, "PARENT");
            insertUser(SELF_PLAYER_ID, SELF_PLAYER_EMAIL, passwordHash, "PLAYER");
            insertUser(COACH_ID, COACH_EMAIL, passwordHash, "COACH");
            grantAuthority(PARENT_A_ID, "ROLE_PARENT");
            grantAuthority(PARENT_B_ID, "ROLE_PARENT");
            grantAuthority(SELF_PLAYER_ID, "ROLE_PLAYER");
            grantAuthority(COACH_ID, "ROLE_COACH");
            return null;
        });
    }

    // ---- AC4: PATCH /me/position ----

    @Test
    void updateOwnPosition_asPlayer_returns200WithNewPosition() {
        String cookies = loginAndGetCookies(SELF_PLAYER_EMAIL);
        createSelfProfile(cookies, "GOALKEEPER");

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + PLAYERS_BASE + "/me/position",
            HttpMethod.PATCH,
            Map.of("position", "FORWARD"),
            authenticatedHeaders(cookies),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("position")).isEqualTo("FORWARD");
    }

    @Test
    void updateOwnPosition_asCoach_returns403() {
        String cookies = loginAndGetCookies(COACH_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + PLAYERS_BASE + "/me/position",
            HttpMethod.PATCH,
            Map.of("position", "FORWARD"),
            authenticatedHeaders(cookies),
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ---- AC5: PATCH /{playerId}/position ----

    @Test
    void updateChildPosition_ownChild_returns200() {
        String parentCookies = loginAndGetCookies(PARENT_A_EMAIL);
        long childId = createChild(parentCookies, "Child One", "DEFENDER");

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + PLAYERS_BASE + "/" + childId + "/position",
            HttpMethod.PATCH,
            Map.of("position", "MIDFIELDER"),
            authenticatedHeaders(parentCookies),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("position")).isEqualTo("MIDFIELDER");
    }

    @Test
    void updateChildPosition_otherParentsChild_returns403() {
        String parentACookies = loginAndGetCookies(PARENT_A_EMAIL);
        long childId = createChild(parentACookies, "Child Of A", "DEFENDER");

        String parentBCookies = loginAndGetCookies(PARENT_B_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + PLAYERS_BASE + "/" + childId + "/position",
            HttpMethod.PATCH,
            Map.of("position", "FORWARD"),
            authenticatedHeaders(parentBCookies),
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void updateChildPosition_asPlayer_returns403() {
        String parentCookies = loginAndGetCookies(PARENT_A_EMAIL);
        long childId = createChild(parentCookies, "Child Two", "DEFENDER");

        String playerCookies = loginAndGetCookies(SELF_PLAYER_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + PLAYERS_BASE + "/" + childId + "/position",
            HttpMethod.PATCH,
            Map.of("position", "FORWARD"),
            authenticatedHeaders(playerCookies),
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ---- helpers ----

    private void createSelfProfile(String cookies, String position) {
        httpTestClient.makeHttpRequest(
            baseUrl() + PLAYERS_BASE + "/me",
            HttpMethod.POST,
            Map.of("position", position),
            authenticatedHeaders(cookies),
            Map.class
        );
    }

    private long createChild(String parentCookies, String name, String position) {
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + PLAYERS_BASE,
            HttpMethod.POST,
            Map.of(
                "name", name,
                "dateOfBirth", "1995-06-01",
                "position", position
            ),
            authenticatedHeaders(parentCookies),
            Map.class
        );
        return Long.parseLong(String.valueOf(response.getBody().get("id")));
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

    private void grantAuthority(long userId, String authorityName) {
        jdbcTemplate.update(
            "INSERT INTO main.user_authority (user_id, authority_id) " +
            "VALUES (?, (SELECT id FROM main.authority WHERE name = ?)) ON CONFLICT DO NOTHING",
            userId, authorityName
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
            "'ACTIVE', '1990-03-15', ?, 'Test', 'OTHER', 'en', 'User', 'DE', ?, " +
            "true, false, ?, 'EMAIL', ?, false, " +
            "?, 'BASIC_VERIFIED')",
            id,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            email,
            "670" + (id % 10000000),
            email, passwordHash, role
        );
    }
}
