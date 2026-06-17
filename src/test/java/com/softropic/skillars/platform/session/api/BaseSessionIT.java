package com.softropic.skillars.platform.session.api;

import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

abstract class BaseSessionIT {

    protected static final String LOGIN_ENDPOINT = "/api/auth/login";
    protected static final String DRILLS_BASE    = "/api/session/drills";
    protected static final String CLIENT_ID      = "testClientId";
    protected static final String TEST_PASSWORD  = "TestPass@123!";

    @Autowired protected JdbcTemplate jdbcTemplate;
    @Autowired protected TransactionTemplate transactionTemplate;
    @Autowired protected HttpTestClient httpTestClient;
    @Autowired protected PasswordEncoder passwordEncoder;

    @LocalServerPort protected int randomServerPort;

    protected String loginAndGetCookies(String email) {
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

    protected HttpHeaders clientHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(SecurityConstants.API_KEY_HEADER, CLIENT_ID);
        return headers;
    }

    protected HttpHeaders authenticatedHeaders(String cookieValue) {
        HttpHeaders headers = clientHeaders();
        headers.add(HttpHeaders.COOKIE, cookieValue);
        return headers;
    }

    protected String baseUrl() {
        return "http://localhost:" + randomServerPort;
    }

    protected void insertAuthority(int id, String name) {
        jdbcTemplate.update(
            "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
            "VALUES (?, ?, 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
            id, name, Timestamp.from(Instant.now())
        );
    }

    protected void insertUser(long id, String email, String passwordHash, String role) {
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
            "69" + (id % 100000000),
            email, passwordHash, role
        );
    }

    protected void grantRole(long userId, String roleName) {
        jdbcTemplate.update(
            "INSERT INTO main.user_authority (user_id, authority_id) " +
            "VALUES (?, (SELECT id FROM main.authority WHERE name = ?)) ON CONFLICT DO NOTHING",
            userId, roleName
        );
    }

    protected UUID insertCoachProfile(long userId) {
        UUID profileId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO marketplace.coach_profiles " +
            "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
            "VALUES (?, ?, 'Test Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
            profileId, userId
        );
        return profileId;
    }

    protected void insertSubscription(UUID coachId, String tier) {
        jdbcTemplate.update(
            "INSERT INTO marketplace.coach_subscriptions (coach_id, tier, active_since) " +
            "VALUES (?, ?, NOW()) ON CONFLICT DO NOTHING",
            coachId, tier
        );
    }

    protected void insertDrill(UUID id, String name, String libraryType, UUID ownerCoachId, String status) {
        jdbcTemplate.update(
            "INSERT INTO session.drills (id, name, library_type, owner_coach_id, status, metadata, version) " +
            "VALUES (?, ?, ?, ?, ?, ?::jsonb, 0)",
            id, name, libraryType, ownerCoachId, status,
            "{\"primarySkills\":[\"dribbling\"],\"secondarySkills\":[],\"skillWeighting\":{\"dribbling\":100}," +
            "\"repDensity\":10,\"intensity\":2,\"pressureLevel\":1,\"cognitiveLoad\":1,\"matchRealism\":2," +
            "\"weakFootBias\":false,\"difficultyTier\":\"U12\",\"equipmentRequired\":[\"ball\"]," +
            "\"recommendedGroupSize\":\"2\",\"coachingPoints\":[\"Keep it simple\"]}"
        );
    }
}
