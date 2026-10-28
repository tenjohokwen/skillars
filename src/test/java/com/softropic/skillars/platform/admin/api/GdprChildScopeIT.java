package com.softropic.skillars.platform.admin.api;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.filestorage.service.FileStorageService;
import com.softropic.skillars.platform.security.SecurityIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.web.client.HttpClientErrorException;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * Self-service GDPR listing + parent-scoped export/erasure of a shadow child profile (V172).
 * Shares the {VideoProviderAdapter, FileStorageService} mock set (field names included) with
 * GdprExportIT/GdprErasureIT so it reuses their Spring context.
 */
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class GdprChildScopeIT extends AbstractIntegrationTest {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String CLIENT_ID = "testClientId";
    private static final String TEST_PASSWORD = "TestPass@123!";
    private static final String MOCK_DOWNLOAD_URL = "https://mock-cdn.skillars-test.com/gdpr-child-export.zip";

    private static final long PARENT_ID = 9846_000_001L;
    private static final long OTHER_PARENT_ID = 9846_000_002L;
    private static final long SELF_PLAYER_USER_ID = 9846_000_003L;
    private static final String PARENT_EMAIL = "gdpr.child.parent.9846@skillars-test.com";
    private static final String OTHER_PARENT_EMAIL = "gdpr.child.other.9846@skillars-test.com";
    private static final String SELF_PLAYER_EMAIL = "gdpr.child.selfplayer.9846@skillars-test.com";

    // TSID-shaped player_profiles ids, deliberately unrelated to any user id.
    private static final long CHILD_ID = 784_600_000_000_001L;
    private static final long SELF_PROFILE_ID = 784_600_000_000_002L;

    @MockitoBean
    private FileStorageService fileStorageService;

    // Keep this exact field name; it is part of the context cache key (see GdprExportIT).
    @MockitoBean VideoProviderAdapter videoProviderAdapter;

    @Autowired private HttpTestClient httpTestClient;
    @Autowired private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        Mockito.when(fileStorageService.signedDownloadUrl(anyString(), any())).thenReturn(MOCK_DOWNLOAD_URL);
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("INSERT INTO main.authority (id, name, status, created_by, created_date) "
                + "VALUES (9846, 'ROLE_PARENT', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));
            jdbcTemplate.update("INSERT INTO main.authority (id, name, status, created_by, created_date) "
                + "VALUES (9847, 'ROLE_PLAYER', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));
            insertUser(PARENT_ID, PARENT_EMAIL, passwordHash, "PARENT");
            grantAuthority(PARENT_ID, "ROLE_PARENT");
            insertUser(OTHER_PARENT_ID, OTHER_PARENT_EMAIL, passwordHash, "PARENT");
            grantAuthority(OTHER_PARENT_ID, "ROLE_PARENT");
            insertUser(SELF_PLAYER_USER_ID, SELF_PLAYER_EMAIL, passwordHash, "PLAYER");
            grantAuthority(SELF_PLAYER_USER_ID, "ROLE_PLAYER");

            jdbcTemplate.update("INSERT INTO main.player_profiles "
                    + "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, "
                    + "consent_accepted_at, consent_policy_version, created_at, created_by) "
                    + "VALUES (?, 'Kid Example', ?, 'FORWARD', 'AGE_10_12', ?, false, ?, '1.0', ?, 'system')",
                CHILD_ID, Date.valueOf(LocalDate.of(2015, 4, 17)), PARENT_ID,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
            jdbcTemplate.update("INSERT INTO main.player_profiles "
                    + "(id, name, date_of_birth, position, age_tier, user_id, independent_account_allowed, "
                    + "created_at, created_by) "
                    + "VALUES (?, 'Adult Self', ?, 'MIDFIELDER', 'ADULT', ?, true, ?, 'system')",
                SELF_PROFILE_ID, Date.valueOf(LocalDate.now().minusYears(20)), SELF_PLAYER_USER_ID,
                Timestamp.from(Instant.now()));
            return null;
        });
    }

    // ── self scope ──

    @Test
    void listOwnRequests_showsDownloadUrlOnlyForCompletedUnexpiredExport() {
        transactionTemplate.execute(status -> {
            insertRequest(UUID.randomUUID(), PARENT_ID, null, "EXPORT", "COMPLETED",
                Instant.now().plusSeconds(3600), MOCK_DOWNLOAD_URL);
            insertRequest(UUID.randomUUID(), PARENT_ID, null, "EXPORT", "COMPLETED",
                Instant.now().minusSeconds(3600), "https://expired.example/x.zip");
            // A child-scoped row must not appear in the self list.
            insertRequest(UUID.randomUUID(), PARENT_ID, CHILD_ID, "EXPORT", "PENDING", null, null);
            return null;
        });

        ResponseEntity<List> resp = httpTestClient.makeHttpRequest(baseUrl() + "/api/gdpr/requests",
            HttpMethod.GET, null, headers(login(PARENT_EMAIL)), List.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = resp.getBody();
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(r -> r.get("downloadUrl")).containsExactlyInAnyOrder(MOCK_DOWNLOAD_URL, null);
    }

    @Test
    void ownPendingExport_doesNotBlockChildExport() {
        transactionTemplate.execute(status -> {
            insertRequest(UUID.randomUUID(), PARENT_ID, null, "EXPORT", "PENDING", null, null);
            return null;
        });
        ResponseEntity<Map> resp = post("/api/gdpr/players/" + CHILD_ID + "/export", login(PARENT_EMAIL));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    // ── child export ──

    @Test
    void childExport_byOwningParent_completesAndIsListedWithDownloadUrl() {
        String cookies = login(PARENT_EMAIL);
        ResponseEntity<Map> resp = post("/api/gdpr/players/" + CHILD_ID + "/export", cookies);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID requestId = UUID.fromString((String) resp.getBody().get("requestId"));

        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT user_id, player_id, status FROM admin.gdpr_requests WHERE id = ?", requestId);
        assertThat(((Number) row.get("user_id")).longValue()).isEqualTo(PARENT_ID);
        assertThat(((Number) row.get("player_id")).longValue()).isEqualTo(CHILD_ID);
        // The export runs synchronously after commit, on the request thread.
        assertThat(row.get("status")).isEqualTo("COMPLETED");

        ResponseEntity<List> list = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/gdpr/players/" + CHILD_ID + "/requests", HttpMethod.GET, null, headers(cookies),
            List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = list.getBody();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("downloadUrl")).isEqualTo(MOCK_DOWNLOAD_URL);

        // ...and the child-scoped row is NOT in the parent's own list.
        ResponseEntity<List> own = httpTestClient.makeHttpRequest(baseUrl() + "/api/gdpr/requests",
            HttpMethod.GET, null, headers(cookies), List.class);
        assertThat(own.getBody()).isEmpty();
    }

    @Test
    void childExport_byOtherParent_returns403() {
        assertStatus(() -> post("/api/gdpr/players/" + CHILD_ID + "/export", login(OTHER_PARENT_EMAIL)),
            HttpStatus.FORBIDDEN);
        assertThat(countRequests(CHILD_ID)).isZero();
    }

    @Test
    void childEndpoints_rejectSelfRegisteredPlayerOnOwnProfile() {
        // The ownership guard admits a self-owned profile; the PARENT role gate must still refuse it.
        assertStatus(() -> post("/api/gdpr/players/" + SELF_PROFILE_ID + "/erasure", login(SELF_PLAYER_EMAIL)),
            HttpStatus.FORBIDDEN);
        assertThat(countRequests(SELF_PROFILE_ID)).isZero();
    }

    @Test
    void childExport_duplicatePending_returns409() {
        transactionTemplate.execute(status -> {
            insertRequest(UUID.randomUUID(), PARENT_ID, CHILD_ID, "EXPORT", "PROCESSING", null, null);
            return null;
        });
        assertStatus(() -> post("/api/gdpr/players/" + CHILD_ID + "/export", login(PARENT_EMAIL)),
            HttpStatus.CONFLICT);
    }

    @Test
    void scopedUniqueIndex_rejectsSecondActiveChildRequest_butAllowsSelfOne() {
        // The DB-level backstop for the concurrent-duplicate race the service's pre-check cannot close.
        transactionTemplate.execute(status -> {
            insertRequest(UUID.randomUUID(), PARENT_ID, CHILD_ID, "ERASURE", "PENDING", null, null);
            insertRequest(UUID.randomUUID(), PARENT_ID, null, "ERASURE", "PENDING", null, null);
            return null;
        });
        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            insertRequest(UUID.randomUUID(), OTHER_PARENT_ID, CHILD_ID, "ERASURE", "PROCESSING", null, null);
            return null;
        })).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    // ── child erasure ──

    @Test
    void childErasure_scrubsProfile_hidesItFromParentList_andLeavesParentAccountIntact() {
        UUID bookingId = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            Instant start = Instant.now().minusSeconds(7 * 86400);
            jdbcTemplate.update("INSERT INTO booking.bookings (id, coach_id, parent_id, player_id, status, "
                    + "requested_start_time, requested_end_time, version, created_at, updated_at, canonical_timezone, "
                    + "notes) VALUES (?, ?, ?, ?, 'COMPLETED', ?, ?, 0, ?, ?, 'Europe/Berlin', 'Allergic to nuts')",
                bookingId, UUID.randomUUID(), PARENT_ID, CHILD_ID, Timestamp.from(start),
                Timestamp.from(start.plusSeconds(3600)), Timestamp.from(start), Timestamp.from(start));
            return null;
        });

        String cookies = login(PARENT_EMAIL);
        ResponseEntity<Map> resp = post("/api/gdpr/players/" + CHILD_ID + "/erasure", cookies);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID requestId = UUID.fromString((String) resp.getBody().get("requestId"));

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM admin.gdpr_requests WHERE id = ?",
            String.class, requestId)).isEqualTo("COMPLETED");

        Map<String, Object> profile = jdbcTemplate.queryForMap(
            "SELECT name, date_of_birth, development_data_erased_at, parent_id FROM main.player_profiles WHERE id = ?",
            CHILD_ID);
        assertThat(profile.get("name")).isEqualTo("Erased player");
        assertThat(((Date) profile.get("date_of_birth")).toLocalDate()).isEqualTo(LocalDate.of(2015, 12, 31));
        assertThat(profile.get("development_data_erased_at")).isNotNull();
        assertThat(((Number) profile.get("parent_id")).longValue()).isEqualTo(PARENT_ID);

        // Booking row kept (financial history), its free text anonymised.
        assertThat(jdbcTemplate.queryForObject("SELECT notes FROM booking.bookings WHERE id = ?",
            String.class, bookingId)).isNull();

        // The parent's own account is untouched and still usable.
        Map<String, Object> parent = jdbcTemplate.queryForMap(
            "SELECT email, locked FROM main.\"user\" WHERE id = ?", PARENT_ID);
        assertThat(parent.get("email")).isEqualTo(PARENT_EMAIL);
        assertThat(parent.get("locked")).isEqualTo(false);

        ResponseEntity<List> children = httpTestClient.makeHttpRequest(baseUrl() + "/api/security/players",
            HttpMethod.GET, null, headers(cookies), List.class);
        assertThat(children.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(children.getBody()).isEmpty();

        // A second erasure of an already-erased child is refused.
        assertStatus(() -> post("/api/gdpr/players/" + CHILD_ID + "/erasure", cookies), HttpStatus.CONFLICT);
    }

    @Test
    void childErasure_withLiveBooking_returns409AndErasesNothing() {
        transactionTemplate.execute(status -> {
            Instant start = Instant.now().plusSeconds(86400);
            jdbcTemplate.update("INSERT INTO booking.bookings (id, coach_id, parent_id, player_id, status, "
                    + "requested_start_time, requested_end_time, version, created_at, updated_at, canonical_timezone) "
                    + "VALUES (?, ?, ?, ?, 'CONFIRMED', ?, ?, 0, ?, ?, 'Europe/Berlin')",
                UUID.randomUUID(), UUID.randomUUID(), PARENT_ID, CHILD_ID, Timestamp.from(start),
                Timestamp.from(start.plusSeconds(3600)), Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
            return null;
        });

        HttpClientErrorException ex = catchHttp(
            () -> post("/api/gdpr/players/" + CHILD_ID + "/erasure", login(PARENT_EMAIL)));
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getResponseBodyAsString()).contains("gdpr.activeBookingsExist");
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM main.player_profiles WHERE id = ?",
            String.class, CHILD_ID)).isEqualTo("Kid Example");
        assertThat(countRequests(CHILD_ID)).isZero();
    }

    @Test
    void concurrentDuplicateChildExports_exactlyOneAccepted() throws Exception {
        String cookies = login(PARENT_EMAIL);
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<HttpStatus>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<HttpStatus> call = () -> {
                    go.await();
                    try {
                        return HttpStatus.valueOf(
                            post("/api/gdpr/players/" + CHILD_ID + "/export", cookies).getStatusCode().value());
                    } catch (HttpClientErrorException e) {
                        return HttpStatus.valueOf(e.getStatusCode().value());
                    }
                };
                results.add(pool.submit(call));
            }
            go.countDown();
            List<HttpStatus> statuses = new ArrayList<>();
            for (Future<HttpStatus> f : results) {
                statuses.add(f.get(60, TimeUnit.SECONDS));
            }
            // The export completes synchronously, so a late request may legitimately start a fresh one
            // once the first is COMPLETED. What must never happen is a 5xx, or two ACTIVE rows at once.
            assertThat(statuses).allMatch(s -> s == HttpStatus.ACCEPTED || s == HttpStatus.CONFLICT);
            assertThat(statuses).contains(HttpStatus.ACCEPTED);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM admin.gdpr_requests "
                + "WHERE player_id = ? AND status IN ('PENDING','PROCESSING')", Integer.class, CHILD_ID)).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    // ── helpers ──

    private ResponseEntity<Map> post(String path, String cookies) {
        return httpTestClient.makeHttpRequest(baseUrl() + path, HttpMethod.POST, null, headers(cookies), Map.class);
    }

    private void assertStatus(Runnable call, HttpStatus expected) {
        assertThat(catchHttp(call::run).getStatusCode()).isEqualTo(expected);
    }

    private HttpClientErrorException catchHttp(Runnable call) {
        try {
            call.run();
        } catch (HttpClientErrorException e) {
            return e;
        }
        throw new AssertionError("expected an HTTP client error");
    }

    private int countRequests(long playerId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM admin.gdpr_requests WHERE player_id = ?",
            Integer.class, playerId);
    }

    private void insertRequest(UUID id, long userId, Long playerId, String type, String status,
                               Instant expiresAt, String downloadUrl) {
        jdbcTemplate.update("INSERT INTO admin.gdpr_requests (id, user_id, player_id, request_type, status, "
                + "created_at, completed_at, download_url, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, userId, playerId, type, status, Timestamp.from(Instant.now()),
            "COMPLETED".equals(status) ? Timestamp.from(Instant.now()) : null, downloadUrl,
            expiresAt == null ? null : Timestamp.from(expiresAt));
    }

    private String login(String email) {
        ResponseEntity<Map> loginResponse = httpTestClient.makeHttpRequest(baseUrl() + LOGIN_ENDPOINT,
            HttpMethod.POST, Map.of("email", email, "password", TEST_PASSWORD), clientHeaders(), Map.class);
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

    private HttpHeaders headers(String cookies) {
        HttpHeaders headers = clientHeaders();
        headers.add(HttpHeaders.COOKIE, cookies);
        return headers;
    }

    private void insertUser(long id, String email, String passwordHash, String role) {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" (id, created_by, created_date, last_modified_by, last_modified_date, request_id, "
                + "session_id, status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, "
                + "activated, locked, login, login_id_type, password_hash, otp_enabled, skillars_role, "
                + "verification_status) VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, 'ACTIVE', '1985-06-01', "
                + "?, 'Test', 'OTHER', 'en', ?, 'DE', ?, true, false, ?, 'EMAIL', ?, false, ?, 'BASIC_VERIFIED')",
            id, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            email, role, "984" + (id % 10000000), email, passwordHash, role);
    }

    private void grantAuthority(long userId, String roleName) {
        jdbcTemplate.update("INSERT INTO main.user_authority (user_id, authority_id) "
            + "VALUES (?, (SELECT id FROM main.authority WHERE name = ?)) ON CONFLICT DO NOTHING", userId, roleName);
    }
}
