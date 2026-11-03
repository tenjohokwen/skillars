package com.softropic.skillars.platform.security.api;

import com.softropic.skillars.config.AbstractIntegrationTest;

import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.security.SecurityIT;
import com.softropic.skillars.platform.security.contract.Gender;
import com.softropic.skillars.platform.security.contract.event.AccountSuspensionRequestedEvent;
import com.softropic.skillars.platform.security.infrastructure.jwt.TokenCreator;
import com.softropic.skillars.platform.security.service.RefreshTokenRevocationService;
import com.softropic.skillars.infrastructure.util.TestClockProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;

import io.jsonwebtoken.Claims;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.softropic.skillars.infrastructure.security.SecurityConstants.JWT_TTL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class AuthResourceIT extends AbstractIntegrationTest {

    private static final String LOGIN_ENDPOINT    = "/api/auth/login";
    private static final String REFRESH_ENDPOINT  = "/api/auth/refresh";
    private static final String LOGOUT_ENDPOINT   = "/api/auth/logout";
    private static final String CLIENT_ID         = "testClientId";

    private static final String COACH_EMAIL    = "coach.test@skillars.com";
    private static final String PARENT_EMAIL   = "parent.test@skillars.com";
    private static final String UNVERIFIED_EMAIL = "unverified.test@skillars.com";
    // skillars-deferred-143: account-liveness fixtures. LOCKED is the case the story exists for
    // (locked but still activated — UserAdminService.lockUserAccount's shape); DISABLED is the
    // pre-existing isActivated() guard kept as a regression check; ERASED is GdprErasureService's
    // shape (activated=false AND locked=true, GdprErasureService.java:277-278).
    private static final String LOCKED_EMAIL   = "locked.test@skillars.com";
    private static final String DISABLED_EMAIL = "disabled.test@skillars.com";
    private static final String ERASED_EMAIL   = "erased.test@skillars.com";
    private static final String TEST_PASSWORD  = "TestPass@123!";

    // Fixed numeric IDs for test users (large, unlikely to collide with TSID range)
    private static final long AUTHORITY_COACH_ID  = 901L;
    private static final long AUTHORITY_PARENT_ID = 902L;
    private static final long COACH_USER_ID       = 9000000001L;
    private static final long PARENT_USER_ID      = 9000000002L;
    private static final long UNVERIFIED_USER_ID  = 9000000003L;
    private static final long LOCKED_USER_ID      = 9000000004L;
    private static final long DISABLED_USER_ID    = 9000000005L;
    private static final long ERASED_USER_ID      = 9000000006L;
    private static final long CSAM_SELF_PLAYER_PROFILE_ID  = 9000000101L;
    private static final long CSAM_MINOR_PLAYER_PROFILE_ID = 9000000102L;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private HttpTestClient httpTestClient;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ConfigService configService;


    @Autowired
    private TokenCreator tokenCreator;

    // skillars-deferred-166: the chain revoke is what every logout path now depends on, so its durability
    // is asserted directly rather than only through HTTP. A plain @Autowired does not fork a Spring
    // context (the cache key is built from annotations and mock definitions), so this is safe under the
    // AC3 context ceiling that IntegrationTestConventionTest guards.
    @Autowired
    private RefreshTokenRevocationService refreshTokenRevocationService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    private static final String PHONE_OTP_REQUIRED_KEY = "security.registration.phone-otp-required";

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

            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH", "BASIC_VERIFIED", true, false);
            insertUser(PARENT_USER_ID, PARENT_EMAIL, passwordHash, "PARENT", "BASIC_VERIFIED", true, false);
            insertUser(UNVERIFIED_USER_ID, UNVERIFIED_EMAIL, passwordHash, "PARENT", "UNVERIFIED", true, false);
            // skillars-deferred-143 fixtures: activated+locked, deactivated+unlocked, and the
            // GDPR-erasure shape (deactivated+locked).
            insertUser(LOCKED_USER_ID, LOCKED_EMAIL, passwordHash, "PARENT", "BASIC_VERIFIED", true, true);
            insertUser(DISABLED_USER_ID, DISABLED_EMAIL, passwordHash, "PARENT", "BASIC_VERIFIED", false, false);
            insertUser(ERASED_USER_ID, ERASED_EMAIL, passwordHash, "PARENT", "BASIC_VERIFIED", false, true);

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
            jdbcTemplate.update("DELETE FROM main.player_profiles WHERE id IN (?, ?)",
                CSAM_SELF_PLAYER_PROFILE_ID, CSAM_MINOR_PLAYER_PROFILE_ID);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE user_id = ?", COACH_USER_ID);
            jdbcTemplate.execute("DELETE FROM main.user_authority");
            jdbcTemplate.execute("DELETE FROM main.\"user\"");
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id IN ("
                + AUTHORITY_COACH_ID + "," + AUTHORITY_PARENT_ID + ")");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
        configService.updateConfig(PHONE_OTP_REQUIRED_KEY, "false");
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

        // Bug found manually testing a coach photo upload (2026-10-01): `id` here used to be a bare
        // JSON number. CommonConfig.longToStringModule() quotes every OTHER Jackson-serialized Long
        // in this app specifically because JS cannot represent a Tsid-sized long losslessly — this
        // cookie is hand-built JSON (AuthService.login/refresh), so it bypassed that protection.
        // auth.store.js's hydrateFromCookie() (called on every page load) then silently corrupted
        // authStore.userId via IEEE-754 double rounding, which 403'd wherever that value was later
        // sent back as an owner-bound entityId (CoachProfileBuilderPage.vue's upload
        // step). Asserting the literal quoted shape, not just "parses as JSON", is deliberate: a
        // bare-number regression still parses fine, it just silently corrupts large ids.
        String skpCookie = setCookies.stream().filter(c -> c.startsWith("skp=")).findFirst().orElseThrow();
        String skpValue = java.net.URLDecoder.decode(
            skpCookie.substring("skp=".length()).split(";")[0], StandardCharsets.UTF_8);
        assertThat(skpValue).contains("\"id\":\"" + COACH_USER_ID + "\"");
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
        // Phone-OTP enforcement is disabled by default (security.registration.phone-otp-required=false);
        // force it on here to verify the enforcement path itself still works when enabled. Written
        // straight to the table: ConfigService.updateConfig refuses "true" while no SMS gateway exists.
        transactionTemplate.executeWithoutResult(status -> jdbcTemplate.update(
            "UPDATE main.platform_config SET value = 'true', updated_at = NOW() WHERE key = ?", PHONE_OTP_REQUIRED_KEY));
        configService.invalidate();

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


    // TODO ensure there is just 1 system wide rate limit that works
   /* @Test
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
    }*/

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

        // Same hand-built skp cookie as login (AuthService.refresh mirrors AuthService.login here)
        // — see login_validCoachCredentials_returns200WithRoleAndSetsTokenCookies for the full bug.
        String skpCookie = setCookies.stream().filter(c -> c.startsWith("skp=")).findFirst().orElseThrow();
        String skpValue = java.net.URLDecoder.decode(
            skpCookie.substring("skp=".length()).split(";")[0], StandardCharsets.UTF_8);
        assertThat(skpValue).contains("\"id\":\"" + COACH_USER_ID + "\"");

        long usedCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.refresh_tokens WHERE used = true", Long.class);
        assertThat(usedCount).isGreaterThanOrEqualTo(1);
    }

    /**
     * skillars-deferred-144 AC2: a {@code JWTExpiredException} takes
     * {@code JWTAuthorizationFilter}'s routine-denial branch (its own {@code isGenuineDenial}
     * deliberately excludes it — fires on every ordinary 15-minute idle-out). That branch calls
     * {@code loginTokenManager.deleteLoginToken} alone, not {@code securityUtil.terminateSession},
     * and {@code deleteLoginToken} now also clears {@code skp} (this story's AC2), closing the gap
     * where a routine 401 left it standing. Drives a real request through the full filter chain
     * with a genuinely expired JWT — unlike {@code JwtManagerImplTest#testDeleteLoginToken_success}
     * (AC7), which proves the cookie list changed but not that the filter's own 401 response
     * actually expires {@code skp}. Uses {@code /api/auth/login} (not the plain {@code /authenticate}
     * form-login SecurityIT otherwise uses) specifically because only {@code AuthService.login()}
     * (and {@code refresh()}/{@code refreshLoginToken}) set {@code skp} at all — the plain
     * {@code /authenticate} path never does (JwtManagerImpl.createLoginToken has no skp call).
     */
    @Test
    void routineDenial_expiredJwt_alsoClearsSkillarsProfileCookie() {
        ResponseEntity<Map> loginResponse = httpTestClient.makeHttpRequest(
            baseUrl() + LOGIN_ENDPOINT,
            HttpMethod.POST,
            Map.of("email", COACH_EMAIL, "password", TEST_PASSWORD),
            clientHeaders(),
            Map.class
        );
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<String> loginSetCookies = loginResponse.getHeaders().get("Set-Cookie");
        assertThat(loginSetCookies).isNotNull();
        // Precondition for the assertion below to be meaningful — login() always sets 'skp'.
        assertThat(loginSetCookies).anyMatch(c -> c.startsWith("skp="));

        // ClockProvider is a ThreadLocal (ClockProvider.java) and the embedded server handles the
        // login HTTP call above on its OWN worker thread, so offsetting it around that call would
        // have no effect on the JWT the server actually mints. Instead, mint an independent,
        // already-expired JWT directly via TokenCreator on THIS (test) thread — jjwt's parser
        // (ClaimsExtractorImpl) checks expiry against the REAL system clock regardless of which
        // thread minted the token, and shares this app's one JWT signing secret, so the server can
        // verify and then reject it exactly as it would a genuinely stale real-world token. Same
        // underlying technique as JwtManagerImplTest#testExtractPrincipal_expiredToken, adapted for
        // the thread boundary a full HTTP IT introduces. The claims map is built by hand rather
        // than via TokenCreator.toClaims(...), which reads RequestMetadataProvider.getClientInfo()
        // — populated only inside a live HTTP request, which this test-thread call is not.
        Map<String, Object> claims = new HashMap<>();
        claims.put(Claims.SUBJECT, COACH_EMAIL);
        claims.put(SecurityConstants.ROLES, "[{\"authority\":\"ROLE_COACH\"}]");
        claims.put(SecurityConstants.BUS_ID, String.valueOf(COACH_USER_ID));
        claims.put(SecurityConstants.GENDER, Gender.MALE.name());
        claims.put(SecurityConstants.DISPLAY_NAME, "Coach");
        claims.put(SecurityConstants.OTP_ENABLED, false);
        claims.put(SecurityConstants.DB_REFRESH_TOKEN,
            Instant.now().toEpochMilli() + Duration.ofDays(1).toMillis());
        String expiredJwt;
        TestClockProvider.setClock(Clock.offset(TestClockProvider.getClock(),
            Duration.ofSeconds(-(JWT_TTL.toSeconds() + 60))));
        try {
            expiredJwt = tokenCreator.generateTokenFromClaims(claims);
        } finally {
            TestClockProvider.setSystemClock();
        }

        // Keep every other cookie (skp, rtkn, ...) from the real login response as-is; replace only
        // 'potc' with the independently-minted expired JWT.
        List<String> requestCookies = loginSetCookies.stream()
            .map(c -> c.split(";", 2)[0])
            .map(c -> c.startsWith("potc=") ? "potc=" + expiredJwt : c)
            .toList();
        // Precondition for the request below to actually exercise the expired-JWT path: if
        // 'potc' is ever renamed, the substitution above silently no-ops, the request carries no
        // JWT at all, and the SAME routine-denial branch is reached via AccessDeniedException
        // instead — this test would stay green while no longer testing what its name claims.
        assertThat(requestCookies).anyMatch(c -> c.equals("potc=" + expiredJwt));
        String cookieHeaderValue = String.join("; ", requestCookies);

        HttpHeaders authHeaders = clientHeaders();
        authHeaders.add(HttpHeaders.COOKIE, cookieHeaderValue);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/v1/account/", HttpMethod.GET, null, authHeaders, Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                List<String> denialCookies = ex.getResponseHeaders() == null
                    ? null : ex.getResponseHeaders().get(HttpHeaders.SET_COOKIE);
                assertThat(denialCookies).isNotNull();
                assertThat(denialCookies)
                    .as("skp must be expired by the routine-denial branch's own deleteLoginToken call")
                    .anyMatch(c -> c.startsWith("skp=") && c.contains("Max-Age=0"));
            });
    }


    @Test
    void refresh_alreadyUsedToken_revokesAllAndReturns401() {
        // Revived by skillars-deferred-166. The TODO that parked it ("the system should have a single JWT
        // refresh mechanism; the new one just duplicates an already existing one") is about the SPA, which
        // keeps its session alive through GET /refresh and does not call this endpoint at all
        // (src/frontend/src/api/auth.api.js). It says nothing about the endpoint's own contract, which this
        // IT exercises directly and which RS01/RS02 just changed — so the theft branch needs its guard
        // back. The forced `used = true` below leaves rotatedAt null on the successor, so the grace window
        // finds no live successor to redirect to and this is genuine reuse.

        ResponseEntity<Map> loginResponse = httpTestClient.makeHttpRequest(
            baseUrl() + LOGIN_ENDPOINT,
            HttpMethod.POST,
            Map.of("email", COACH_EMAIL, "password", TEST_PASSWORD),
            clientHeaders(),
            Map.class
        );
        String rtknCookie = extractCookieValue(loginResponse, "rtkn");

        // First refresh: RT1 → used, successor RT2 created. Grace window: 30 s.
        httpTestClient.makeHttpRequest(
            baseUrl() + REFRESH_ENDPOINT,
            HttpMethod.POST,
            null,
            cookieHeaders(rtknCookie),
            Map.class
        );

        // Exhaust the successor so the grace-window path finds no live token to redirect to, forcing the
        // "revoke all + 401" branch. Through commitWrite, not a bare jdbcTemplate.update: Hikari runs with
        // auto-commit = false, so a bare write outside a transaction reports its row count and is then
        // rolled back when the connection is released — invisible to the server under test. That is why
        // this test failed on revival: the successor stayed live, so the replay was legitimately redirected
        // to it and returned 200. Exactly the defect skillars-deferred-144 AC5 found in the sibling tests.
        commitWrite("UPDATE main.refresh_tokens SET used = true WHERE user_id = ? AND used = false",
            COACH_USER_ID);

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

        assertThat(liveTokenCountFor(COACH_USER_ID)).isZero();
        assertThat(sessionEpochOf(COACH_USER_ID))
            .as("skillars-deferred-149 AC3: theft detection also bumps the account's session epoch, so a "
                + "standing JWT cannot keep self-extending through the filter's no-DB-hit fast path")
            .isNotNull();
    }

    @Test
    void refresh_expiredToken_returns401() {
        // skillars-deferred-144 AC5: the raw token is chosen FIRST, then its hash derived via this
        // class's own sha256Hex helper — never the reverse. The seeded row and the request's
        // cookie now genuinely refer to the same token, unlike the previous arbitrary hardcoded
        // hex literal (no raw value hashes to it — that would be a preimage attack).
        String rawToken = "expired-refresh-token-raw-value";
        String expiredHash = sha256Hex(rawToken);
        // Routed through commitWrite (not a bare jdbcTemplate.update): spring.datasource.hikari
        // .auto-commit=false means a bare write issued outside a transaction is rolled back when
        // the connection is released, so the row never existed for the server under test to find —
        // which is exactly why this test previously passed for the wrong reason (see AC5).
        commitWrite(
            "INSERT INTO main.refresh_tokens (id, user_id, token_hash, expires_at, used) " +
            "VALUES (990001, ?, ?, ?, false)",
            COACH_USER_ID, expiredHash, Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS))
        );

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + REFRESH_ENDPOINT,
            HttpMethod.POST,
            null,
            cookieHeaders(rawToken),
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED));

        // The real proof the expiry branch (AuthService.java's token.getExpiresAt().isBefore(...))
        // was reached, not the not-found branch: both throw BadCredentialsException with the same
        // HTTP status, but usedFlagOf(expiredHash) is true only on the expiry branch, because its
        // terminateSession call revokes the token in a REQUIRES_NEW transaction that commits
        // independently of this request's own (rolled-back) transaction.
        assertThat(usedFlagOf(expiredHash)).isTrue();
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


    // --- skillars-deferred-143: account-liveness enforcement on login / refresh ----------------

    @Test
    void login_lockedButActivatedUser_returns401WithAccLockedKey() {
        // The case this story exists for: UserAdminService.lockUserAccount() sets locked=true
        // WITHOUT deactivating, so the pre-existing isActivated() guard never caught it and the
        // account could obtain a brand-new session via POST /api/auth/login indefinitely.
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + LOGIN_ENDPOINT,
            HttpMethod.POST,
            Map.of("email", LOCKED_EMAIL, "password", TEST_PASSWORD),
            clientHeaders(),
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                assertThat(ex.getResponseBodyAsString()).contains("security.accLocked");
            });
    }

    @Test
    void login_deactivatedUser_returns401WithAccNotEnabledKey() {
        // Regression guard: the pre-existing isActivated() rejection must keep its outcome after
        // being routed through the shared ensureAccountIsLive helper.
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + LOGIN_ENDPOINT,
            HttpMethod.POST,
            Map.of("email", DISABLED_EMAIL, "password", TEST_PASSWORD),
            clientHeaders(),
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                assertThat(ex.getResponseBodyAsString()).contains("security.accNotEnabled");
            });
    }

    @Test
    void login_gdprErasedUser_returns401() {
        // GdprErasureService.eraseTransactional sets activated=false AND locked=true
        // (GdprErasureService.java:277-278). Deactivation is checked first, so the key is
        // accNotEnabled — asserted explicitly so a later reordering of the two checks is visible.
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + LOGIN_ENDPOINT,
            HttpMethod.POST,
            Map.of("email", ERASED_EMAIL, "password", TEST_PASSWORD),
            clientHeaders(),
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                assertThat(ex.getResponseBodyAsString()).contains("security.accNotEnabled");
            });
    }

    @Test
    void refresh_accountLockedMidSession_returns401AndDurablyRevokesPresentedToken() {
        // AC2's core property. The account is locked AFTER a valid session exists, which is the
        // real-world shape (an admin locks a signed-in user). The rejection throws LockedException
        // out of AuthService.refresh(), rolling back that method's own transaction — so a plain
        // managed save() of used=true would be silently discarded. markUsedByTokenHash's
        // REQUIRES_NEW propagation is what makes the revocation survive, and the only honest way to
        // assert that is to read the row back from the database AFTER the request has completed.
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

        String presentedHash = sha256Hex(rtknCookie);
        assertThat(usedFlagOf(presentedHash)).isFalse();

        commitWrite("UPDATE main.\"user\" SET locked = true WHERE id = ?", COACH_USER_ID);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + REFRESH_ENDPOINT,
            HttpMethod.POST,
            null,
            cookieHeaders(rtknCookie),
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                assertThat(ex.getResponseBodyAsString()).contains("security.accLocked");
            });

        // The presented token must be dead in the DATABASE, not merely "save() was called".
        assertThat(usedFlagOf(presentedHash))
            .as("presented refresh token must be durably revoked despite the rejection rolling "
                + "back AuthService.refresh()'s own transaction")
            .isTrue();

        // And no successor token may have been minted for the locked account.
        long activeTokens = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.refresh_tokens WHERE used = false AND user_id = ?",
            Long.class, COACH_USER_ID);
        assertThat(activeTokens).isZero();
    }

    @Test
    void refresh_accountDeactivatedMidSession_returns401AndRevokesPresentedToken() {
        ResponseEntity<Map> loginResponse = httpTestClient.makeHttpRequest(
            baseUrl() + LOGIN_ENDPOINT,
            HttpMethod.POST,
            Map.of("email", COACH_EMAIL, "password", TEST_PASSWORD),
            clientHeaders(),
            Map.class
        );
        String rtknCookie = extractCookieValue(loginResponse, "rtkn");
        assertThat(rtknCookie).isNotNull();
        String presentedHash = sha256Hex(rtknCookie);

        commitWrite("UPDATE main.\"user\" SET activated = false WHERE id = ?", COACH_USER_ID);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + REFRESH_ENDPOINT,
            HttpMethod.POST,
            null,
            cookieHeaders(rtknCookie),
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                assertThat(ex.getResponseBodyAsString()).contains("security.accNotEnabled");
            });

        assertThat(usedFlagOf(presentedHash)).isTrue();
    }

    @Test
    void refresh_gdprErasedMidSession_returns401AndRevokesPresentedToken() {
        ResponseEntity<Map> loginResponse = httpTestClient.makeHttpRequest(
            baseUrl() + LOGIN_ENDPOINT,
            HttpMethod.POST,
            Map.of("email", COACH_EMAIL, "password", TEST_PASSWORD),
            clientHeaders(),
            Map.class
        );
        String rtknCookie = extractCookieValue(loginResponse, "rtkn");
        assertThat(rtknCookie).isNotNull();
        String presentedHash = sha256Hex(rtknCookie);

        commitWrite(
            "UPDATE main.\"user\" SET activated = false, locked = true WHERE id = ?", COACH_USER_ID);

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

        assertThat(usedFlagOf(presentedHash)).isTrue();
    }

    @Test
    void refresh_accountLockedAndStaleCookieReplayedInGraceWindow_revokesTheSuccessorToken() {
        // Code review [Patch] 2026-10-05, rewritten by skillars-deferred-166. refresh()'s multi-tab
        // grace-window branch resolves the presented stale row to its SUCCESSOR and falls through to the
        // liveness check, so the successor — the account's actual live credential — must not survive the
        // denial. The original fix was an explicit markUsedByTokenHash on the resolved row, because
        // terminateSession could only reach the row the rtkn cookie hashes to (the stale original).
        // terminateSession now revokes the whole chain reachable from that cookie, so it reaches the
        // successor on its own and the explicit call is gone.
        //
        // AC4 additions: assert revoked_at (not just `used`, which rotation also sets), and prove the
        // revocation is scoped to THIS chain by giving the same user a second live session that must
        // survive untouched.
        ResponseEntity<Map> loginResponse = httpTestClient.makeHttpRequest(
            baseUrl() + LOGIN_ENDPOINT,
            HttpMethod.POST,
            Map.of("email", COACH_EMAIL, "password", TEST_PASSWORD),
            clientHeaders(),
            Map.class
        );
        String original = extractCookieValue(loginResponse, "rtkn");
        assertThat(original).isNotNull();

        // One successful refresh: `original` becomes used + rotatedAt, `successor` is minted unused.
        ResponseEntity<Map> firstRefresh = httpTestClient.makeHttpRequest(
            baseUrl() + REFRESH_ENDPOINT,
            HttpMethod.POST,
            null,
            cookieHeaders(original),
            Map.class
        );
        assertThat(firstRefresh.getStatusCode()).isEqualTo(HttpStatus.OK);
        String successor = extractCookieValue(firstRefresh, "rtkn");
        assertThat(successor).isNotNull().isNotEqualTo(original);
        assertThat(usedFlagOf(sha256Hex(original))).isTrue();
        assertThat(usedFlagOf(sha256Hex(successor))).isFalse();

        commitWrite("UPDATE main.\"user\" SET locked = true WHERE id = ?", COACH_USER_ID);

        // Replay the STALE original well inside the 30s grace window, so reuse detection resolves
        // the successor instead of treating the replay as theft.
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + REFRESH_ENDPOINT,
            HttpMethod.POST,
            null,
            cookieHeaders(original),
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                assertThat(ex.getResponseBodyAsString()).contains("security.accLocked");
            });

        assertThat(usedFlagOf(sha256Hex(successor)))
            .as("the successor token the grace-window branch resolved to must be revoked too, "
                + "not just the stale cookie value this request happened to carry")
            .isTrue();
        assertThat(revokedAtOf(sha256Hex(successor)))
            .as("the successor must be REVOKED, not merely marked used — `used` alone cannot tell a "
                + "denied session from a rotated one, which is what let RS01 hide")
            .isNotNull();
        assertThat(revokedAtOf(sha256Hex(original)))
            .as("the presented row is revoked as well: the walk starts there")
            .isNotNull();

        long activeTokens = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.refresh_tokens WHERE used = false AND user_id = ?",
            Long.class, COACH_USER_ID);
        assertThat(activeTokens)
            .as("a locked account must be left with no live refresh credential")
            .isZero();
    }

    // --- skillars-deferred-166: logout revokes the chain (RS01); the grace window follows the
    // --- token's own successor (RS02) -----------------------------------------------------------

    @Test
    void logout_afterTheTokenWasRotated_revokesTheWholeChain() {
        // AC1 / ledger RS01. Before this story, logout revoked the presented row only while it was still
        // unused (markUsedByTokenHash's `AND used = false`), so a refresh a moment earlier left the row
        // already used, the UPDATE matched nothing, and the successor that refresh had just minted stayed
        // live for the rest of its 7-day TTL: clicking Logout did not end the session.
        String original = loginAndGetRtkn(COACH_EMAIL);

        ResponseEntity<Map> firstRefresh = httpTestClient.makeHttpRequest(
            baseUrl() + REFRESH_ENDPOINT, HttpMethod.POST, null, cookieHeaders(original), Map.class);
        String successor = extractCookieValue(firstRefresh, "rtkn");
        assertThat(successor).isNotNull().isNotEqualTo(original);
        assertThat(usedFlagOf(sha256Hex(successor))).isFalse();

        // Log out carrying the STALE cookie, exactly as a tab that refreshed in another window would.
        ResponseEntity<Void> logoutResponse = httpTestClient.makeHttpRequest(
            baseUrl() + LOGOUT_ENDPOINT, HttpMethod.POST, null, cookieHeaders(original), Void.class);
        assertThat(logoutResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(revokedAtOf(sha256Hex(original))).as("the presented row").isNotNull();
        assertThat(revokedAtOf(sha256Hex(successor)))
            .as("the successor minted by the rotation — the token that used to survive logout")
            .isNotNull();
        assertThat(liveTokenCountFor(COACH_USER_ID)).isZero();

        // The deletion headers AC1 asks for, distinguished from a real value: clearAuthCookies always
        // emits `rtkn=; Max-Age=0`, so "no Set-Cookie at all" would be the wrong assertion.
        assertThat(logoutResponse.getHeaders().get("Set-Cookie"))
            .anyMatch(c -> c.startsWith("rtkn=") && c.contains("Max-Age=0"))
            .anyMatch(c -> c.startsWith("skp=") && c.contains("Max-Age=0"));

        // And the successor is genuinely dead at the endpoint, not just in the table.
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + REFRESH_ENDPOINT, HttpMethod.POST, null, cookieHeaders(successor), Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                assertThat(rtknValuesIn(ex.getResponseHeaders()))
                    .as("a rejected refresh may only emit a deletion header, never a usable rtkn")
                    .isEmpty();
            });
    }

    @Test
    void refresh_staleCookieInGraceWindow_redirectsToItsOwnSuccessorAndLeavesTheOtherDeviceAlone() {
        // AC2 / ledger RS02. The grace window used to resolve a stale token via "the newest live token for
        // this user", i.e. across every device. With two sessions that is routinely the OTHER device's
        // token: it was handed to this request and rotated, so the other device's cookie became a used
        // token and its next refresh tripped theft detection and logged the user out everywhere.
        String deviceA = loginAndGetRtkn(COACH_EMAIL);
        String deviceB = loginAndGetRtkn(COACH_EMAIL);
        assertThat(deviceB).isNotEqualTo(deviceA);

        // A refreshes: A0 -> A1.
        ResponseEntity<Map> aRefresh = httpTestClient.makeHttpRequest(
            baseUrl() + REFRESH_ENDPOINT, HttpMethod.POST, null, cookieHeaders(deviceA), Map.class);
        String deviceA1 = extractCookieValue(aRefresh, "rtkn");
        assertThat(deviceA1).isNotNull();

        // A stale request still carrying A0 arrives inside the 30 s window.
        ResponseEntity<Map> staleReplay = httpTestClient.makeHttpRequest(
            baseUrl() + REFRESH_ENDPOINT, HttpMethod.POST, null, cookieHeaders(deviceA), Map.class);
        assertThat(staleReplay.getStatusCode()).isEqualTo(HttpStatus.OK);
        String deviceA2 = extractCookieValue(staleReplay, "rtkn");
        assertThat(deviceA2).isNotNull().isNotIn(deviceA, deviceA1, deviceB);

        // It consumed A's own successor, not B's session.
        assertThat(usedFlagOf(sha256Hex(deviceA1)))
            .as("the redirect must consume the presented token's OWN successor")
            .isTrue();
        assertThat(usedFlagOf(sha256Hex(deviceB)))
            .as("device B's token must be untouched — this is the RS02 regression")
            .isFalse();

        // And B still works afterwards, which is the user-visible half of RS02: before the fix B's next
        // refresh hit the theft branch and revoked every session on the account.
        ResponseEntity<Map> bRefresh = httpTestClient.makeHttpRequest(
            baseUrl() + REFRESH_ENDPOINT, HttpMethod.POST, null, cookieHeaders(deviceB), Map.class);
        assertThat(bRefresh.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(extractCookieValue(bRefresh, "rtkn")).isNotNull();
    }

    @Test
    void refresh_withATokenRevokedByLogout_returns401AndLeavesOtherSessionsAlive() {
        // AC3. A stale tab replaying a logged-out cookie is not theft, and must not escalate to
        // account-wide revocation: before this story a revoked token with no rotatedAt fell into the reuse
        // branch, so one tab's late retry signed the user out of every other device and bumped the
        // account's session epoch.
        String deviceA = loginAndGetRtkn(COACH_EMAIL);
        String deviceB = loginAndGetRtkn(COACH_EMAIL);

        httpTestClient.makeHttpRequest(
            baseUrl() + LOGOUT_ENDPOINT, HttpMethod.POST, null, cookieHeaders(deviceA), Void.class);
        assertThat(revokedAtOf(sha256Hex(deviceA))).isNotNull();

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + REFRESH_ENDPOINT, HttpMethod.POST, null, cookieHeaders(deviceA), Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                assertThat(rtknValuesIn(ex.getResponseHeaders())).isEmpty();
            });

        assertThat(usedFlagOf(sha256Hex(deviceB)))
            .as("the other device's session must survive a replay of a logged-out token")
            .isFalse();
        assertThat(sessionEpochOf(COACH_USER_ID))
            .as("no account-wide revocation: the session epoch must not be bumped")
            .isNull();
    }

    @Test
    void refresh_withATokenOlderThanTheAccountSessionEpoch_returns401() {
        // AC6. markAllUsedByUserId (password change / reset / suspension) is a bulk UPDATE whose snapshot
        // cannot include a successor row a concurrent refresh commits after the statement starts, so a
        // revoke-all could leave one live token behind. Comparing the token's age against
        // security_session_invalidated_at (V158) refuses it on first use regardless of what the bulk
        // update saw — the same guard V158 gave JWTs.
        String rtkn = loginAndGetRtkn(COACH_EMAIL);

        // The token predates a revocation that happened a minute ago.
        commitWrite("UPDATE main.refresh_tokens SET created_at = now() - interval '1 hour' "
            + "WHERE token_hash = ?", sha256Hex(rtkn));
        commitWrite("UPDATE main.\"user\" SET security_session_invalidated_at = now() - interval '1 minute' "
            + "WHERE id = ?", COACH_USER_ID);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + REFRESH_ENDPOINT, HttpMethod.POST, null, cookieHeaders(rtkn), Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                assertThat(rtknValuesIn(ex.getResponseHeaders())).isEmpty();
            });

        // This one session is ended; the epoch is NOT re-bumped (no second account-wide revocation).
        assertThat(revokedAtOf(sha256Hex(rtkn))).isNotNull();
    }

    @Test
    void refresh_tokenMintedAfterTheSessionEpoch_stillWorks() {
        // The other half of AC6: the epoch must not lock a legitimate re-login out of its own session.
        commitWrite("UPDATE main.\"user\" SET security_session_invalidated_at = now() - interval '1 hour' "
            + "WHERE id = ?", COACH_USER_ID);

        String rtkn = loginAndGetRtkn(COACH_EMAIL);
        ResponseEntity<Map> refreshed = httpTestClient.makeHttpRequest(
            baseUrl() + REFRESH_ENDPOINT, HttpMethod.POST, null, cookieHeaders(rtkn), Map.class);

        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(extractCookieValue(refreshed, "rtkn")).isNotNull();
    }

    @Test
    void refresh_twoConcurrentRefreshesOfTheSameToken_neverTripTheftDetection() throws Exception {
        // AC7. Two tabs POST /refresh with the same cookie at the same instant. The presented row is now
        // locked PESSIMISTIC_WRITE, so they serialise: the winner rotates, and the loser reads committed
        // state (used, a fresh rotatedAt, a written replaced_by_id) and takes the grace redirect to the
        // real successor. The property that matters is that a legitimate multi-tab race is never read as
        // theft — and that nothing 500s on the lock.
        String rtkn = loginAndGetRtkn(COACH_EMAIL);

        // Its own JDK-connection RestTemplate, for the reason the login-burst test above documents: a
        // parallel burst through the shared pooled client can starve it for later tests.
        RestTemplate client = new RestTemplateBuilder()
            .requestFactory(SimpleClientHttpRequestFactory.class)
            .build();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<HttpStatusCode>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        return client.exchange(baseUrl() + REFRESH_ENDPOINT, HttpMethod.POST,
                            new HttpEntity<>(null, cookieHeaders(rtkn)), Map.class).getStatusCode();
                    } catch (HttpClientErrorException e) {
                        return e.getStatusCode();
                    }
                }));
            }
            start.countDown();
            List<HttpStatusCode> statuses = new ArrayList<>();
            for (Future<HttpStatusCode> f : results) {
                statuses.add(f.get(60, TimeUnit.SECONDS));
            }

            assertThat(statuses)
                .as("a concurrent refresh resolves to 200 (rotated, or redirected to the successor) or "
                    + "401 (the optimistic-lock backstop) — never a 5xx from the row lock")
                .allMatch(st -> st.equals(HttpStatus.OK) || st.equals(HttpStatus.UNAUTHORIZED));
            assertThat(liveTokenCountFor(COACH_USER_ID))
                .as("theft detection would have revoked every token for the user, leaving none live")
                .isEqualTo(1);
            assertThat(sessionEpochOf(COACH_USER_ID))
                .as("a multi-tab race must not bump the account's revocation epoch")
                .isNull();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void refresh_threeConcurrentRefreshesOfTheSameToken_neverTripTheftDetection() throws Exception {
        // The regression guard for the chain-depth bound. resolveGraceSuccessor's first version stopped
        // after two hops, so a third client refreshing the same cookie inside one 30 s window walked
        // T0 -> T1(used) -> T2(used), ran out of hops, and was answered as theft: every refresh token for
        // the account revoked and the JWT session epoch bumped. The query it replaced found the live token
        // however deep the chain went, so stopping early was a regression, and AC7's two-thread test could
        // not see it.
        String rtkn = loginAndGetRtkn(COACH_EMAIL);

        RestTemplate client = new RestTemplateBuilder()
            .requestFactory(SimpleClientHttpRequestFactory.class)
            .build();
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<HttpStatusCode>> results = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        return client.exchange(baseUrl() + REFRESH_ENDPOINT, HttpMethod.POST,
                            new HttpEntity<>(null, cookieHeaders(rtkn)), Map.class).getStatusCode();
                    } catch (HttpClientErrorException e) {
                        return e.getStatusCode();
                    }
                }));
            }
            start.countDown();
            List<HttpStatusCode> statuses = new ArrayList<>();
            for (Future<HttpStatusCode> f : results) {
                statuses.add(f.get(60, TimeUnit.SECONDS));
            }

            assertThat(statuses).allMatch(st -> st.equals(HttpStatus.OK) || st.equals(HttpStatus.UNAUTHORIZED));
            assertThat(sessionEpochOf(COACH_USER_ID))
                .as("three tabs refreshing one cookie is a race, not theft: the account's revocation epoch "
                    + "must not be bumped")
                .isNull();
            assertThat(liveTokenCountFor(COACH_USER_ID))
                .as("theft revocation would leave zero live tokens; a handled race leaves exactly one")
                .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void logout_withAChainLongerThanTheHopBound_leavesNothingTradeable() {
        // The hop bound used to fail OPEN: the walk logged a warning and returned, leaving the rows beyond
        // it `used = false, revoked_at = null` — ordinary live tokens that refresh would keep rotating, so
        // the logged-out session continued indefinitely. That is ledger RS01 again, reached through the
        // bound. It now falls back to revoking every token for the account.
        String head = loginAndGetRtkn(COACH_EMAIL);

        // Ten rotations, so the chain from `head` is longer than MAX_CHAIN_HOPS (8).
        String cursor = head;
        for (int i = 0; i < 10; i++) {
            ResponseEntity<Map> rotated = httpTestClient.makeHttpRequest(
                baseUrl() + REFRESH_ENDPOINT, HttpMethod.POST, null, cookieHeaders(cursor), Map.class);
            assertThat(rotated.getStatusCode()).isEqualTo(HttpStatus.OK);
            cursor = extractCookieValue(rotated, "rtkn");
            assertThat(cursor).isNotNull();
        }
        final String tail = cursor;
        assertThat(usedFlagOf(sha256Hex(tail))).isFalse();

        ResponseEntity<Void> logoutResponse = httpTestClient.makeHttpRequest(
            baseUrl() + LOGOUT_ENDPOINT, HttpMethod.POST, null, cookieHeaders(head), Void.class);
        assertThat(logoutResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(liveTokenCountFor(COACH_USER_ID))
            .as("no token may remain tradeable after the logout, bound or no bound")
            .isZero();
        assertThat(revokedAtOf(sha256Hex(tail)))
            .as("the fallback must STAMP revoked_at, not just `used`: a row left used-but-not-revoked reads "
                + "as 'rotated, successor unknown', so the next refresh escalates to a second account-wide "
                + "revocation instead of the plain 401 an ended session gets")
            .isNotNull();
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + REFRESH_ENDPOINT, HttpMethod.POST, null, cookieHeaders(tail), Map.class))
            .as("the tail beyond the hop bound must not be rotatable")
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getResponseBodyAsString())
                .as("and it is an ended session, not theft")
                .doesNotContain("reuse"));
        assertThat(sessionEpochOf(COACH_USER_ID))
            .as("so no second account-wide revocation is triggered by replaying the tail")
            .isNull();
    }

    @Test
    void revokeChainByTokenHash_commitsEvenWhenTheCallingTransactionRollsBack() {
        // The property every logout path now rests on, tested in isolation from HTTP. AuthService.refresh's
        // denial paths revoke and then throw, which rolls their own transaction back; the revoke survives
        // only because this service is REQUIRES_NEW. The sibling test below asserts the same thing for
        // markUsedByTokenHash, which since skillars-deferred-166 has no production caller — so without
        // this test the durability of the revoke that actually runs would be unasserted, and a future
        // change to the service's propagation would break every denial silently.
        String rtkn = loginAndGetRtkn(COACH_EMAIL);
        ResponseEntity<Map> firstRefresh = httpTestClient.makeHttpRequest(
            baseUrl() + REFRESH_ENDPOINT, HttpMethod.POST, null, cookieHeaders(rtkn), Map.class);
        String successor = extractCookieValue(firstRefresh, "rtkn");
        assertThat(successor).isNotNull();

        transactionTemplate.execute(status -> {
            refreshTokenRevocationService.revokeChainByTokenHash(sha256Hex(rtkn));
            status.setRollbackOnly();
            return null;
        });

        assertThat(revokedAtOf(sha256Hex(rtkn)))
            .as("the presented row stays revoked after the caller's transaction rolled back")
            .isNotNull();
        assertThat(revokedAtOf(sha256Hex(successor)))
            .as("and so does the successor the walk reached")
            .isNotNull();
        assertThat(liveTokenCountFor(COACH_USER_ID)).isZero();
    }

    @Test
    void logoutAndRefreshRacingOnTheSameToken_leaveNoLiveTokenInThatChain() throws Exception {
        // AC5 / ledger RS01, the race the item is really about. Whichever order the two land in, the chain
        // must end up dead: refresh-then-logout means the walk follows replaced_by_id to the successor;
        // logout-then-refresh means refresh sees revoked_at and refuses without minting anything. Before
        // this story the first order left the successor live for its full TTL.
        String rtkn = loginAndGetRtkn(COACH_EMAIL);

        RestTemplate client = new RestTemplateBuilder()
            .requestFactory(SimpleClientHttpRequestFactory.class)
            .build();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<String> refreshTask = pool.submit(() -> {
                start.await();
                try {
                    return extractCookieValue(client.exchange(baseUrl() + REFRESH_ENDPOINT, HttpMethod.POST,
                        new HttpEntity<>(null, cookieHeaders(rtkn)), Map.class), "rtkn");
                } catch (HttpClientErrorException e) {
                    return null;
                }
            });
            Future<HttpStatusCode> logoutTask = pool.submit(() -> {
                start.await();
                try {
                    return client.exchange(baseUrl() + LOGOUT_ENDPOINT, HttpMethod.POST,
                        new HttpEntity<>(null, cookieHeaders(rtkn)), Void.class).getStatusCode();
                } catch (HttpClientErrorException e) {
                    return e.getStatusCode();
                }
            });
            start.countDown();

            String mintedByRefresh = refreshTask.get(60, TimeUnit.SECONDS);
            assertThat(logoutTask.get(60, TimeUnit.SECONDS)).isEqualTo(HttpStatus.NO_CONTENT);

            assertThat(liveTokenCountFor(COACH_USER_ID))
                .as("no live refresh credential may outlive the logout, in either order")
                .isZero();

            // And if refresh did win the race, the token it handed out is genuinely rejected now.
            if (mintedByRefresh != null) {
                assertThat(revokedAtOf(sha256Hex(mintedByRefresh))).isNotNull();
                assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
                    baseUrl() + REFRESH_ENDPOINT, HttpMethod.POST, null,
                    cookieHeaders(mintedByRefresh), Map.class))
                    .isInstanceOf(HttpClientErrorException.class);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void markUsedByTokenHash_commitsEvenWhenTheCallingTransactionRollsBack() {
        // The single property the whole AC2 redesign exists to guarantee, tested in isolation from
        // the HTTP layer. markUsedByTokenHash is @Transactional(REQUIRES_NEW) precisely so that
        // AuthService.refresh()'s revoke-then-throw rejection path keeps the revocation after its
        // own transaction is rolled back. Here the rollback is forced explicitly via
        // setRollbackOnly, so a regression to plain @Transactional (or to a managed save()) fails
        // this test instead of passing silently.
        String hash = sha256Hex("rollback-probe-token");
        commitWrite(
            "INSERT INTO main.refresh_tokens (id, user_id, token_hash, expires_at, used, version) " +
            "VALUES (990002, ?, ?, ?, false, 0)",
            COACH_USER_ID, hash, Timestamp.from(Instant.now().plus(1, ChronoUnit.DAYS))
        );

        transactionTemplate.execute(status -> {
            refreshTokenRepository.markUsedByTokenHash(hash);
            status.setRollbackOnly();
            return null;
        });

        assertThat(usedFlagOf(hash))
            .as("REQUIRES_NEW revocation must survive the caller's rollback")
            .isTrue();
    }

    // --- fix/security-session-revocation ------------------------------------------------------

    @Test
    void csamSuspension_ofACoachUpload_resolvesTheCoachProfileIdAndRevokesEverySession() {
        // Video.ownerId for a coach upload is the CoachProfile UUID (VideoResource /
        // DrillUploadService). The listener used to look that string up as a login/email and so
        // never found anyone: every real CSAM match ended in "user not found".
        UUID coachProfileId = UUID.randomUUID();
        commitWrite("INSERT INTO marketplace.coach_profiles "
            + "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) "
            + "VALUES (?, ?, 'CSAM Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
            coachProfileId, COACH_USER_ID);

        assertCsamSuspensionRevokesSessions(coachProfileId.toString(), COACH_EMAIL, COACH_USER_ID);
    }

    @Test
    void csamSuspension_ofAPlayerUpload_resolvesThePlayerProfileIdToItsOwnAccount() {
        // Player HOMEWORK uploads store the uploader's own PlayerProfile id (self-registered,
        // user_id set) as Video.ownerId.
        commitWrite("INSERT INTO main.player_profiles (id, name, date_of_birth, position, age_tier, "
            + "user_id, independent_account_allowed, created_at, created_by) VALUES "
            + "(?, 'CSAM Player', '1990-01-01', 'MIDFIELDER', 'ADULT', ?, true, now(), 'system')",
            CSAM_SELF_PLAYER_PROFILE_ID, PARENT_USER_ID);

        assertCsamSuspensionRevokesSessions(String.valueOf(CSAM_SELF_PLAYER_PROFILE_ID), PARENT_EMAIL, PARENT_USER_ID);
    }

    @Test
    void csamSuspension_ofAManagedMinorProfile_suspendsTheParentAccount() {
        // A parent-managed minor has no login of its own (user_id NULL); the parent is the only
        // account controlling that profile, so it is the one suspended.
        commitWrite("INSERT INTO main.player_profiles (id, name, date_of_birth, position, age_tier, "
            + "parent_id, independent_account_allowed, created_at, created_by) VALUES "
            + "(?, 'CSAM Minor', ?, 'MIDFIELDER', 'AGE_10_12', ?, false, now(), 'system')",
            CSAM_MINOR_PLAYER_PROFILE_ID, java.sql.Date.valueOf(java.time.LocalDate.now().minusYears(11)),
            PARENT_USER_ID);

        assertCsamSuspensionRevokesSessions(String.valueOf(CSAM_MINOR_PLAYER_PROFILE_ID), PARENT_EMAIL, PARENT_USER_ID);
    }

    @Test
    void csamSuspension_byLogin_stillResolves() {
        assertCsamSuspensionRevokesSessions(COACH_EMAIL, COACH_EMAIL, COACH_USER_ID);
    }

    /**
     * Logs {@code email} in, publishes the CSAM suspension for {@code ownerId}, then asserts the
     * account is SUSPENDED + locked, every JWT is invalidated, every refresh token is revoked, and
     * neither refresh nor login works any more.
     */
    private void assertCsamSuspensionRevokesSessions(String ownerId, String email, long userId) {
        configService.updateConfig(PHONE_OTP_REQUIRED_KEY, "false");
        ResponseEntity<Map> loginResponse = httpTestClient.makeHttpRequest(
            baseUrl() + LOGIN_ENDPOINT, HttpMethod.POST,
            Map.of("email", email, "password", TEST_PASSWORD), clientHeaders(), Map.class);
        String rtknCookie = extractCookieValue(loginResponse, "rtkn");
        assertThat(rtknCookie).isNotNull();

        eventPublisher.publishEvent(new AccountSuspensionRequestedEvent(ownerId, UUID.randomUUID()));

        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT verification_status, locked, security_session_invalidated_at FROM main.\"user\" WHERE id = ?",
            userId);
        assertThat(row.get("verification_status")).isEqualTo("SUSPENDED");
        assertThat(row.get("locked")).isEqualTo(true);
        assertThat(row.get("security_session_invalidated_at"))
            .as("standing JWTs must be invalidated, not just the refresh tokens")
            .isNotNull();
        long liveTokens = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.refresh_tokens WHERE used = false AND user_id = ?",
            Long.class, userId);
        assertThat(liveTokens).isZero();

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + REFRESH_ENDPOINT, HttpMethod.POST, null, cookieHeaders(rtknCookie), Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + LOGIN_ENDPOINT, HttpMethod.POST,
            Map.of("email", email, "password", TEST_PASSWORD), clientHeaders(), Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                assertThat(ex.getResponseBodyAsString()).contains("security.accLocked");
            });
    }

    @Test
    void suspendedStatusAlone_blocksLoginWhenPhoneOtpIsNotRequired() {
        // SUSPENDED but not locked (e.g. a pre-fix suspension, or `locked` cleared by another path):
        // the status itself must refuse a session, independent of the phone-otp flag.
        configService.updateConfig(PHONE_OTP_REQUIRED_KEY, "false");
        commitWrite("UPDATE main.\"user\" SET verification_status = 'SUSPENDED', locked = false WHERE id = ?",
            PARENT_USER_ID);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + LOGIN_ENDPOINT, HttpMethod.POST,
            Map.of("email", PARENT_EMAIL, "password", TEST_PASSWORD), clientHeaders(), Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                assertThat(ex.getResponseBodyAsString()).contains("security.accLocked");
            });
    }

    @Test
    void springSecurityLogoutEndpoint_revokesTheSessionsRefreshToken() {
        // POST /api/logout (Spring Security's LogoutFilter) used to only delete cookies, leaving the
        // refresh token usable for its whole TTL.
        configService.updateConfig(PHONE_OTP_REQUIRED_KEY, "false");
        ResponseEntity<Map> loginResponse = httpTestClient.makeHttpRequest(
            baseUrl() + LOGIN_ENDPOINT, HttpMethod.POST,
            Map.of("email", COACH_EMAIL, "password", TEST_PASSWORD), clientHeaders(), Map.class);
        String rtknCookie = extractCookieValue(loginResponse, "rtkn");
        assertThat(rtknCookie).isNotNull();

        ResponseEntity<Void> logoutResponse = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/logout", HttpMethod.POST, null, cookieHeaders(rtknCookie), Void.class);

        assertThat(logoutResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(usedFlagOf(sha256Hex(rtknCookie))).isTrue();
        assertThat(logoutResponse.getHeaders().get("Set-Cookie"))
            .anyMatch(c -> c.startsWith("rtkn=") && c.contains("Max-Age=0"));
    }

    @Test
    void springSecurityLogoutEndpoint_ignoresGet_soACrossSiteLinkCannotRevokeTheSession() {
        configService.updateConfig(PHONE_OTP_REQUIRED_KEY, "false");
        ResponseEntity<Map> loginResponse = httpTestClient.makeHttpRequest(
            baseUrl() + LOGIN_ENDPOINT, HttpMethod.POST,
            Map.of("email", COACH_EMAIL, "password", TEST_PASSWORD), clientHeaders(), Map.class);
        String rtknCookie = extractCookieValue(loginResponse, "rtkn");

        try {
            httpTestClient.makeHttpRequest(
                baseUrl() + "/api/logout", HttpMethod.GET, null, cookieHeaders(rtknCookie), Void.class);
        } catch (HttpClientErrorException ignored) {
            // Whatever GET answers, it must not be the logout.
        }

        assertThat(usedFlagOf(sha256Hex(rtknCookie))).isFalse();
    }

    @Test
    void parallelWrongPasswords_neverRecordMoreFailuresThanTheBudget() throws Exception {
        // LoginRateLimiter.checkLimited claims a transaction-scoped advisory try-lock per
        // SHA-256(email|ip). Before that, every request in a parallel burst read a count below the
        // budget and then inserted its own failure, so the budget was exceeded by the degree of
        // parallelism. Now each attempt holds the key while it counts and records (401), waits a
        // bounded time for a busy key, or is turned away (429) without being recorded — because the
        // budget is used up or the key stayed busy. Follow-up sequential attempts then fill the
        // budget exactly, never beyond it.
        long maxAttempts = configService.find("security.login.max-attempts").map(Long::parseLong).orElse(5L);
        int burst = (int) maxAttempts * 3;
        // Its own JDK-connection RestTemplate: a burst through the shared pooled Apache client
        // (5 connections per route) can starve that pool for later tests.
        RestTemplate client = new RestTemplateBuilder()
            .requestFactory(SimpleClientHttpRequestFactory.class)
            .build();
        ExecutorService pool = Executors.newFixedThreadPool(burst);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<HttpStatusCode>> results = new ArrayList<>();
            for (int i = 0; i < burst; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        return wrongPasswordAttempt(client);
                    } catch (HttpClientErrorException e) {
                        return e.getStatusCode();
                    }
                }));
            }
            start.countDown();
            List<HttpStatusCode> statuses = new ArrayList<>();
            for (Future<HttpStatusCode> f : results) {
                statuses.add(f.get(60, TimeUnit.SECONDS));
            }

            long recordedByBurst = loginAttemptCountFor(COACH_EMAIL);
            assertThat(recordedByBurst)
                .as("failed attempts recorded for a parallel burst of %d wrong passwords", burst)
                .isBetween(1L, maxAttempts);
            assertThat(statuses).allMatch(st -> st.equals(HttpStatus.UNAUTHORIZED) || st.equals(HttpStatus.TOO_MANY_REQUESTS));
            assertThat(statuses).filteredOn(HttpStatus.UNAUTHORIZED::equals).hasSize((int) recordedByBurst);

            for (int i = 0; i < maxAttempts; i++) {
                try {
                    wrongPasswordAttempt(client);
                } catch (HttpClientErrorException expected) {
                    // 401 until the budget is used up, then 429
                }
            }
            assertThat(loginAttemptCountFor(COACH_EMAIL)).isEqualTo(maxAttempts);
        } finally {
            pool.shutdownNow();
        }
    }

    private HttpStatusCode wrongPasswordAttempt(RestTemplate client) {
        return client.exchange(baseUrl() + LOGIN_ENDPOINT, HttpMethod.POST,
            new HttpEntity<>(Map.of("email", COACH_EMAIL, "password", "wrongPassword123!"), clientHeaders()),
            Map.class).getStatusCode();
    }

    private long loginAttemptCountFor(String email) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.login_attempts WHERE subject_hash = ?",
            Long.class, sha256Hex(email.toLowerCase()));
    }

    /**
     * Applies a write and COMMITS it. Required, not stylistic: {@code spring.datasource.hikari
     * .auto-commit} is {@code false} (application.yaml:183, so Hibernate can group statements into
     * one transaction), which means a bare {@code jdbcTemplate} write issued outside a transaction
     * reports its row count and is then rolled back when the connection is released — invisible to
     * the application under test. Every write in this class therefore goes through
     * {@code transactionTemplate}, as {@link #setUp()} already does.
     */
    private void commitWrite(String sql, Object... args) {
        transactionTemplate.execute(status -> jdbcTemplate.update(sql, args));
    }

    /** skillars-deferred-166: the {@code revoked_at} stamp, which tells a denied session from a rotated one. */
    private Instant revokedAtOf(String tokenHash) {
        Timestamp stamp = jdbcTemplate.queryForObject(
            "SELECT revoked_at FROM main.refresh_tokens WHERE token_hash = ?", Timestamp.class, tokenHash);
        return stamp == null ? null : stamp.toInstant();
    }

    /** Rows that can still be traded in for a session: unused and not revoked. */
    private long liveTokenCountFor(long userId) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.refresh_tokens WHERE used = false AND revoked_at IS NULL "
                + "AND user_id = ?", Long.class, userId);
    }

    /** The account-wide revocation epoch (V158); null unless a revoke-all has run. */
    private Instant sessionEpochOf(long userId) {
        Timestamp stamp = jdbcTemplate.queryForObject(
            "SELECT security_session_invalidated_at FROM main.\"user\" WHERE id = ?",
            Timestamp.class, userId);
        return stamp == null ? null : stamp.toInstant();
    }

    /** Logs in and returns the issued {@code rtkn}; one call per simulated device. */
    private String loginAndGetRtkn(String email) {
        ResponseEntity<Map> loginResponse = httpTestClient.makeHttpRequest(
            baseUrl() + LOGIN_ENDPOINT, HttpMethod.POST,
            Map.of("email", email, "password", TEST_PASSWORD), clientHeaders(), Map.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String rtkn = extractCookieValue(loginResponse, "rtkn");
        assertThat(rtkn).isNotNull();
        return rtkn;
    }

    /**
     * The NON-EMPTY {@code rtkn} values in a response, i.e. usable credentials. Deletion headers
     * ({@code rtkn=; Max-Age=0}) are excluded, which is the distinction AC1/AC3/AC5 turn on:
     * {@code clearAuthCookies} emits a deletion header on every teardown, so "no Set-Cookie for rtkn"
     * would be the wrong assertion and "some Set-Cookie for rtkn" the wrong one too.
     */
    private List<String> rtknValuesIn(HttpHeaders headers) {
        List<String> setCookies = headers == null ? null : headers.get("Set-Cookie");
        if (setCookies == null) {
            return List.of();
        }
        return setCookies.stream()
            .filter(c -> c.startsWith(SecurityConstants.REFRESH_TOKEN_COOKIE + "="))
            .map(c -> c.split(";")[0].substring(SecurityConstants.REFRESH_TOKEN_COOKIE.length() + 1))
            .filter(v -> !v.isBlank())
            .toList();
    }

    /** Reads the {@code used} flag straight out of the database for the given token hash. */
    private boolean usedFlagOf(String tokenHash) {
        return jdbcTemplate.queryForObject(
            "SELECT used FROM main.refresh_tokens WHERE token_hash = ?", Boolean.class, tokenHash);
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

    /**
     * skillars-deferred-143: {@code locked} was previously hardcoded {@code false} in this insert,
     * which made the account-lock enforcement path untestable from here.
     */
    private void insertUser(long id, String email, String passwordHash,
                            String skillarsRole, String verificationStatus,
                            boolean activated, boolean locked) {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" (" +
            "  id, login, login_id_type, password_hash, activated, locked, " +
            "  skillars_role, verification_status, " +
            "  first_name, last_name, gender, dob, email, lang_key, status, " +
            "  created_by, created_date, last_modified_by, last_modified_date" +
            ") VALUES (?, ?, 'EMAIL', ?, ?, ?, ?, ?, " +
            "  'Test', 'User', 'MALE', '1990-01-01', ?, 'en', 'ACTIVE', " +
            "  'system', ?, 'system', ?)",
            id, email, passwordHash, activated, locked,
            skillarsRole, verificationStatus,
            email,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now())
        );
    }
}
