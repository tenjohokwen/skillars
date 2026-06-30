package com.softropic.skillars.platform.admin.api;

import com.softropic.skillars.config.TestConfig;
import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.gemini.GeminiClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.filestorage.service.FileStorageService;
import com.softropic.skillars.platform.security.SecurityIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestConfig.class)
@TestPropertySource(properties = {
    "spring.cloud.compatibility-verifier.enabled=false",
    "rate.limiting.enabled=false",
    "allowed.clients=testClientId"
})
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class GdprExportIT {

    private static final String LOGIN_ENDPOINT  = "/api/auth/login";
    private static final String EXPORT_URL      = "/api/gdpr/export";
    private static final String CLIENT_ID       = "testClientId";
    private static final String TEST_PASSWORD   = "TestPass@123!";
    private static final String MOCK_DOWNLOAD_URL = "https://mock-cdn.skillars-test.com/gdpr-export.zip";

    private static final long PARENT_ID       = 9200_000_001L;
    private static final long PARENT_B_ID     = 9200_000_002L;

    private static final String PARENT_EMAIL   = "gdpr.parent.9200@skillars-test.com";
    private static final String PARENT_B_EMAIL = "gdpr.parentb.9200@skillars-test.com";

    @MockitoBean
    private GeminiClient geminiClient;

    @MockitoBean
    private FileStorageService fileStorageService;

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private PasswordEncoder passwordEncoder;

    @LocalServerPort private int randomServerPort;

    @BeforeEach
    void setUp() {
        Mockito.when(fileStorageService.signedDownloadUrl(anyString(), any()))
            .thenReturn(MOCK_DOWNLOAD_URL);

        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) VALUES (9200, 'ROLE_PARENT', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));

            insertUser(PARENT_ID, PARENT_EMAIL, passwordHash, "PARENT");
            grantAuthority(PARENT_ID, "ROLE_PARENT");

            insertUser(PARENT_B_ID, PARENT_B_EMAIL, passwordHash, "PARENT");
            grantAuthority(PARENT_B_ID, "ROLE_PARENT");

            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM admin.gdpr_requests WHERE user_id IN (?, ?)", PARENT_ID, PARENT_B_ID);
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?)", PARENT_ID, PARENT_B_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?)", PARENT_ID, PARENT_B_ID);
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id = 9200");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void requestExport_parentUser_returns202WithRequestId() {
        String cookies = loginAndGetCookies(PARENT_EMAIL);
        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            baseUrl() + EXPORT_URL,
            HttpMethod.POST, null, authenticatedHeaders(cookies), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(resp.getBody()).containsKey("requestId");

        UUID requestId = UUID.fromString((String) resp.getBody().get("requestId"));
        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT status FROM admin.gdpr_requests WHERE id = ?", requestId);
        assertThat(row.get("status")).isIn("COMPLETED", "PROCESSING", "PENDING");
    }

    @Test
    void requestExport_duplicatePending_returns409() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO admin.gdpr_requests (id, user_id, request_type, status, created_at) VALUES (?, ?, 'EXPORT', 'PENDING', ?)",
                UUID.randomUUID(), PARENT_ID, Timestamp.from(Instant.now()));
            return null;
        });

        String cookies = loginAndGetCookies(PARENT_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + EXPORT_URL, HttpMethod.POST, null, authenticatedHeaders(cookies), Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void getExportStatus_pendingRequest_returns200WithStatus() {
        UUID requestId = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO admin.gdpr_requests (id, user_id, request_type, status, created_at) VALUES (?, ?, 'EXPORT', 'PENDING', ?)",
                requestId, PARENT_ID, Timestamp.from(Instant.now()));
            return null;
        });

        String cookies = loginAndGetCookies(PARENT_EMAIL);
        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            baseUrl() + EXPORT_URL + "/" + requestId,
            HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("status")).isEqualTo("PENDING");
        assertThat(resp.getBody().get("requestId")).isEqualTo(requestId.toString());
    }

    @Test
    void getExportStatus_completedRequest_returns302Redirect() {
        UUID requestId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(24, ChronoUnit.HOURS);
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO admin.gdpr_requests (id, user_id, request_type, status, created_at, completed_at, download_url, expires_at) VALUES (?, ?, 'EXPORT', 'COMPLETED', ?, ?, ?, ?)",
                requestId, PARENT_ID, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
                MOCK_DOWNLOAD_URL, Timestamp.from(expiresAt));
            return null;
        });

        String cookies = loginAndGetCookies(PARENT_EMAIL);
        ResponseEntity<String> resp = httpTestClient.makeHttpRequest(
            baseUrl() + EXPORT_URL + "/" + requestId,
            HttpMethod.GET, null, authenticatedHeaders(cookies), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(resp.getHeaders().getLocation().toString()).isEqualTo(MOCK_DOWNLOAD_URL);
    }

    @Test
    void getExportStatus_expiredRequest_returns410() {
        UUID requestId = UUID.randomUUID();
        Instant expiredAt = Instant.now().minus(1, ChronoUnit.HOURS);
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO admin.gdpr_requests (id, user_id, request_type, status, created_at, completed_at, download_url, expires_at) VALUES (?, ?, 'EXPORT', 'COMPLETED', ?, ?, ?, ?)",
                requestId, PARENT_ID, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
                MOCK_DOWNLOAD_URL, Timestamp.from(expiredAt));
            return null;
        });

        String cookies = loginAndGetCookies(PARENT_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + EXPORT_URL + "/" + requestId,
            HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.GONE));
    }

    @Test
    void getExportStatus_otherUsersRequest_returns403() {
        UUID requestId = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO admin.gdpr_requests (id, user_id, request_type, status, created_at) VALUES (?, ?, 'EXPORT', 'PENDING', ?)",
                requestId, PARENT_ID, Timestamp.from(Instant.now()));
            return null;
        });

        String cookies = loginAndGetCookies(PARENT_B_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + EXPORT_URL + "/" + requestId,
            HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void requestExport_unauthenticated_returns401() {
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + EXPORT_URL, HttpMethod.POST, null, clientHeaders(), Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    // ── helpers ──

    private String loginAndGetCookies(String email) {
        ResponseEntity<Map> loginResponse = httpTestClient.makeHttpRequest(
            baseUrl() + LOGIN_ENDPOINT,
            HttpMethod.POST,
            Map.of("email", email, "password", TEST_PASSWORD),
            clientHeaders(), Map.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> setCookies = loginResponse.getHeaders().get("Set-Cookie");
        assertThat(setCookies).isNotNull();
        return setCookies.stream().map(c -> c.split(";")[0]).reduce((a, b) -> a + "; " + b).orElseThrow();
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

    private void insertUser(long id, String email, String passwordHash, String role) {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" (id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, activated, locked, login, login_id_type, password_hash, otp_enabled, skillars_role, verification_status) VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, 'ACTIVE', '1985-06-01', ?, 'Test', 'OTHER', 'en', ?, 'DE', ?, true, false, ?, 'EMAIL', ?, false, ?, 'BASIC_VERIFIED')",
            id, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            email, role, "920" + (id % 10000000), email, passwordHash, role);
    }

    private void grantAuthority(long userId, String roleName) {
        jdbcTemplate.update(
            "INSERT INTO main.user_authority (user_id, authority_id) VALUES (?, (SELECT id FROM main.authority WHERE name = ?)) ON CONFLICT DO NOTHING",
            userId, roleName);
    }
}
