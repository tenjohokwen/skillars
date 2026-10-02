package com.softropic.skillars.platform.marketplace.api;

import com.softropic.skillars.config.AbstractIntegrationTest;

import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.SecurityIT;

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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AC1 ({@code GET} the coach's own full profile, for edit-dialog prefill), AC2's hard requirement
 * (empirically confirm {@code saveStep1}-{@code saveStep4} are idempotent-updatable against a real
 * {@code ACTIVE} coach, not just the static read the story's Context section cites), and AC3 (the
 * new photo-delete endpoint). A sibling of {@link CoachProfileBuilderIT}, split out per the story's
 * own suggested file name rather than growing that file further.
 */
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class CoachProfileSelfEditIT extends AbstractIntegrationTest {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String PROFILE_BASE = "/api/marketplace/coaches/me/profile";
    private static final String CLIENT_ID = "testClientId";
    private static final String TEST_PASSWORD = "CoachPass@123!";

    private static final long COACH_ID = 9300000001L;
    private static final long PARENT_ID = 9300000002L;
    private static final String COACH_EMAIL = "coach.selfedit@skillars-test.com";
    private static final String PARENT_EMAIL = "parent.selfedit@skillars-test.com";

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
                "VALUES (9300, 'ROLE_COACH', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now())
            );
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (9301, 'ROLE_PARENT', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now())
            );
            insertCoachUser(COACH_ID, COACH_EMAIL, passwordHash);
            insertParentUser(PARENT_ID, PARENT_EMAIL, passwordHash);
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_COACH')) ON CONFLICT DO NOTHING",
                COACH_ID
            );
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_PARENT')) ON CONFLICT DO NOTHING",
                PARENT_ID
            );
            return null;
        });
    }

    // ---- AC1 ----

    @Test
    void getOwnFullProfile_asCoach_returnsOwnData() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        saveAllSteps(cookies);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE, HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("displayName")).isEqualTo("Coach Name");
        assertThat(response.getBody().get("city")).isEqualTo("Berlin");
        assertThat(response.getBody().get("status")).isEqualTo("DRAFT");
    }

    @Test
    void getOwnFullProfile_draftProfile_returnsAllCollectedFields() {
        // AC1's whole point: unlike the public view, this must work BEFORE publish (still DRAFT).
        String cookies = loginAndGetCookies(COACH_EMAIL);
        saveStep1(cookies);
        saveStep2(cookies);
        saveStep3(cookies);
        saveStep4(cookies);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE, HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("status")).isEqualTo("DRAFT");
        @SuppressWarnings("unchecked")
        List<String> specialties = (List<String>) response.getBody().get("specialties");
        assertThat(specialties).contains("Dribbling");
        assertThat(response.getBody().get("perSessionPrice")).isNotNull();
        assertThat((List<?>) response.getBody().get("availabilityWindows")).isNotEmpty();
    }

    @Test
    void getOwnFullProfile_asParent_returns403() {
        String cookies = loginAndGetCookies(PARENT_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE, HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ---- AC2: empirical confirmation that saveStep1-4 are idempotent-updatable on an ACTIVE coach ----

    @Test
    void saveStep1_onActiveCoach_succeedsAndProfileStaysActive() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        saveAllSteps(cookies);
        publish(cookies);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/steps/1",
            HttpMethod.PUT,
            Map.of("displayName", "Updated Name", "languages", List.of("English"),
                "canonicalTimezone", "Europe/Berlin"),
            authenticatedHeaders(cookies),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statusOfCoach()).isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT display_name FROM marketplace.coach_profiles WHERE user_id = ?", String.class, COACH_ID))
            .isEqualTo("Updated Name");
    }

    @Test
    void saveStep2_onActiveCoach_succeedsAndProfileStaysActive() {
        // Deliberately resubmits the SAME ageGroup ("ADULT") saveAllSteps already saved: this is
        // what surfaced a genuine pre-existing bug during this AC's required empirical confirmation
        // — Hibernate's default flush order runs INSERTs before DELETEs, so without an explicit flush
        // between deleteByCoachId and saveAll this 400s with generic.dataError (uq_coach_age_group).
        String cookies = loginAndGetCookies(COACH_EMAIL);
        saveAllSteps(cookies);
        publish(cookies);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/steps/2",
            HttpMethod.PUT,
            Map.of("specialties", List.of("Passing"), "ageGroups", List.of("ADULT")),
            authenticatedHeaders(cookies),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statusOfCoach()).isEqualTo("ACTIVE");
    }

    @Test
    void saveStep3_onActiveCoach_succeedsAndProfileStaysActive() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        saveAllSteps(cookies);
        publish(cookies);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/steps/3",
            HttpMethod.PUT,
            Map.of("perSessionPrice", 75.0),
            authenticatedHeaders(cookies),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statusOfCoach()).isEqualTo("ACTIVE");
    }

    @Test
    void saveStep3_resubmittingSameSessionPackCount_doesNotViolateUniqueConstraint() {
        // Same insert-before-delete hazard as saveStep2's ageGroups, for uq_session_pack
        // (coach_id, session_count) — a coach editing a pack's price/label while keeping the same
        // session count must not 400.
        String cookies = loginAndGetCookies(COACH_EMAIL);
        saveStep1(cookies);
        saveStep2(cookies);
        httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/steps/3",
            HttpMethod.PUT,
            Map.of("perSessionPrice", 50.0, "sessionPacks",
                List.of(Map.of("sessionCount", 5, "totalPrice", 200.0, "label", "Starter"))),
            authenticatedHeaders(cookies),
            Map.class
        );

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/steps/3",
            HttpMethod.PUT,
            Map.of("perSessionPrice", 50.0, "sessionPacks",
                List.of(Map.of("sessionCount", 5, "totalPrice", 180.0, "label", "Starter Discounted"))),
            authenticatedHeaders(cookies),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void saveStep3_twoPacksWithSameSessionCount_returnsMappedDuplicateError() {
        // Review audit item 2: EditCoachPricingDialog blocks duplicate session counts client-side,
        // but that is not a backstop — this submits the duplicate directly, the way a second tab
        // resubmitting a stale pack list or any direct API caller would. uq_session_pack
        // (coach_id, session_count) rejects the second row of the SAME insert batch, which the
        // flush-before-reinsert fix deliberately does not address (it only orders deletes before
        // inserts). Unmapped this surfaced as an untranslated generic.dataError.
        String cookies = loginAndGetCookies(COACH_EMAIL);
        saveStep1(cookies);
        saveStep2(cookies);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/steps/3",
            HttpMethod.PUT,
            Map.of("perSessionPrice", 50.0, "sessionPacks", List.of(
                Map.of("sessionCount", 5, "totalPrice", 200.0, "label", "Starter"),
                Map.of("sessionCount", 5, "totalPrice", 180.0, "label", "Starter Again"))),
            authenticatedHeaders(cookies),
            Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                // the point of the mapping: a specific, translatable key rather than generic.dataError
                assertThat(ex.getResponseBodyAsString())
                    .contains("\"errorKey\":\"marketplace.duplicateSessionPackCount\"");
            });
    }

    @Test
    void saveStep4_onActiveCoach_succeedsAndProfileStaysActive() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        saveAllSteps(cookies);
        publish(cookies);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/steps/4",
            HttpMethod.PUT,
            Map.of("windows", List.of(Map.of("dayOfWeek", 2, "startTime", "10:00:00", "endTime", "12:00:00",
                "canonicalTimezone", "Europe/Berlin"))),
            authenticatedHeaders(cookies),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statusOfCoach()).isEqualTo("ACTIVE");
    }

    // ---- AC3: photo delete ----

    @Test
    void deletePhoto_asCoach_returns204AndPhotoUrlNullOnNextFetch() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        saveStep1(cookies);
        saveStep2(cookies);
        saveStep3(cookies);
        saveStep4(cookies);
        String photoKey = "coach_profile/" + COACH_ID + "/2026/06/test-photo.jpg";
        httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/steps/5",
            HttpMethod.PUT,
            Map.of("photoUrl", photoKey),
            authenticatedHeaders(cookies),
            Map.class
        );
        assertThat(jdbcTemplate.queryForObject(
            "SELECT photo_url FROM marketplace.coach_profiles WHERE user_id = ?", String.class, COACH_ID))
            .isNotNull();
        // FileStorageService.softDelete looks the key up in main.file_storage_objects and checks
        // ownership via created_by — insert the matching row directly, as the real sign/confirm-upload
        // flow would have, rather than driving the full S3-backed flow through this IT.
        insertFileStorageObject(photoKey, COACH_EMAIL);

        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/photo", HttpMethod.DELETE, null, authenticatedHeaders(cookies), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        ResponseEntity<Map> fetched = httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE, HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);
        assertThat(fetched.getBody().get("photoUrl")).isNull();
    }

    @Test
    void deletePhoto_noExistingPhoto_isNoOpAndReturns204() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        saveStep1(cookies);

        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/photo", HttpMethod.DELETE, null, authenticatedHeaders(cookies), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    // ---- skillars-deferred-139 review D2: deletePhoto tolerates a missing/foreign-owned storage row ----

    @Test
    void deletePhoto_storageObjectAlreadyGone_stillClearsPhotoUrlAndReturns204() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        saveStep1(cookies);
        saveStep2(cookies);
        saveStep3(cookies);
        saveStep4(cookies);
        String photoKey = "coach_profile/" + COACH_ID + "/2026/06/vanished-photo.jpg";
        httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/steps/5",
            HttpMethod.PUT,
            Map.of("photoUrl", photoKey),
            authenticatedHeaders(cookies),
            Map.class
        );
        // Deliberately no matching main.file_storage_objects row inserted — simulates the key
        // pointing at a row that was already soft-deleted independently (e.g. via StorageResource's
        // own DELETE /api/storage/**).

        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/photo", HttpMethod.DELETE, null, authenticatedHeaders(cookies), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        ResponseEntity<Map> fetched = httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE, HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);
        assertThat(fetched.getBody().get("photoUrl")).isNull();
    }

    @Test
    void deletePhoto_storageObjectForeignOwned_stillClearsPhotoUrlAndReturns204() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        saveStep1(cookies);
        saveStep2(cookies);
        saveStep3(cookies);
        saveStep4(cookies);
        String photoKey = "coach_profile/" + COACH_ID + "/2026/06/stale-owner-photo.jpg";
        httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/steps/5",
            HttpMethod.PUT,
            Map.of("photoUrl", photoKey),
            authenticatedHeaders(cookies),
            Map.class
        );
        // created_by deliberately NOT this coach's login — mirrors the reachable real-world case of
        // an email change (UserProfileService.updateUserEmail updates the user's login but not any
        // already-created file_storage_objects row's created_by).
        insertFileStorageObject(photoKey, "someone-else@skillars-test.com");

        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/photo", HttpMethod.DELETE, null, authenticatedHeaders(cookies), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        ResponseEntity<Map> fetched = httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE, HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);
        assertThat(fetched.getBody().get("photoUrl")).isNull();
    }

    @Test
    void deletePhoto_asParent_returns403() {
        String cookies = loginAndGetCookies(PARENT_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/photo", HttpMethod.DELETE, null, authenticatedHeaders(cookies), Void.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ---- helpers ----

    private void insertFileStorageObject(String key, String createdByLogin) {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.file_storage_objects " +
                "(id, key, owner_id, original_filename, content_type, size_bytes, provider, bucket, " +
                "upload_confirmed_at, created_by, created_date) " +
                "VALUES (?, ?, ?, 'test-photo.jpg', 'image/jpeg', 1024, 'local', 'test-bucket', ?, ?, ?)",
                9300000099L, key, String.valueOf(COACH_ID), Timestamp.from(Instant.now()),
                createdByLogin, Timestamp.from(Instant.now())
            );
            return null;
        });
    }

    private String statusOfCoach() {
        return jdbcTemplate.queryForObject(
            "SELECT status FROM marketplace.coach_profiles WHERE user_id = ?", String.class, COACH_ID);
    }

    private void publish(String cookies) {
        httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/publish", HttpMethod.POST, null, authenticatedHeaders(cookies), Map.class);
    }

    private void saveStep1(String cookies) {
        httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/steps/1",
            HttpMethod.PUT,
            Map.of("displayName", "Coach Name", "bio", "Bio text", "city", "Berlin", "district", "Mitte",
                "languages", List.of("English"), "canonicalTimezone", "Europe/Berlin"),
            authenticatedHeaders(cookies),
            Map.class
        );
    }

    private void saveStep2(String cookies) {
        httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/steps/2",
            HttpMethod.PUT,
            Map.of("specialties", List.of("Dribbling"), "ageGroups", List.of("ADULT")),
            authenticatedHeaders(cookies),
            Map.class
        );
    }

    private void saveStep3(String cookies) {
        httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/steps/3",
            HttpMethod.PUT,
            Map.of("perSessionPrice", 50.0),
            authenticatedHeaders(cookies),
            Map.class
        );
    }

    private void saveStep4(String cookies) {
        httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/steps/4",
            HttpMethod.PUT,
            Map.of("windows", List.of(Map.of("dayOfWeek", 1, "startTime", "09:00:00", "endTime", "11:00:00",
                "canonicalTimezone", "Europe/Berlin"))),
            authenticatedHeaders(cookies),
            Map.class
        );
    }

    private void saveAllSteps(String cookies) {
        saveStep1(cookies);
        saveStep2(cookies);
        saveStep3(cookies);
        saveStep4(cookies);
        httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/steps/5", HttpMethod.PUT, Map.of(), authenticatedHeaders(cookies), Map.class);
    }

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

    private void insertCoachUser(long id, String email, String passwordHash) {
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
            id,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            email,
            "670" + (id % 10000000),
            email, passwordHash
        );
    }

    private void insertParentUser(long id, String email, String passwordHash) {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" " +
            "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, " +
            "status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, " +
            "activated, locked, login, login_id_type, password_hash, otp_enabled, " +
            "skillars_role, verification_status) " +
            "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, " +
            "'ACTIVE', '1985-06-01', ?, 'Test', 'OTHER', 'en', 'Parent', 'DE', ?, " +
            "true, false, ?, 'EMAIL', ?, false, " +
            "'PARENT', 'BASIC_VERIFIED')",
            id,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            email,
            "670" + (id % 10000000 + 1),
            email, passwordHash
        );
    }
}
