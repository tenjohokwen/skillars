package com.softropic.skillars.platform.security.api;

import com.softropic.skillars.config.AbstractIntegrationTest;

import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.security.SecurityIT;
import com.softropic.skillars.platform.security.repo.RefreshTokenRepository;

import org.junit.jupiter.api.AfterEach;
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
    private RefreshTokenRepository refreshTokenRepository;

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
        // sent back as an owner-bound entityId (CoachProfileBuilderPlaceholderPage.vue's upload
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
        // force it on here to verify the enforcement path itself still works when enabled.
        configService.updateConfig(PHONE_OTP_REQUIRED_KEY, "true");

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


    //TODO the system should have a single JWT refresh mechanism. The new one just duplicates an already existing one
   /* @Test
    void refresh_alreadyUsedToken_revokesAllAndReturns401() {
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

        // Exhaust the successor so the grace-window path finds no active token,
        // forcing the "revoke all + 401" branch rather than silently redirecting.
        jdbcTemplate.update(
            "UPDATE main.refresh_tokens SET used = true WHERE user_id = ? AND used = false",
            COACH_USER_ID
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
    }*/

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
        // Code review [Patch] 2026-10-05. refresh()'s multi-tab grace-window branch REASSIGNS the
        // local `token` to a live SUCCESSOR row (AuthService.java:158-165) and falls through to the
        // liveness check. terminateSession derives the row to revoke from the raw rtkn cookie on
        // THIS request — the stale, already-used original — so the successor, which is the
        // account's actual live credential, would survive the denial unless the resolved token is
        // revoked explicitly.
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

        long activeTokens = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.refresh_tokens WHERE used = false AND user_id = ?",
            Long.class, COACH_USER_ID);
        assertThat(activeTokens)
            .as("a locked account must be left with no live refresh credential")
            .isZero();
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
