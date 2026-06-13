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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
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
class SanitizePreviewResourceIT {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String PREVIEW_ENDPOINT = "/api/util/sanitize-preview";
    private static final String CLIENT_ID = "testClientId";
    private static final String TEST_PASSWORD = "CoachPass@123!";
    private static final long COACH_ID = 9100000003L;
    private static final String COACH_EMAIL = "coach.sanitize@skillars-test.com";

    @LocalServerPort
    private int randomServerPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private HttpTestClient httpTestClient;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (9200, 'ROLE_COACH', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now())
            );
            jdbcTemplate.update(
                "INSERT INTO main.\"user\" " +
                "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, " +
                "status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, " +
                "activated, locked, login, login_id_type, password_hash, otp_enabled, " +
                "skillars_role, verification_status) " +
                "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, " +
                "'ACTIVE', '1990-03-15', ?, 'Test', 'OTHER', 'en', 'Coach', 'DE', ?, " +
                "true, false, ?, 'EMAIL', ?, false, " +
                "'COACH', 'BASIC_VERIFIED')",
                COACH_ID,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
                COACH_EMAIL,
                "6709100003",
                COACH_EMAIL,
                passwordHash
            );
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_COACH')) ON CONFLICT DO NOTHING",
                COACH_ID
            );
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM main.refresh_tokens WHERE user_id = ?", COACH_ID);
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id = ?", COACH_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id = ?", COACH_ID);
            jdbcTemplate.update("DELETE FROM main.authority WHERE id = ?", 9200);
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void preview_emailDetected_returnsDetectionFound() {
        String cookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Map> res = httpTestClient.makeHttpRequest(
            baseUrl() + PREVIEW_ENDPOINT,
            HttpMethod.POST,
            Map.of("text", "Contact me at coach@example.com"),
            authenticatedHeaders(cookies),
            Map.class
        );

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Boolean) res.getBody().get("detectionFound")).isTrue();
        assertThat((String) res.getBody().get("original")).isEqualTo("Contact me at coach@example.com");
        assertThat((String) res.getBody().get("sanitized")).contains("[contact details removed]");
    }

    @Test
    void preview_cleanText_returnsNotDetected() {
        String cookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Map> res = httpTestClient.makeHttpRequest(
            baseUrl() + PREVIEW_ENDPOINT,
            HttpMethod.POST,
            Map.of("text", "Certified football coach in Berlin"),
            authenticatedHeaders(cookies),
            Map.class
        );

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Boolean) res.getBody().get("detectionFound")).isFalse();
    }

    @Test
    void preview_unauthenticated_returns401() {
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + PREVIEW_ENDPOINT,
            HttpMethod.POST,
            Map.of("text", "test"),
            clientHeaders(),
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void preview_nullText_returns400() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        Map<String, Object> nullTextBody = new HashMap<>();
        nullTextBody.put("text", null);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + PREVIEW_ENDPOINT,
            HttpMethod.POST,
            nullTextBody,
            authenticatedHeaders(cookies),
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST));
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
}
