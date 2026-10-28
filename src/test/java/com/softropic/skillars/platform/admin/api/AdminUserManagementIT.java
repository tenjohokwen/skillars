package com.softropic.skillars.platform.admin.api;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.SecurityIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpStatusCodeException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * feat/admin-moderation-and-enforcement D7 / A10: {@code /api/admin/users} — search, detail,
 * suspend / reactivate any non-admin account (incl. session revocation), and login rate-limit unlock.
 * Same seeding / login shape as {@link ReinstateIT}; no mocks, so it shares the canonical context.
 */
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class AdminUserManagementIT extends AbstractIntegrationTest {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String REFRESH_ENDPOINT = "/api/auth/refresh";
    private static final String CLIENT_ID = "testClientId";
    private static final String TEST_PASSWORD = "TestPass@123!";

    private static final long ADMIN_ID = 9171_000_100L;
    private static final long OTHER_ADMIN_ID = 9171_000_101L;
    private static final long PARENT_ID = 9171_000_010L;
    private static final long COACH_ID = 9171_000_020L;
    private static final UUID COACH_PROFILE_ID = UUID.fromString("9171aaaa-0000-4000-8000-000000000020");

    private static final String ADMIN_EMAIL = "admin.users9171@skillars-test.com";
    private static final String OTHER_ADMIN_EMAIL = "admin2.users9171@skillars-test.com";
    private static final String PARENT_EMAIL = "parent.users9171@skillars-test.com";
    private static final String COACH_EMAIL = "coach.users9171@skillars-test.com";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) VALUES (9171, 'ROLE_PARENT', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) VALUES (9172, 'ROLE_ADMIN', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));

            insertUser(PARENT_ID, PARENT_EMAIL, passwordHash, "PARENT", "Paula", "Parentson");
            grantAuthority(PARENT_ID, "ROLE_PARENT");
            insertUser(ADMIN_ID, ADMIN_EMAIL, passwordHash, "ADMIN", "Ada", "Admin");
            grantAuthority(ADMIN_ID, "ROLE_ADMIN");
            insertUser(OTHER_ADMIN_ID, OTHER_ADMIN_EMAIL, passwordHash, "ADMIN", "Otto", "Admin");
            grantAuthority(OTHER_ADMIN_ID, "ROLE_ADMIN");
            insertUser(COACH_ID, COACH_EMAIL, passwordHash, "COACH", "Cora", "Coach");
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles (id, user_id, display_name, bio, city, languages, canonical_timezone, status, status_changed_at) "
                    + "VALUES (?, ?, 'Cora Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE', ?)",
                COACH_PROFILE_ID, COACH_ID, Timestamp.from(Instant.now()));
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM admin.admin_action_log WHERE reference_id IN (?, ?, ?)",
                String.valueOf(PARENT_ID), String.valueOf(ADMIN_ID), String.valueOf(OTHER_ADMIN_ID));
            jdbcTemplate.update("DELETE FROM admin.gdpr_requests WHERE user_id = ?", PARENT_ID);
            jdbcTemplate.update("DELETE FROM admin.admin_action_log WHERE reference_id IN (?, ?)",
                String.valueOf(COACH_ID), COACH_PROFILE_ID.toString());
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id = ?", COACH_PROFILE_ID);
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?, ?, ?)", PARENT_ID, ADMIN_ID, OTHER_ADMIN_ID, COACH_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?, ?, ?)", PARENT_ID, ADMIN_ID, OTHER_ADMIN_ID, COACH_ID);
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id IN (9171, 9172)");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void search_byNameFragment_returnsMatchingUser_andIsAdminOnly() {
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        ResponseEntity<Map> resp = call(
            baseUrl() + "/api/admin/users?q=Parentson", HttpMethod.GET, null,
            authenticatedHeaders(adminCookies), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> content = (List<Map<String, Object>>) resp.getBody().get("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0).get("email")).isEqualTo(PARENT_EMAIL);
        assertThat(content.get(0).get("userId")).isEqualTo(String.valueOf(PARENT_ID));
        assertThat(content.get(0).get("suspended")).isEqualTo(false);

        String parentCookies = loginAndGetCookies(PARENT_EMAIL);
        ResponseEntity<Map> denied = call(
            baseUrl() + "/api/admin/users?q=admin", HttpMethod.GET, null,
            authenticatedHeaders(parentCookies), Map.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void search_likeWildcardsInQuery_areMatchedLiterally() {
        // '_' would match the '.' in "parent.users9171@..." if it were not escaped as a literal.
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        ResponseEntity<Map> resp = call(
            baseUrl() + "/api/admin/users?q=parent_users9171", HttpMethod.GET, null,
            authenticatedHeaders(adminCookies), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) resp.getBody().get("content")).isEmpty();
    }

    @Test
    void suspend_locksAccount_revokesSessions_andAudits() {
        String parentCookies = loginAndGetCookies(PARENT_EMAIL);
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);

        ResponseEntity<Void> resp = suspend(PARENT_ID, adminCookies, "Abusive messages");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT locked FROM main.\"user\" WHERE id = ?", Boolean.class, PARENT_ID)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT security_session_invalidated_at FROM main.\"user\" WHERE id = ?", Timestamp.class, PARENT_ID))
            .isNotNull();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.refresh_tokens WHERE user_id = ? AND used = false", Long.class, PARENT_ID))
            .isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT reason FROM admin.admin_action_log WHERE reference_id = ? AND action_type = 'USER_SUSPEND' AND admin_id = ?",
            String.class, String.valueOf(PARENT_ID), ADMIN_ID)).isEqualTo("Abusive messages");

        // The suspended user's existing refresh token no longer mints a session, and a fresh login fails.
        ResponseEntity<Map> refresh = call(
            baseUrl() + REFRESH_ENDPOINT, HttpMethod.POST, null, authenticatedHeaders(parentCookies), Map.class);
        assertThat(refresh.getStatusCode().is4xxClientError()).isTrue();
        ResponseEntity<Map> login = rawLogin(PARENT_EMAIL, TEST_PASSWORD);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void suspend_coachAccount_alsoSuspendsTheMarketplaceProfile_reactivateLeavesItSuspended() {
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);

        assertThat(suspend(COACH_ID, adminCookies, "Fraud").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM marketplace.coach_profiles WHERE id = ?", String.class, COACH_PROFILE_ID))
            .isEqualTo("SUSPENDED");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT reason FROM admin.admin_action_log WHERE reference_id = ? AND action_type = 'COACH_SUSPEND'",
            String.class, COACH_PROFILE_ID.toString())).isEqualTo("Account suspended: Fraud");

        assertThat(reactivate(COACH_ID, adminCookies, "ok").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM marketplace.coach_profiles WHERE id = ?", String.class, COACH_PROFILE_ID))
            .isEqualTo("SUSPENDED");
    }

    @Test
    void suspend_isIdempotent_secondCallWritesNoSecondAuditRow() {
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        assertThat(suspend(PARENT_ID, adminCookies, "first").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(suspend(PARENT_ID, adminCookies, "second").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM admin.admin_action_log WHERE reference_id = ? AND action_type = 'USER_SUSPEND'",
            Long.class, String.valueOf(PARENT_ID))).isEqualTo(1L);
    }

    @Test
    void concurrentSuspends_serialiseOnTheRowLock_exactlyOneAuditRow() throws Exception {
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<HttpStatus> call = () -> {
            start.await();
            return HttpStatus.valueOf(suspend(PARENT_ID, adminCookies, "race").getStatusCode().value());
        };
        try {
            Future<HttpStatus> a = pool.submit(call);
            Future<HttpStatus> b = pool.submit(call);
            start.countDown();
            assertThat(a.get(30, TimeUnit.SECONDS)).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(b.get(30, TimeUnit.SECONDS)).isEqualTo(HttpStatus.NO_CONTENT);
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM admin.admin_action_log WHERE reference_id = ? AND action_type = 'USER_SUSPEND'",
            Long.class, String.valueOf(PARENT_ID))).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT locked FROM main.\"user\" WHERE id = ?", Boolean.class, PARENT_ID)).isTrue();
    }

    @Test
    void suspend_anotherAdmin_isRefusedWith409_andSelfWith400() {
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);

        assertThat(suspend(OTHER_ADMIN_ID, adminCookies, "nope").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(suspend(ADMIN_ID, adminCookies, "nope").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.\"user\" WHERE id IN (?, ?) AND locked = true", Long.class, ADMIN_ID, OTHER_ADMIN_ID))
            .isZero();
    }

    @Test
    void reactivateAndUnlock_ofAnAdminAccount_areRefusedWith409() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("UPDATE main.\"user\" SET locked = true WHERE id = ?", OTHER_ADMIN_ID);
            return null;
        });
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);

        assertThat(reactivate(OTHER_ADMIN_ID, adminCookies, "undo").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(
            baseUrl() + "/api/admin/users/" + OTHER_ADMIN_ID + "/unlock-login", HttpMethod.POST, null,
            authenticatedHeaders(adminCookies), Map.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT locked FROM main.\"user\" WHERE id = ?", Boolean.class, OTHER_ADMIN_ID)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM admin.admin_action_log WHERE reference_id = ?", Long.class,
            String.valueOf(OTHER_ADMIN_ID))).isZero();
    }

    @Test
    void suspend_coachAccountAlreadyLockedElsewhere_stillSuspendsTheMarketplaceProfile() {
        // e.g. locked by the legacy UserAdminService.lockUserAccount path: locked=true, profile ACTIVE.
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("UPDATE main.\"user\" SET locked = true WHERE id = ?", COACH_ID);
            return null;
        });
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);

        assertThat(suspend(COACH_ID, adminCookies, "Fraud").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM marketplace.coach_profiles WHERE id = ?", String.class, COACH_PROFILE_ID))
            .isEqualTo("SUSPENDED");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM admin.admin_action_log WHERE reference_id = ? AND action_type = 'USER_SUSPEND'",
            Long.class, String.valueOf(COACH_ID))).isZero();
    }

    @Test
    void suspend_withoutReason_isRejected() {
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        assertThat(suspend(PARENT_ID, adminCookies, "").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void reactivate_unlocksAccount_andLoginWorksAgain() {
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        suspend(PARENT_ID, adminCookies, "temporary");

        ResponseEntity<Void> resp = reactivate(PARENT_ID, adminCookies, "Appeal accepted");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT locked FROM main.\"user\" WHERE id = ?", Boolean.class, PARENT_ID)).isFalse();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM admin.admin_action_log WHERE reference_id = ? AND action_type = 'USER_REACTIVATE'",
            Long.class, String.valueOf(PARENT_ID))).isEqualTo(1L);
        assertThat(rawLogin(PARENT_EMAIL, TEST_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void reactivate_accountWithGdprErasure_isRefusedWith409() {
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        suspend(PARENT_ID, adminCookies, "temporary");
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO admin.gdpr_requests (id, user_id, request_type, status, created_at, retry_count) VALUES (?, ?, 'ERASURE', 'COMPLETED', ?, 0)",
                UUID.randomUUID(), PARENT_ID, Timestamp.from(Instant.now()));
            return null;
        });

        assertThat(reactivate(PARENT_ID, adminCookies, "undo").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT locked FROM main.\"user\" WHERE id = ?", Boolean.class, PARENT_ID)).isTrue();
    }

    @Test
    void failedLogin_isPersistedWithSubjectHash_andUnlockClearsIt() {
        // AuthService.login fix: the failed-attempt row used to be rolled back with the
        // BadCredentialsException it recorded, so the DB rate limit could never engage.
        assertThat(rawLogin(PARENT_EMAIL, "WrongPass@123!").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rawLogin(PARENT_EMAIL, "WrongPass@123!").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.login_attempts WHERE subject_hash = ?", Long.class, sha256(PARENT_EMAIL)))
            .isEqualTo(2L);

        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        ResponseEntity<Map> detail = call(
            baseUrl() + "/api/admin/users/" + PARENT_ID, HttpMethod.GET, null,
            authenticatedHeaders(adminCookies), Map.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) detail.getBody().get("recentFailedLoginAttempts")).longValue()).isEqualTo(2L);
        assertThat(detail.getBody().get("privileged")).isEqualTo(false);

        ResponseEntity<Map> unlock = call(
            baseUrl() + "/api/admin/users/" + PARENT_ID + "/unlock-login", HttpMethod.POST, null,
            authenticatedHeaders(adminCookies), Map.class);
        assertThat(unlock.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) unlock.getBody().get("clearedAttempts")).intValue()).isEqualTo(2);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.login_attempts WHERE subject_hash = ?", Long.class, sha256(PARENT_EMAIL)))
            .isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM admin.admin_action_log WHERE reference_id = ? AND action_type = 'USER_LOGIN_UNLOCK'",
            Long.class, String.valueOf(PARENT_ID))).isEqualTo(1L);
    }

    @Test
    void detail_includesActionHistory_andMarksAdminsPrivileged() {
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        suspend(PARENT_ID, adminCookies, "history check");

        ResponseEntity<Map> detail = call(
            baseUrl() + "/api/admin/users/" + PARENT_ID, HttpMethod.GET, null,
            authenticatedHeaders(adminCookies), Map.class);
        assertThat(detail.getBody().get("suspended")).isEqualTo(true);
        List<Map<String, Object>> history = (List<Map<String, Object>>) detail.getBody().get("history");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("actionType")).isEqualTo("USER_SUSPEND");
        assertThat(history.get(0).get("reason")).isEqualTo("history check");

        ResponseEntity<Map> adminDetail = call(
            baseUrl() + "/api/admin/users/" + OTHER_ADMIN_ID, HttpMethod.GET, null,
            authenticatedHeaders(adminCookies), Map.class);
        assertThat(adminDetail.getBody().get("privileged")).isEqualTo(true);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private ResponseEntity<Void> suspend(long userId, String cookies, String reason) {
        return call(
            baseUrl() + "/api/admin/users/" + userId + "/suspend", HttpMethod.POST,
            Map.of("reason", reason), authenticatedHeaders(cookies), Void.class);
    }

    private ResponseEntity<Void> reactivate(long userId, String cookies, String reason) {
        return call(
            baseUrl() + "/api/admin/users/" + userId + "/reactivate", HttpMethod.POST,
            Map.of("reason", reason), authenticatedHeaders(cookies), Void.class);
    }

    private ResponseEntity<Map> rawLogin(String email, String password) {
        return call(
            baseUrl() + LOGIN_ENDPOINT, HttpMethod.POST,
            Map.of("email", email, "password", password), clientHeaders(), Map.class);
    }

    private String loginAndGetCookies(String email) {
        ResponseEntity<Map> loginResponse = rawLogin(email, TEST_PASSWORD);
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

    private void insertUser(long id, String email, String passwordHash, String role, String first, String last) {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" (id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, activated, locked, login, login_id_type, password_hash, otp_enabled, skillars_role, verification_status) VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, 'ACTIVE', '1985-06-01', ?, ?, 'OTHER', 'en', ?, 'DE', ?, true, false, ?, 'EMAIL', ?, false, ?, 'BASIC_VERIFIED')",
            id, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            email, first, last, "917" + (id % 10000000), email, passwordHash, role);
    }

    private void grantAuthority(long userId, String roleName) {
        jdbcTemplate.update(
            "INSERT INTO main.user_authority (user_id, authority_id) VALUES (?, (SELECT id FROM main.authority WHERE name = ?)) ON CONFLICT DO NOTHING",
            userId, roleName);
    }

    private static String sha256(String raw) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(raw.toLowerCase().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * {@link HttpTestClient} throws on any 4xx/5xx; this keeps the status so refusal paths can be
     * asserted on directly. Error bodies are dropped (assertions on them read the DB instead).
     */
    private <T> ResponseEntity<T> call(String url, HttpMethod method, Map<String, Object> body, HttpHeaders headers, Class<T> type) {
        try {
            return httpTestClient.makeHttpRequest(url, method, body, headers, type);
        } catch (HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode()).build();
        }
    }
}
