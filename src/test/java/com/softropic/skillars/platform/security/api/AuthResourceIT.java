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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestConfig.class)
@TestPropertySource(properties = {
    "spring.cloud.compatibility-verifier.enabled=false",
    "rate.limiting.enabled=false"
})
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class AuthResourceIT {

    private static final String LOGIN_ENDPOINT    = "/api/auth/login";
    private static final String REFRESH_ENDPOINT  = "/api/auth/refresh";
    private static final String LOGOUT_ENDPOINT   = "/api/auth/logout";
    private static final String CLIENT_ID         = "testClientId";

    private static final String COACH_EMAIL    = "coach.test@skillars.com";
    private static final String PARENT_EMAIL   = "parent.test@skillars.com";
    private static final String UNVERIFIED_EMAIL = "unverified.test@skillars.com";
    private static final String TEST_PASSWORD  = "TestPass@123!";

    // Fixed numeric IDs for test users (large, unlikely to collide with TSID range)
    private static final long AUTHORITY_COACH_ID  = 901L;
    private static final long AUTHORITY_PARENT_ID = 902L;
    private static final long COACH_USER_ID       = 9000000001L;
    private static final long PARENT_USER_ID      = 9000000002L;
    private static final long UNVERIFIED_USER_ID  = 9000000003L;

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
                "VALUES (?, 'ROLE_COACH', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                AUTHORITY_COACH_ID, Timestamp.from(Instant.now())
            );
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (?, 'ROLE_PARENT', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                AUTHORITY_PARENT_ID, Timestamp.from(Instant.now())
            );

            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH", "BASIC_VERIFIED", true);
            insertUser(PARENT_USER_ID, PARENT_EMAIL, passwordHash, "PARENT", "BASIC_VERIFIED", true);
            insertUser(UNVERIFIED_USER_ID, UNVERIFIED_EMAIL, passwordHash, "PARENT", "UNVERIFIED", true);

            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_COACH')) " +
                "ON CONFLICT DO NOTHING",
                COACH_USER_ID
            );
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_PARENT')) " +
                "ON CONFLICT DO NOTHING",
                PARENT_USER_ID
            );
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_PARENT')) " +
                "ON CONFLICT DO NOTHING",
                UNVERIFIED_USER_ID
            );
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.execute("DELETE FROM main.user_authority");
            jdbcTemplate.execute("DELETE FROM main.\"user\"");
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id IN ("
                + AUTHORITY_COACH_ID + "," + AUTHORITY_PARENT_ID + ")");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void login_validCoachCredentials_returns200WithRoleAndSetsTokenCookies() {
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + LOGIN_ENDPOINT,
            HttpMethod.POST,
            Map.of("email", COACH_EMAIL, "password", TEST_PASSWORD),
            clientHeaders(),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKey("userId");
        assertThat(response.getBody().get("role")).isEqualTo("COACH");
        assertThat(response.getBody()).containsKey("displayName");

        List<String> setCookies = response.getHeaders().get("Set-Cookie");
        assertThat(setCookies).isNotNull();
        assertThat(setCookies).anyMatch(c -> c.startsWith("rtkn=") && c.contains("HttpOnly"));
        assertThat(setCookies).anyMatch(c -> c.startsWith("skp="));
        assertThat(setCookies).anyMatch(c -> c.startsWith("potc=") && c.contains("HttpOnly"));
    }

    @Test
    void login_validParentCredentials_returnsParentRole() {
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + LOGIN_ENDPOINT,
            HttpMethod.POST,
            Map.of("email", PARENT_EMAIL, "password", TEST_PASSWORD),
            clientHeaders(),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("role")).isEqualTo("PARENT");
    }

    @Test
    void login_invalidPassword_returns401() {
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + LOGIN_ENDPOINT,
            HttpMethod.POST,
            Map.of("email", COACH_EMAIL, "password", "wrongPassword123!"),
            clientHeaders(),
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void login_unknownEmail_returns401() {
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + LOGIN_ENDPOINT,
            HttpMethod.POST,
            Map.of("email", "nobody@skillars.com", "password", TEST_PASSWORD),
            clientHeaders(),
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void login_unverifiedUser_returns403WithAccountNotVerifiedCode() {
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + LOGIN_ENDPOINT,
            HttpMethod.POST,
            Map.of("email", UNVERIFIED_EMAIL, "password", TEST_PASSWORD),
            clientHeaders(),
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(ex.getResponseBodyAsString()).contains("security.accountNotVerified");
            });
    }

    @Test
    void login_rateLimitExceeded_returns429() {
        // AuthService keys on sha256(email|remoteAddr); in tests remoteAddr is 127.0.0.1.
        String identifier = sha256Hex(COACH_EMAIL.toLowerCase() + "|127.0.0.1");
        Instant recent = Instant.now().minus(5, ChronoUnit.MINUTES);
        for (int i = 0; i < 5; i++) {
            jdbcTemplate.update(
                "INSERT INTO main.login_attempts (id, identifier, attempted_at) VALUES (?, ?, ?)",
                9001L + i, identifier, Timestamp.from(recent)
            );
        }

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + LOGIN_ENDPOINT,
            HttpMethod.POST,
            Map.of("email", COACH_EMAIL, "password", TEST_PASSWORD),
            clientHeaders(),
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    @Test
    void refresh_validUnusedToken_rotatesTokenAndReturns200() {
        ResponseEntity<Map> loginResponse = httpTestClient.makeHttpRequest(
            baseUrl() + LOGIN_ENDPOINT,
            HttpMethod.POST,
            Map.of("email", COACH_EMAIL, "password", TEST_PASSWORD),
            clientHeaders(),
            Map.class
        );
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        String rtknCookie = extractCookieValue(loginResponse, "rtkn");
        assertThat(rtknCookie).isNotNull();

        ResponseEntity<Map> refreshResponse = httpTestClient.makeHttpRequest(
            baseUrl() + REFRESH_ENDPOINT,
            HttpMethod.POST,
            null,
            cookieHeaders(rtknCookie),
            Map.class
        );

        assertThat(refreshResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refreshResponse.getBody()).containsKey("userId");

        List<String> setCookies = refreshResponse.getHeaders().get("Set-Cookie");
        assertThat(setCookies).anyMatch(c -> c.startsWith("rtkn=") && c.contains("HttpOnly"));

        long usedCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.refresh_tokens WHERE used = true", Long.class);
        assertThat(usedCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    void refresh_alreadyUsedToken_revokesAllAndReturns401() {
        ResponseEntity<Map> loginResponse = httpTestClient.makeHttpRequest(
            baseUrl() + LOGIN_ENDPOINT,
            HttpMethod.POST,
            Map.of("email", COACH_EMAIL, "password", TEST_PASSWORD),
            clientHeaders(),
            Map.class
        );
        String rtknCookie = extractCookieValue(loginResponse, "rtkn");

        httpTestClient.makeHttpRequest(
            baseUrl() + REFRESH_ENDPOINT,
            HttpMethod.POST,
            null,
            cookieHeaders(rtknCookie),
            Map.class
        );

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + REFRESH_ENDPOINT,
            HttpMethod.POST,
            null,
            cookieHeaders(rtknCookie),
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED));

        long usedCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.refresh_tokens WHERE used = false AND user_id = ?",
            Long.class, COACH_USER_ID);
        assertThat(usedCount).isZero();
    }

    @Test
    void refresh_expiredToken_returns401() {
        String expiredHash = "deadbeef01234567890123456789012345678901234567890123456789012345";
        jdbcTemplate.update(
            "INSERT INTO main.refresh_tokens (id, user_id, token_hash, expires_at, used) " +
            "VALUES (990001, ?, ?, ?, false)",
            COACH_USER_ID, expiredHash, Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS))
        );
        String fakeRaw = "fake-expired-raw-token-value-that-maps-to-nothing-but-hash-set";

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + REFRESH_ENDPOINT,
            HttpMethod.POST,
            null,
            cookieHeaders(fakeRaw),
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void refresh_missingCookie_returns401() {
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + REFRESH_ENDPOINT,
            HttpMethod.POST,
            null,
            clientHeaders(),
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void logout_marksTokenUsedAndClearsCookies() {
        ResponseEntity<Map> loginResponse = httpTestClient.makeHttpRequest(
            baseUrl() + LOGIN_ENDPOINT,
            HttpMethod.POST,
            Map.of("email", COACH_EMAIL, "password", TEST_PASSWORD),
            clientHeaders(),
            Map.class
        );
        String rtknCookie = extractCookieValue(loginResponse, "rtkn");
        assertThat(rtknCookie).isNotNull();

        ResponseEntity<Void> logoutResponse = httpTestClient.makeHttpRequest(
            baseUrl() + LOGOUT_ENDPOINT,
            HttpMethod.POST,
            null,
            cookieHeaders(rtknCookie),
            Void.class
        );

        assertThat(logoutResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        long activeTokens = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.refresh_tokens WHERE used = false AND user_id = ?",
            Long.class, COACH_USER_ID);
        assertThat(activeTokens).isZero();

        List<String> setCookies = logoutResponse.getHeaders().get("Set-Cookie");
        assertThat(setCookies).isNotNull();
        assertThat(setCookies).anyMatch(c -> c.contains("rtkn=") && c.contains("Max-Age=0"));
    }

    private String baseUrl() {
        return "http://localhost:" + randomServerPort;
    }

    private HttpHeaders clientHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(SecurityConstants.API_KEY_HEADER, CLIENT_ID);
        return headers;
    }

    private HttpHeaders cookieHeaders(String rtknValue) {
        HttpHeaders headers = clientHeaders();
        headers.add(HttpHeaders.COOKIE, SecurityConstants.REFRESH_TOKEN_COOKIE + "=" + rtknValue);
        return headers;
    }

    private String extractCookieValue(ResponseEntity<?> response, String cookieName) {
        List<String> setCookies = response.getHeaders().get("Set-Cookie");
        if (setCookies == null) return null;
        return setCookies.stream()
            .filter(c -> c.startsWith(cookieName + "="))
            .map(c -> c.split(";")[0].substring(cookieName.length() + 1))
            .findFirst()
            .orElse(null);
    }

    private static String sha256Hex(String raw) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                                       .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void insertUser(long id, String email, String passwordHash,
                            String skillarsRole, String verificationStatus, boolean activated) {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" (" +
            "  id, login, login_id_type, password_hash, activated, locked, " +
            "  skillars_role, verification_status, " +
            "  first_name, last_name, gender, dob, email, lang_key, status, " +
            "  created_by, created_date, last_modified_by, last_modified_date" +
            ") VALUES (?, ?, 'EMAIL', ?, ?, false, ?, ?, " +
            "  'Test', 'User', 'MALE', '1990-01-01', ?, 'en', 'ACTIVE', " +
            "  'system', ?, 'system', ?)",
            id, email, passwordHash, activated,
            skillarsRole, verificationStatus,
            email,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now())
        );
    }
}
