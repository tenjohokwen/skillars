package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.infrastructure.storage.BaseStorageIT;
import com.softropic.skillars.platform.development.contract.CoachBrandingRequest;
import com.softropic.skillars.platform.development.contract.CoachBrandingResponse;
import com.softropic.skillars.platform.development.contract.ReportGeneratedEvent;
import com.softropic.skillars.platform.filestorage.contract.ConfirmUploadRequest;
import com.softropic.skillars.platform.filestorage.contract.ConfirmUploadResponse;
import com.softropic.skillars.platform.filestorage.contract.SignUploadRequest;
import com.softropic.skillars.platform.filestorage.contract.SignUploadResponse;
import com.softropic.skillars.platform.security.SecurityIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Deferred-77 AC2: proves the real @Async @TransactionalEventListener wiring — a PENDING_UPLOAD
 * report becomes READY (with a real storage_key) only after onReportGenerated actually runs against
 * the live DB/S3-compatible backend, and listReports only ever surfaces it once READY. The
 * UPLOAD_FAILED branch is covered at the unit level (ReportGenerationServiceTest) where the S3 call
 * can be made to throw deterministically.
 */
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class ReportGenerationServiceIT extends BaseStorageIT {

    @Autowired private ReportGenerationService reportGenerationService;
    @Autowired private com.softropic.skillars.platform.development.repo.PlayerTimelineRepository
        playerTimelineRepository;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private RestTemplate restTemplate;

    private static final long PARENT_USER_ID = 9576000001L;
    private static final long PLAYER_ID = 9576000010L;
    private static final UUID COACH_ID = UUID.randomUUID();

    private static final long BRANDING_COACH_USER_ID = 9576000020L;
    private static final String BRANDING_COACH_EMAIL = "coach.branding.it@skillars-test.com";
    private static final String BRANDING_COACH_PASSWORD = "TestPass@123!";

    /**
     * skillars-deferred-155 code review: the only real-database exercise of
     * {@code PlayerTimelineRepository.findSessionNotesForReport}. Before this, all five references to it
     * in the test tree were Mockito stubs in {@code ReportGenerationServiceTest}, so the hand-written
     * native SQL — {@code jsonb_exists(...)}, {@code metadata->>'coachId' = CAST(:coachId AS text)} with
     * a UUID bind, a schema-qualified {@code SELECT *} hydrating entities, and the {@code LIMIT} /
     * tie-broken {@code ORDER BY} — had never once been executed by Postgres. A binding or mapping
     * error would have surfaced as a 500 on the first production report instead of a test failure.
     *
     * <p>The load-bearing assertion is the non-attended row: its {@code coach_id} column is NULL
     * (TimelineEventListener.java:61 gates it on attendance) while {@code metadata.coachId} is set
     * unconditionally (:55), so a column-based filter would silently drop a real recap. That is the
     * trap AC1 exists to avoid, and only a real query can prove it is avoided.
     */
    @Test
    void findSessionNotesForReport_realQuery_filtersOnMetadataKeyAndHonoursLimitAndOrder() {
        UUID otherCoachId = UUID.randomUUID();
        Instant base = Instant.parse("2026-03-01T10:00:00Z");
        transactionTemplate.execute(status -> {
            // 1. attended — coach_id column populated
            insertTimelineEvent(base.plusSeconds(300), "SESSION_COMPLETED", COACH_ID,
                "Attended recap", "Attended next", COACH_ID);
            // 2. NON-attended — coach_id column NULL, metadata.coachId still set. Must be returned.
            insertTimelineEvent(base.plusSeconds(200), "SESSION_COMPLETED", null,
                "Non-attended recap", "Non-attended next", COACH_ID);
            // 3. another coach's recap for the same player — must not leak
            insertTimelineEvent(base.plusSeconds(100), "SESSION_COMPLETED", otherCoachId,
                "Other coach recap", "Other coach next", otherCoachId);
            // 4. this coach, but no recap keys at all — the jsonb_exists guard must drop it
            insertTimelineEvent(base.plusSeconds(50), "SESSION_COMPLETED", COACH_ID,
                null, null, COACH_ID);
            // 5. this coach's recap keys on a different event type — the event_type filter must drop it
            insertTimelineEvent(base.plusSeconds(10), "PERFORMANCE_REPORT", COACH_ID,
                "Wrong type recap", "Wrong type next", COACH_ID);
            return null;
        });

        var all = playerTimelineRepository.findSessionNotesForReport(PLAYER_ID, COACH_ID, 10);

        assertThat(all).hasSize(2);
        assertThat(all.get(0).getMetadata().get("whatWeDid")).isEqualTo("Attended recap");
        assertThat(all.get(1).getMetadata().get("whatWeDid"))
            .as("non-attended session's recap must survive the filter — coach_id column is NULL there")
            .isEqualTo("Non-attended recap");
        assertThat(all.get(1).getCoachId())
            .as("proves the row really is the attendance-gated one")
            .isNull();

        var capped = playerTimelineRepository.findSessionNotesForReport(PLAYER_ID, COACH_ID, 1);
        assertThat(capped).hasSize(1);
        assertThat(capped.get(0).getMetadata().get("whatWeDid")).isEqualTo("Attended recap");
    }

    /**
     * Builds the metadata object with only the keys that are actually present, mirroring
     * TimelineEventListener.java:56-57's own conditional puts — the absent-key case is what the
     * query's jsonb_exists guard has to discriminate on, so a fixture that always wrote both keys
     * could not express it. Test inputs are controlled literals with no quotes to escape.
     */
    private void insertTimelineEvent(Instant occurredAt, String eventType, UUID coachIdColumn,
                                     String whatWeDid, String whatsNext, UUID metadataCoachId) {
        StringBuilder metadata = new StringBuilder("{\"coachId\":\"").append(metadataCoachId).append('"');
        if (whatWeDid != null) {
            metadata.append(",\"whatWeDid\":\"").append(whatWeDid).append('"');
        }
        if (whatsNext != null) {
            metadata.append(",\"whatsNext\":\"").append(whatsNext).append('"');
        }
        metadata.append('}');
        jdbcTemplate.update(
            "INSERT INTO development.player_timeline_events "
                + "(id, player_id, event_type, occurred_at, metadata, coach_id) "
                + "VALUES (?, ?, ?, ?, ?::jsonb, ?)",
            UUID.randomUUID(), PLAYER_ID, eventType, Timestamp.from(occurredAt),
            metadata.toString(), coachIdColumn);
    }

    @Test
    void onReportGenerated_realAsyncPipeline_transitionsPendingUploadToReadyAndListable() {
        UUID reportId = UUID.randomUUID();
        Instant generatedAt = Instant.now();

        transactionTemplate.execute(status -> {
            insertParentUser();
            insertPlayerProfile();
            jdbcTemplate.update(
                "INSERT INTO development.performance_reports " +
                "(id, coach_id, player_id, generated_at, next_steps, version, status) " +
                "VALUES (?, ?, ?, ?, 'Keep practicing.', 1, 'PENDING_UPLOAD')",
                reportId, COACH_ID, PLAYER_ID, Timestamp.from(generatedAt));

            eventPublisher.publishEvent(new ReportGeneratedEvent(
                reportId, PLAYER_ID, "Test Coach", "Test Player", generatedAt,
                "%PDF-1.4 test report bytes".getBytes()));
            return null;
        });

        await().atMost(5, SECONDS).until(() -> "READY".equals(reportStatus(reportId)));

        String storageKey = jdbcTemplate.queryForObject(
            "SELECT storage_key FROM development.performance_reports WHERE id = ?", String.class, reportId);
        assertThat(storageKey).isNotNull();

        assertThat(reportGenerationService.listReports(PLAYER_ID))
            .anySatisfy(r -> assertThat(r.id()).isEqualTo(reportId));

        int timelineCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM development.player_timeline_events WHERE reference_id = ?",
            Integer.class, reportId);
        assertThat(timelineCount).isEqualTo(1);
    }

    // skillars-deferred-152 AC7/Task 7: a real sign-upload -> S3 PUT -> confirm -> PUT /branding
    // round trip, rather than ReportGenerationServiceTest's hand-built FileStorageObject fixture
    // (.ownerId(...) set directly) — proving the four id-space call sites this project's
    // coach_branding ownership fix depends on actually agree end-to-end, not just that each one's
    // own unit test passes in isolation: SecurityUtil.getCurrentCoachUserId() (CoachBrandingResource),
    // ReportGenerationService.saveBranding's assertOwnership call, StorageResource's
    // OWNER_BOUND_ENTITIES coach_branding entry (entityId must equal the authenticated principal's
    // businessId -- the coach's user id, not the CoachProfile UUID), and the frontend's
    // entityId: String(userId) (not exercised directly here, but this id space IS the contract it
    // depends on). Follows StorageResourceIT's existing signUploadHttp/putToS3/confirmUploadHttp
    // round-trip helpers rather than inventing a new upload-flow helper.
    @Test
    void saveBranding_realSignUploadConfirmFlow_allFourIdSpaceCallSitesAgreeEndToEnd() {
        transactionTemplate.execute(status -> {
            insertCoachUser(BRANDING_COACH_USER_ID, BRANDING_COACH_EMAIL,
                passwordEncoder.encode(BRANDING_COACH_PASSWORD));
            grantRole(BRANDING_COACH_USER_ID, "ROLE_COACH");
            UUID coachId = insertCoachProfile(BRANDING_COACH_USER_ID);
            insertSubscription(coachId, "ACADEMY");
            return null;
        });

        HttpHeaders authHeaders = loginAsCoach(BRANDING_COACH_EMAIL, BRANDING_COACH_PASSWORD);

        byte[] logoBytes = new byte[]{1, 2, 3, 4};
        SignUploadResponse signResponse = signUploadHttp(
            authHeaders, String.valueOf(BRANDING_COACH_USER_ID), "image/png", "png", logoBytes.length);

        putToS3(signResponse.uploadUrl(), logoBytes, "image/png");

        ConfirmUploadResponse confirmResponse =
            confirmUploadHttp(authHeaders, signResponse.key(), "image/png", logoBytes.length);
        assertThat(confirmResponse.key()).isEqualTo(signResponse.key());

        saveBrandingHttp(authHeaders, signResponse.key(), "#3B82F6");

        CoachBrandingResponse branding = getBrandingHttp(authHeaders);
        assertThat(branding.logoKey()).isEqualTo(signResponse.key());
        assertThat(branding.logoSignedUrl()).isNotBlank();
        assertThat(branding.brandColour()).isEqualTo("#3B82F6");
    }

    private String reportStatus(UUID reportId) {
        return jdbcTemplate.queryForObject(
            "SELECT status::text FROM development.performance_reports WHERE id = ?", String.class, reportId);
    }

    private void insertPlayerProfile() {
        jdbcTemplate.update(
            "INSERT INTO main.player_profiles " +
            "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
            "VALUES (?, 'Report Player', ?, 'MIDFIELDER', 'AGE_10_12', ?, false, ?, 'system')",
            PLAYER_ID, Date.valueOf(LocalDate.now().minusYears(10)),
            PARENT_USER_ID, Timestamp.from(Instant.now()));
    }

    private void insertParentUser() {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" " +
            "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, " +
            "status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, " +
            "activated, locked, login, login_id_type, password_hash, otp_enabled, " +
            "skillars_role, verification_status) " +
            "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, " +
            "'ACTIVE', '1990-01-01', ?, 'Test', 'OTHER', 'en', 'User', 'DE', ?, " +
            "true, false, ?, 'EMAIL', ?, false, " +
            "'PARENT', 'BASIC_VERIFIED')",
            PARENT_USER_ID,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            "reportgen.parent@skillars-test.com",
            "69" + (PARENT_USER_ID % 100000000L),
            "reportgen.parent@skillars-test.com", "x"
        );
    }

    // ── AC7 coach-branding end-to-end helpers ───────────────────────────────

    private void insertCoachUser(long userId, String email, String passwordHash) {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" " +
            "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, " +
            "status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, " +
            "activated, locked, login, login_id_type, password_hash, otp_enabled, " +
            "skillars_role, verification_status) " +
            "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, " +
            "'ACTIVE', '1990-01-01', ?, 'Test', 'OTHER', 'en', 'Coach', 'DE', ?, " +
            "true, false, ?, 'EMAIL', ?, false, " +
            "'COACH', 'BASIC_VERIFIED')",
            userId, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            email, "69" + (userId % 100000000L), email, passwordHash);
    }

    private void grantRole(long userId, String roleName) {
        jdbcTemplate.update(
            "INSERT INTO main.user_authority (user_id, authority_id) " +
            "VALUES (?, (SELECT id FROM main.authority WHERE name = ?)) ON CONFLICT DO NOTHING",
            userId, roleName);
    }

    private UUID insertCoachProfile(long userId) {
        UUID profileId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO marketplace.coach_profiles " +
            "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
            "VALUES (?, ?, 'Branding Test Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
            profileId, userId);
        return profileId;
    }

    private void insertSubscription(UUID coachId, String tier) {
        jdbcTemplate.update(
            "INSERT INTO marketplace.coach_subscriptions (coach_id, tier, active_since) " +
            "VALUES (?, ?, NOW()) ON CONFLICT DO NOTHING",
            coachId, tier);
    }

    /** Mirrors AdminLogin.loginAsAdmin's POST /authenticate + Set-Cookie extraction for an
     *  arbitrary (non-admin) seeded user, since AdminLogin itself is hardcoded to the admin
     *  fixture's credentials. */
    private HttpHeaders loginAsCoach(String email, String password) {
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        loginHeaders.add(HttpHeaders.COOKIE, "fcookie=fingerprintCookie");
        loginHeaders.add("user-agent", "Java/17.0.11");

        Map<String, String> credentials = Map.of("id", email, "password", password);
        ResponseEntity<Map> loginResponse = restTemplate.exchange(
            baseUrl() + "/authenticate", HttpMethod.POST,
            new HttpEntity<>(credentials, loginHeaders), Map.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<String> setCookies = loginResponse.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(setCookies).isNotNull().isNotEmpty();
        String cookieHeader = String.join("; ",
            setCookies.stream().map(c -> c.split(";", 2)[0]).toList());

        HttpHeaders authHeaders = new HttpHeaders();
        authHeaders.set(HttpHeaders.COOKIE, cookieHeader);
        return authHeaders;
    }

    // JWTAuthorizationFilter/TokenValidatorImpl re-check the fingerprint (fcookie) + user-agent
    // combo embedded in the JWT on every subsequent request, not only at login -- mirrors
    // StorageResourceIT's own authenticatedHeaders() precedent exactly.
    private HttpHeaders authenticatedHeaders(HttpHeaders authHeaders) {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(authHeaders);
        headers.add("user-agent", "Java/17.0.11");
        headers.add(HttpHeaders.COOKIE, "fcookie=fingerprintCookie");
        return headers;
    }

    private SignUploadResponse signUploadHttp(
        HttpHeaders authHeaders, String entityId, String contentType, String extension, long sizeBytes) {
        SignUploadRequest body = new SignUploadRequest(
            "coach_branding", entityId, contentType, extension, sizeBytes, null, null);

        HttpHeaders headers = authenticatedHeaders(authHeaders);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<SignUploadResponse> response = restTemplate.postForEntity(
            baseUrl() + "/api/storage/sign/upload", new HttpEntity<>(body, headers), SignUploadResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private void putToS3(String uploadUrl, byte[] content, String contentType) {
        RestTemplate noRetryRestTemplate = new RestTemplateBuilder()
            .requestFactory(SimpleClientHttpRequestFactory.class)
            .build();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));
        ResponseEntity<Void> response = noRetryRestTemplate.exchange(
            URI.create(uploadUrl), HttpMethod.PUT, new HttpEntity<>(content, headers), Void.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    private ConfirmUploadResponse confirmUploadHttp(
        HttpHeaders authHeaders, String key, String contentType, long sizeBytes) {
        ConfirmUploadRequest body = new ConfirmUploadRequest(contentType, sizeBytes, null, null, null);

        HttpHeaders headers = authenticatedHeaders(authHeaders);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<ConfirmUploadResponse> response = restTemplate.postForEntity(
            baseUrl() + "/api/storage/confirm/" + key, new HttpEntity<>(body, headers), ConfirmUploadResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private void saveBrandingHttp(HttpHeaders authHeaders, String logoKey, String brandColour) {
        CoachBrandingRequest body = new CoachBrandingRequest(logoKey, brandColour);

        HttpHeaders headers = authenticatedHeaders(authHeaders);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Void> response = restTemplate.exchange(
            baseUrl() + "/api/development/coaches/me/branding", HttpMethod.PUT,
            new HttpEntity<>(body, headers), Void.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    private CoachBrandingResponse getBrandingHttp(HttpHeaders authHeaders) {
        HttpHeaders headers = authenticatedHeaders(authHeaders);

        ResponseEntity<CoachBrandingResponse> response = restTemplate.exchange(
            baseUrl() + "/api/development/coaches/me/branding", HttpMethod.GET,
            new HttpEntity<>(headers), CoachBrandingResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }
}
