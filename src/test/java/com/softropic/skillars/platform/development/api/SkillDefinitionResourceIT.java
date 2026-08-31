package com.softropic.skillars.platform.development.api;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.SecurityIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.web.client.HttpClientErrorException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Story Deferred-84 AC4: SkillDefinitionResource now delegates to SkillDefinitionService (was
// injecting the repository + mapper directly — the one resource in this module that did). This IT
// pins the endpoint's externally-visible behaviour, which had zero test coverage at any layer before.
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class SkillDefinitionResourceIT extends AbstractIntegrationTest {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String CLIENT_ID      = "testClientId";
    private static final String TEST_PASSWORD  = "TestPass@123!";

    private static final long USER_ID = 9651000001L;
    private static final String USER_EMAIL = "skilldef.user@skillars-test.com";

    // Test-owned skill rows: no inactive skill exists in the V46 seed data (all 15 default active).
    private static final String ACTIVE_CODE   = "ZZA";
    private static final String INACTIVE_CODE = "ZZZ";

    @Autowired private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        transactionTemplate.execute(status -> {
            insertAuthority(9651, "ROLE_PARENT");
            insertUser(USER_ID, USER_EMAIL, passwordHash, "PARENT");
            grantRole(USER_ID, "ROLE_PARENT");

            jdbcTemplate.update(
                "INSERT INTO development.skill_definitions (code, display_name, display_order, active) " +
                "VALUES (?, 'Test Active Skill', 900, true)", ACTIVE_CODE);
            jdbcTemplate.update(
                "INSERT INTO development.skill_definitions (code, display_name, display_order, active) " +
                "VALUES (?, 'Test Inactive Skill', 901, false)", INACTIVE_CODE);
            return null;
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void getSkillDefinitions_authenticated_returnsActiveSkillsOnly() {
        String cookies = loginAndGetCookies(USER_EMAIL);

        ResponseEntity<List> response = httpTestClient.makeHttpRequest(
            skillDefinitionsUrl(), HttpMethod.GET, null, authenticatedHeaders(cookies), List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> body = response.getBody();
        assertThat(body).isNotNull();
        List<String> codes = body.stream().map(s -> (String) s.get("code")).toList();
        // Relative assertions — the endpoint returns all active skills (15 seeded + our active row),
        // not just the two this test controls.
        assertThat(codes).contains(ACTIVE_CODE, "PAC");
        assertThat(codes).doesNotContain(INACTIVE_CODE);
    }

    @Test
    void getSkillDefinitions_unauthenticated_returns401() {
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            skillDefinitionsUrl(), HttpMethod.GET, null, clientHeaders(), List.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String skillDefinitionsUrl() {
        return baseUrl() + "/api/development/skill-definitions";
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
