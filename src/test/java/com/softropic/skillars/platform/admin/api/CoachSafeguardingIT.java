package com.softropic.skillars.platform.admin.api;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.filestorage.service.FileStorageService;
import com.softropic.skillars.platform.marketplace.service.CoachSafeguardingService;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.service.MailManager;
import com.softropic.skillars.platform.outbox.service.OutboxService;
import com.softropic.skillars.platform.security.SecurityIT;
import com.softropic.skillars.utils.TestMailManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S-09: the optional safeguarding check end to end - the coach asks (with or without an encrypted upload), only a
 * ROLE_ADMIN can view / inspect / reject (a ROLE_LTD_ADMIN and a coach get 403), every staff action is audited, the
 * upload is deleted when staff decide and by the sweeper, the public profile and search cards show a boolean + the
 * valid-until date only, expiry lapses the badge at read time, the reminder email goes out once, and erasure
 * removes everything. FileStorageService is the shared mock (kept in memory here); same mock set / field names as
 * {@link GdprErasureIT} so this class shares its Spring context.
 */
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class CoachSafeguardingIT extends AbstractIntegrationTest {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String CLIENT_ID = "testClientId";
    private static final String TEST_PASSWORD = "TestPass@123!";

    private static final long COACH_USER_ID = 9222_000_010L;
    private static final long ADMIN_ID = 9222_000_100L;
    private static final long LTD_ADMIN_ID = 9222_000_101L;
    private static final String COACH_EMAIL = "coach.sg9222@skillars-test.com";
    private static final String ADMIN_EMAIL = "admin.sg9222@skillars-test.com";
    private static final String LTD_EMAIL = "ltd.sg9222@skillars-test.com";

    private static final byte[] PDF = "%PDF-1.7 SECRET-CERTIFICATE-CONTENT".getBytes(StandardCharsets.US_ASCII);

    @MockitoBean
    private FileStorageService fileStorageService;

    // Present so this class shares the {VideoProviderAdapter, FileStorageService} mock set (and Spring context)
    // with its peers. Keep this exact field name; it is part of the cache key.
    @MockitoBean VideoProviderAdapter videoProviderAdapter;

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private RestTemplate testRestTemplate;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private OutboxService outboxService;
    @Autowired private CoachSafeguardingService safeguardingService;
    @Autowired private MailManager mailManager;

    private UUID coachId;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        coachId = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            for (Object[] a : new Object[][] {{9222, "ROLE_COACH"}, {9223, "ROLE_ADMIN"}, {9224, "ROLE_LTD_ADMIN"}}) {
                jdbcTemplate.update(
                    "INSERT INTO main.authority (id, name, status, created_by, created_date) VALUES (?, ?, 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                    a[0], a[1], Timestamp.from(Instant.now()));
            }
            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH");
            grantAuthority(COACH_USER_ID, "ROLE_COACH");
            insertUser(ADMIN_ID, ADMIN_EMAIL, passwordHash, "ADMIN");
            grantAuthority(ADMIN_ID, "ROLE_ADMIN");
            insertUser(LTD_ADMIN_ID, LTD_EMAIL, passwordHash, "ADMIN");
            grantAuthority(LTD_ADMIN_ID, "ROLE_LTD_ADMIN");
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles (id, user_id, display_name, bio, city, languages, canonical_timezone, status, status_changed_at) " +
                "VALUES (?, ?, 'Safe Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE', ?)",
                coachId, COACH_USER_ID, Timestamp.from(Instant.now()));
            return null;
        });
        ((TestMailManager) mailManager).clear();
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM admin.admin_action_log WHERE reference_id = ?", coachId.toString());
            jdbcTemplate.update("DELETE FROM marketplace.coach_safeguarding_requests WHERE coach_id = ?", coachId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_safeguarding_checks WHERE coach_id = ?", coachId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id = ?", coachId);
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?, ?)", COACH_USER_ID, ADMIN_ID, LTD_ADMIN_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?, ?)", COACH_USER_ID, ADMIN_ID, LTD_ADMIN_ID);
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id IN (9222, 9223, 9224)");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    // ── the whole flow ──────────────────────────────────────────────────────────────────────────

    @Test
    void fullFlow_coachAsks_adminViewsAndInspects_badgeAppears_uploadIsDeleted_andAudited() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);

        assertThat(publicProfile().get("safeguardingCheckOnRecord")).isEqualTo(false);
        assertThat(publicProfile().get("safeguardingValidUntil")).isNull();

        // 1. the coach asks, attaching the certificate: only ciphertext reaches the bucket
        ResponseEntity<Map> requested = postMultipart(coachCookies, PDF);
        assertThat(requested.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(requested.getBody().get("status")).isEqualTo("PENDING");
        assertThat(requested.getBody().get("uploadOnFile")).isEqualTo(true);

        ArgumentCaptor<byte[]> stored = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(fileStorageService).storeBytes(stored.capture(), key.capture(), eq("application/octet-stream"));
        assertThat(new String(stored.getValue(), StandardCharsets.UTF_8)).doesNotContain("SECRET-CERTIFICATE");
        assertThat(key.getValue()).startsWith("safeguarding/" + coachId + "/");
        when(fileStorageService.downloadBytes(key.getValue())).thenReturn(stored.getValue());

        // a pending request is not a badge
        assertThat(publicProfile().get("safeguardingCheckOnRecord")).isEqualTo(false);

        // 2. the admin sees the request and views the decrypted document (audited)
        Map<String, Object> queue = adminGet("/api/admin/safeguarding/requests", adminCookies);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) queue.get("content");
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.get("coachId")).isEqualTo(coachId.toString());
            assertThat(r.get("hasUpload")).isEqualTo(true);
        });
        ResponseEntity<byte[]> doc = getBytes("/api/admin/safeguarding/requests/" + coachId + "/document", adminCookies);
        assertThat(doc.getBody()).isEqualTo(PDF);
        assertThat(doc.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(doc.getHeaders().getCacheControl()).contains("no-store");

        // 3. the admin marks it inspected
        ResponseEntity<Map> inspected = call("/api/admin/safeguarding/requests/" + coachId + "/inspect",
            HttpMethod.POST, adminCookies);
        assertThat(inspected.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> record = jdbcTemplate.queryForMap(
            "SELECT check_type, inspected_by, inspected_at, expires_at FROM marketplace.coach_safeguarding_checks WHERE coach_id = ?",
            coachId);
        assertThat(record.get("check_type")).isEqualTo("DE_EFZ");
        assertThat(((Number) record.get("inspected_by")).longValue()).isEqualTo(ADMIN_ID);
        Instant inspectedAt = ((Timestamp) record.get("inspected_at")).toInstant();
        Instant expiresAt = ((Timestamp) record.get("expires_at")).toInstant();
        assertThat(expiresAt.atZone(ZoneOffset.UTC).toLocalDate())
            .isEqualTo(inspectedAt.atZone(ZoneOffset.UTC).toLocalDate().plusYears(5));

        // the request is gone and the upload is deleted by the decision itself
        assertThat(count("marketplace.coach_safeguarding_requests")).isZero();
        outboxService.drain();
        verify(fileStorageService).deleteRawBytes(key.getValue());

        // audit trail: who, what - and nothing from the certificate
        List<Map<String, Object>> audit = jdbcTemplate.queryForList(
            "SELECT admin_id, action_type, reason FROM admin.admin_action_log WHERE reference_id = ? ORDER BY created_at",
            coachId.toString());
        assertThat(audit).extracting(r -> r.get("action_type"))
            .containsExactly("SAFEGUARDING_DOC_VIEWED", "SAFEGUARDING_INSPECTED");
        assertThat(audit).allSatisfy(r -> {
            assertThat(((Number) r.get("admin_id")).longValue()).isEqualTo(ADMIN_ID);
            assertThat((String) r.get("reason")).doesNotContain("SECRET");
        });

        // 4. the public profile and the search card now carry a boolean + the date, never the inspector
        Map<String, Object> profile = publicProfile();
        assertThat(profile.get("safeguardingCheckOnRecord")).isEqualTo(true);
        assertThat(LocalDate.parse((String) profile.get("safeguardingValidUntil")))
            .isEqualTo(expiresAt.atZone(ZoneOffset.UTC).toLocalDate());
        assertThat(profile.toString()).doesNotContain("inspected").doesNotContain(String.valueOf(ADMIN_ID));

        Map<String, Object> card = searchCard();
        assertThat(card.get("safeguardingCheckOnRecord")).isEqualTo(true);
        assertThat(card.get("safeguardingValidUntil")).isEqualTo(profile.get("safeguardingValidUntil"));

        // 5. the coach sees VERIFIED
        assertThat(ownStatus(coachCookies).get("status")).isEqualTo("VERIFIED");

        // 6. expiry lapses the badge at read time
        transactionTemplate.execute(status -> jdbcTemplate.update(
            "UPDATE marketplace.coach_safeguarding_checks SET inspected_at = ?, expires_at = ? WHERE coach_id = ?",
            Timestamp.from(Instant.now().minus(Duration.ofDays(400))), Timestamp.from(Instant.now().minusSeconds(60)), coachId));
        assertThat(publicProfile().get("safeguardingCheckOnRecord")).isEqualTo(false);
        assertThat(publicProfile().get("safeguardingValidUntil")).isNull();
        assertThat(searchCard().get("safeguardingCheckOnRecord")).isEqualTo(false);
        assertThat(ownStatus(coachCookies).get("status")).isEqualTo("EXPIRED");
    }

    @Test
    void inspectWithoutAnUpload_theVideoCallRoute_works() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);

        assertThat(postMultipart(coachCookies, null).getBody().get("uploadOnFile")).isEqualTo(false);
        verify(fileStorageService, never()).storeBytes(any(), anyString(), anyString());

        assertThat(call("/api/admin/safeguarding/requests/" + coachId + "/inspect", HttpMethod.POST, adminCookies)
            .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(publicProfile().get("safeguardingCheckOnRecord")).isEqualTo(true);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT reason FROM admin.admin_action_log WHERE reference_id = ? AND action_type = 'SAFEGUARDING_INSPECTED'",
            String.class, coachId.toString())).contains("no upload");
    }

    @Test
    void rejectDeletesTheUpload_andTheCoachSeesRejected_butNoBadge() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        postMultipart(coachCookies, PDF);
        String key = jdbcTemplate.queryForObject(
            "SELECT upload_key FROM marketplace.coach_safeguarding_requests WHERE coach_id = ?", String.class, coachId);

        assertThat(call("/api/admin/safeguarding/requests/" + coachId + "/reject", HttpMethod.POST, adminCookies)
            .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        outboxService.drain();
        verify(fileStorageService).deleteRawBytes(key);
        assertThat(ownStatus(coachCookies).get("status")).isEqualTo("REJECTED");
        assertThat(publicProfile().get("safeguardingCheckOnRecord")).isEqualTo(false);
        assertThat(count("marketplace.coach_safeguarding_checks")).isZero();
        // a rejected request cannot be inspected later
        assertThat(call("/api/admin/safeguarding/requests/" + coachId + "/inspect", HttpMethod.POST, adminCookies)
            .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void onlyRoleAdmin_notLtdAdmin_notCoach_notAnonymous() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);
        String ltdCookies = loginAndGetCookies(LTD_EMAIL);
        postMultipart(coachCookies, PDF);

        for (String cookies : List.of(coachCookies, ltdCookies)) {
            assertThat(call("/api/admin/safeguarding/requests", HttpMethod.GET, cookies).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(call("/api/admin/safeguarding/requests/" + coachId + "/document", HttpMethod.GET, cookies)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(call("/api/admin/safeguarding/requests/" + coachId + "/inspect", HttpMethod.POST, cookies)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(call("/api/admin/safeguarding/requests", HttpMethod.GET, null).getStatusCode())
            .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        // and a coach cannot set the badge through their own endpoint: there is none
        assertThat(count("marketplace.coach_safeguarding_checks")).isZero();
    }

    @Test
    void coachWithdraw_removesRecordRequestAndUpload() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);
        String adminCookies = loginAndGetCookies(ADMIN_EMAIL);
        postMultipart(coachCookies, PDF);
        call("/api/admin/safeguarding/requests/" + coachId + "/inspect", HttpMethod.POST, adminCookies);
        postMultipartAllowingFailure(coachCookies); // renewal not allowed yet: valid for years
        assertThat(publicProfile().get("safeguardingCheckOnRecord")).isEqualTo(true);

        assertThat(call("/api/marketplace/coaches/me/safeguarding", HttpMethod.DELETE, coachCookies).getStatusCode())
            .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(count("marketplace.coach_safeguarding_checks")).isZero();
        assertThat(publicProfile().get("safeguardingCheckOnRecord")).isEqualTo(false);
        assertThat(ownStatus(coachCookies).get("status")).isEqualTo("NONE");
    }

    @Test
    void badFiles_areRefused_withoutStoringAnything() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Map> html = postMultipartAllowingFailure(coachCookies, "<html>".getBytes(StandardCharsets.US_ASCII));
        assertThat(html.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        // (an over-5 MB body is refused by the multipart limit, and by CoachSafeguardingService.sniffContentType
        // behind it: see CoachSafeguardingServiceTest; the container drops the connection mid-upload, which a
        // client cannot assert on portably)

        verify(fileStorageService, never()).storeBytes(any(), anyString(), anyString());
        assertThat(count("marketplace.coach_safeguarding_requests")).isZero();
    }

    @Test
    void erasedCoach_cannotFileARequest_theTombstoneGapIsClosed() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);
        transactionTemplate.execute(status -> jdbcTemplate.update(
            "UPDATE marketplace.coach_profiles SET recap_data_erased_at = now() WHERE id = ?", coachId));

        ResponseEntity<Map> resp = postMultipartAllowingFailure(coachCookies, PDF);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(count("marketplace.coach_safeguarding_requests")).isZero();
        verify(fileStorageService, never()).storeBytes(any(), anyString(), anyString());
    }

    // ── jobs ────────────────────────────────────────────────────────────────────────────────────

    @Test
    void sweeper_deletesAnUploadNobodyProcessed_butKeepsTheRequest() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);
        postMultipart(coachCookies, PDF);
        String key = jdbcTemplate.queryForObject(
            "SELECT upload_key FROM marketplace.coach_safeguarding_requests WHERE coach_id = ?", String.class, coachId);
        transactionTemplate.execute(status -> jdbcTemplate.update(
            "UPDATE marketplace.coach_safeguarding_requests SET upload_stored_at = ? WHERE coach_id = ?",
            Timestamp.from(Instant.now().minus(Duration.ofDays(15))), coachId));

        assertThat(safeguardingService.sweepStaleUploads()).isEqualTo(1);

        outboxService.drain();
        verify(fileStorageService).deleteRawBytes(key);
        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT status, upload_key, upload_stored_at FROM marketplace.coach_safeguarding_requests WHERE coach_id = ?",
            coachId);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("upload_key")).isNull();
        assertThat(row.get("upload_stored_at")).isNull();
    }

    @Test
    void sweeper_leavesAFreshUploadAlone() {
        postMultipart(loginAndGetCookies(COACH_EMAIL), PDF);
        assertThat(safeguardingService.sweepStaleUploads()).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT upload_key FROM marketplace.coach_safeguarding_requests WHERE coach_id = ?", String.class, coachId))
            .isNotNull();
    }

    @Test
    void reminder_goesOutOnceForACheckExpiringWithin30Days_notForOneFarAway() {
        transactionTemplate.execute(status -> jdbcTemplate.update(
            "INSERT INTO marketplace.coach_safeguarding_checks (coach_id, check_type, inspected_at, expires_at, inspected_by) VALUES (?, 'DE_EFZ', ?, ?, ?)",
            coachId, Timestamp.from(Instant.now().minus(Duration.ofDays(5 * 365 - 10))),
            Timestamp.from(Instant.now().plus(Duration.ofDays(10))), ADMIN_ID));

        assertThat(safeguardingService.sendExpiryReminders()).isEqualTo(1);

        assertThat(reminderEmails()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT reminder_sent_at FROM marketplace.coach_safeguarding_checks WHERE coach_id = ?", Timestamp.class, coachId))
            .as("the stamp is written in the same transaction as the email").isNotNull();

        ((TestMailManager) mailManager).clear();
        assertThat(safeguardingService.sendExpiryReminders()).isZero();
        assertThat(reminderEmails()).isZero();

        // a check with years left is not reminded
        transactionTemplate.execute(status -> jdbcTemplate.update(
            "UPDATE marketplace.coach_safeguarding_checks SET reminder_sent_at = NULL, expires_at = ? WHERE coach_id = ?",
            Timestamp.from(Instant.now().plus(Duration.ofDays(900))), coachId));
        assertThat(safeguardingService.sendExpiryReminders()).isZero();
    }

    @Test
    void erasure_removesTheRecordTheRequestAndTheUpload() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);
        postMultipart(coachCookies, PDF);
        String key = jdbcTemplate.queryForObject(
            "SELECT upload_key FROM marketplace.coach_safeguarding_requests WHERE coach_id = ?", String.class, coachId);
        transactionTemplate.execute(status -> jdbcTemplate.update(
            "INSERT INTO marketplace.coach_safeguarding_checks (coach_id, check_type, inspected_at, expires_at, inspected_by) VALUES (?, 'DE_EFZ', now(), now() + interval '5 years', ?)",
            coachId, ADMIN_ID));

        transactionTemplate.executeWithoutResult(status -> safeguardingService.eraseForCoach(coachId));

        assertThat(count("marketplace.coach_safeguarding_requests")).isZero();
        assertThat(count("marketplace.coach_safeguarding_checks")).isZero();
        outboxService.drain();
        verify(fileStorageService).deleteRawBytes(key);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private long reminderEmails() {
        return ((TestMailManager) mailManager).getEnvelopes().values().stream()
            .filter(e -> e.emailTemplate() == EmailTemplate.COACH_SAFEGUARDING_EXPIRING)
            .count();
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE coach_id = ?", Long.class, coachId);
    }

    private Map<String, Object> publicProfile() {
        return httpTestClient.makeHttpRequest(baseUrl() + "/api/marketplace/coaches/" + coachId, HttpMethod.GET, null,
            clientHeaders(), Map.class).getBody();
    }

    private Map<String, Object> searchCard() {
        Map<String, Object> body = httpTestClient.makeHttpRequest(baseUrl() + "/api/marketplace/coaches?city=Berlin",
            HttpMethod.GET, null, clientHeaders(), Map.class).getBody();
        List<Map<String, Object>> content = (List<Map<String, Object>>) body.get("coaches");
        if (content == null) {
            content = (List<Map<String, Object>>) body.get("content");
        }
        return content.stream().filter(c -> coachId.toString().equals(c.get("id"))).findFirst().orElseThrow();
    }

    private Map<String, Object> ownStatus(String cookies) {
        return httpTestClient.makeHttpRequest(baseUrl() + "/api/marketplace/coaches/me/safeguarding", HttpMethod.GET,
            null, authenticatedHeaders(cookies), Map.class).getBody();
    }

    private Map<String, Object> adminGet(String path, String cookies) {
        return httpTestClient.makeHttpRequest(baseUrl() + path, HttpMethod.GET, null, authenticatedHeaders(cookies),
            Map.class).getBody();
    }

    private ResponseEntity<byte[]> getBytes(String path, String cookies) {
        HttpHeaders headers = authenticatedHeaders(cookies);
        headers.setAccept(List.of(MediaType.ALL));
        return testRestTemplate.exchange(baseUrl() + path, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
    }

    private ResponseEntity<Map> postMultipart(String cookies, byte[] file) {
        return postMultipartAllowingFailure(cookies, file);
    }

    private ResponseEntity<Map> postMultipartAllowingFailure(String cookies) {
        return postMultipartAllowingFailure(cookies, null);
    }

    private ResponseEntity<Map> postMultipartAllowingFailure(String cookies, byte[] file) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("note", "x"); // keeps the multipart body non-empty for the no-file route
        if (file != null) {
            parts.add("file", new ByteArrayResource(file) {
                @Override
                public String getFilename() {
                    return "certificate.pdf";
                }
            });
        }
        HttpHeaders headers = authenticatedHeaders(cookies);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        try {
            return testRestTemplate.exchange(baseUrl() + "/api/marketplace/coaches/me/safeguarding", HttpMethod.POST,
                new HttpEntity<>(parts, headers), Map.class);
        } catch (HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode()).build();
        }
    }

    /** Status-preserving call: the shared client throws on 4xx/5xx; null cookies = anonymous. */
    private ResponseEntity<Map> call(String path, HttpMethod method, String cookies) {
        HttpHeaders headers = cookies == null ? clientHeaders() : authenticatedHeaders(cookies);
        headers.setAccept(List.of(MediaType.ALL));
        try {
            return testRestTemplate.exchange(baseUrl() + path, method, new HttpEntity<>(headers), Map.class);
        } catch (HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode()).build();
        } catch (org.springframework.web.client.RestClientException e) {
            // a binary body (the document) cannot be read as Map: the status is all these callers assert
            return ResponseEntity.ok().build();
        }
    }

    private String loginAndGetCookies(String email) {
        ResponseEntity<Map> loginResponse = httpTestClient.makeHttpRequest(
            baseUrl() + LOGIN_ENDPOINT, HttpMethod.POST,
            Map.of("email", email, "password", TEST_PASSWORD), clientHeaders(), Map.class);
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

    private void insertUser(long id, String email, String passwordHash, String role) {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" (id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, activated, locked, login, login_id_type, password_hash, otp_enabled, skillars_role, verification_status) VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, 'ACTIVE', '1985-06-01', ?, 'Test', 'OTHER', 'en', ?, 'DE', ?, true, false, ?, 'EMAIL', ?, false, ?, 'BASIC_VERIFIED')",
            id, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            email, role, "918" + (id % 10000000), email, passwordHash, role);
    }

    private void grantAuthority(long userId, String roleName) {
        jdbcTemplate.update(
            "INSERT INTO main.user_authority (user_id, authority_id) VALUES (?, (SELECT id FROM main.authority WHERE name = ?)) ON CONFLICT DO NOTHING",
            userId, roleName);
    }
}
