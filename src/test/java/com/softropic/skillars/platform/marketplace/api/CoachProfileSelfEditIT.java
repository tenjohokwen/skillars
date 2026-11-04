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

    // ---- skillars-deferred-168: the builder's pack IS the coach's purchasable tier ----

    @Test
    void saveStep3_onePack_becomesTheActiveTier_andTheOwnProfileShowsIt() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        saveStep1(cookies);
        saveStep2(cookies);

        assertThat(putStep3(cookies, 40.0, List.of(pack(10, 380.0, "Season 10"))).getStatusCode())
            .isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> tiers = tiersOfCoach();
        assertThat(tiers).singleElement().satisfies(t -> {
            assertThat(t.get("is_active")).isEqualTo(true);
            assertThat(t.get("session_count")).isEqualTo(10);
            assertThat((java.math.BigDecimal) t.get("total_price")).isEqualByComparingTo("380");
            assertThat((java.math.BigDecimal) t.get("price_per_session")).isEqualByComparingTo("38.00");
            assertThat(t.get("label")).isEqualTo("Season 10");
        });
        assertThat(ownSessionPacks(cookies)).singleElement().satisfies(p -> {
            assertThat(p.get("sessionCount")).isEqualTo(10);
            assertThat(((Number) p.get("totalPrice")).doubleValue()).isEqualTo(380.0);
            assertThat(p.get("label")).isEqualTo("Season 10");
        });
    }

    @Test
    void saveStep3_unchangedPack_createsNoNewTier_aChangedOneReplacesIt() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        saveStep1(cookies);
        saveStep2(cookies);
        putStep3(cookies, 50.0, List.of(pack(5, 200.0, null)));

        // Unchanged (the blank label was stored as the default and reads back as null), price edit only.
        putStep3(cookies, 55.0, List.of(pack(5, 200.0, "")));
        assertThat(tiersOfCoach()).hasSize(1);
        assertThat(ownSessionPacks(cookies)).singleElement().satisfies(p -> assertThat(p.get("label")).isNull());

        putStep3(cookies, 55.0, List.of(pack(5, 180.0, "Starter")));
        putStep3(cookies, 55.0, List.of(pack(5, 180.0, "Starter")));
        List<Map<String, Object>> tiers = tiersOfCoach();
        assertThat(tiers).hasSize(2);
        assertThat(tiers).filteredOn(t -> Boolean.TRUE.equals(t.get("is_active"))).singleElement()
            .satisfies(t -> assertThat(t.get("label")).isEqualTo("Starter"));
    }

    @Test
    void saveStep3_emptyPackListWithdrawsTheOffer_nullLeavesItAlone() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        saveStep1(cookies);
        saveStep2(cookies);
        putStep3(cookies, 50.0, List.of(pack(5, 200.0, "Starter")));

        saveStep3(cookies); // no sessionPacks key: the offer is untouched
        assertThat(activeTierCount()).isEqualTo(1);

        putStep3(cookies, 50.0, List.of());
        assertThat(activeTierCount()).isZero();
        assertThat(tiersOfCoach()).hasSize(1); // deactivated, never deleted
        assertThat(ownSessionPacks(cookies)).isEmpty();
    }

    @Test
    void saveStep3_invalidPacks_returnTheValidationEnvelope_andLeaveEverythingUnchanged() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        saveStep1(cookies);
        saveStep2(cookies);
        putStep3(cookies, 50.0, List.of(pack(5, 200.0, "Starter")));

        assertValidation400(cookies, List.of(pack(5, 200.0, "Starter"), pack(10, 350.0, null)), "sessionPacks");
        assertValidation400(cookies, List.of(pack(5, 10.005, null)), "sessionPacks[0].totalPrice");
        assertValidation400(cookies, List.of(pack(5, 50000.01, null)), "sessionPacks[0].totalPrice");
        assertValidation400(cookies, List.of(pack(101, 500.0, null)), "sessionPacks[0].sessionCount");
        assertValidation400(cookies, List.of(pack(5, 200.0, "call 0791 112 3456")), "sessionPacks[0].label");
        assertValidation400(cookies, List.of(pack(100, 0.49, null)), "sessionPacks[0].perSessionPriceValid");

        assertThat(tiersOfCoach()).singleElement().satisfies(t -> assertThat(t.get("label")).isEqualTo("Starter"));
        assertThat(pricingOfCoach()).isEqualByComparingTo("50");
    }

    @Test
    void saveStep3_resendingALongTierPageLabel_succeedsWithoutChurn() {
        // AC4: the "My Session Pack Offer" page allows 200-character labels and the Edit pricing dialog re-sends
        // whatever the profile shows. Before the bound was unified, that blocked every later pricing save.
        String cookies = loginAndGetCookies(COACH_EMAIL);
        saveStep1(cookies);
        saveStep2(cookies);
        saveStep3(cookies);
        String longLabel = "L".repeat(150);
        ResponseEntity<Map> created = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/payment/coaches/me/session-pack-tiers", HttpMethod.POST,
            Map.of("label", longLabel, "sessionCount", 8, "totalPrice", 300.0),
            authenticatedHeaders(cookies), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(ownSessionPacks(cookies)).singleElement()
            .satisfies(p -> assertThat(p.get("label")).isEqualTo(longLabel));
        assertThat(putStep3(cookies, 60.0, List.of(pack(8, 300.0, longLabel))).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        assertThat(tiersOfCoach()).hasSize(1);
        assertThat(pricingOfCoach()).isEqualByComparingTo("60");
    }

    @Test
    void publicProfilePack_isTheTierTheParentBuys() {
        // AC4: the profile's pack and the tier the purchase page loads (and purchasePack charges) are one row.
        String cookies = loginAndGetCookies(COACH_EMAIL);
        saveAllSteps(cookies);
        putStep3(cookies, 40.0, List.of(pack(10, 380.0, "Season 10")));
        publish(cookies);
        assertThat(statusOfCoach()).isEqualTo("ACTIVE");
        String coachUuid = jdbcTemplate.queryForObject(
            "SELECT id::text FROM marketplace.coach_profiles WHERE user_id = ?", String.class, COACH_ID);

        String parentCookies = loginAndGetCookies(PARENT_EMAIL);
        ResponseEntity<Map> profile = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/marketplace/coaches/" + coachUuid, HttpMethod.GET, null,
            authenticatedHeaders(parentCookies), Map.class);
        ResponseEntity<Map> tier = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/payment/coaches/" + coachUuid + "/session-pack-tiers", HttpMethod.GET, null,
            authenticatedHeaders(parentCookies), Map.class);

        List<Map<String, Object>> packs = (List<Map<String, Object>>) profile.getBody().get("sessionPacks");
        assertThat(packs).singleElement().satisfies(p -> {
            assertThat(p.get("sessionCount")).isEqualTo(tier.getBody().get("sessionCount"));
            assertThat(((Number) p.get("totalPrice")).doubleValue())
                .isEqualTo(((Number) tier.getBody().get("totalPrice")).doubleValue());
            assertThat(p.get("label")).isEqualTo(tier.getBody().get("label"));
        });
    }

    @Test
    void concurrentStep3Saves_firstPack_leaveOneActiveTier_andAnyLoserIsA409() throws Exception {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        saveStep1(cookies);
        saveStep2(cookies);
        saveStep3(cookies);

        assertRaceInvariant(cookies);
    }

    @Test
    void concurrentStep3Saves_existingTier_leaveOneActiveTier_andAnyLoserIsA409() throws Exception {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        saveStep1(cookies);
        saveStep2(cookies);
        putStep3(cookies, 50.0, List.of(pack(5, 200.0, "Starter")));

        assertRaceInvariant(cookies);
    }

    /**
     * AC6: two step-3 saves with different prices and packs, released together. Whether they really overlap is
     * up to the scheduler; whatever happens, exactly one tier is active, nothing is a 500, and when one loses
     * (409: payment.tierRaceConflict when no tier existed, generic.conflict from @Version when one did) its
     * pricing write rolled back with it, so pricing and pack both belong to the winner.
     */
    private void assertRaceInvariant(String cookies) throws Exception {
        record Outcome(double price, int count, int status, String body) {}
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<Outcome>> futures = new java.util.ArrayList<>();
            for (double[] variant : new double[][] {{61.0, 7, 280.0}, {62.0, 9, 340.0}}) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        ResponseEntity<Map> r = putStep3(cookies, variant[0],
                            List.of(pack((int) variant[1], variant[2], null)));
                        return new Outcome(variant[0], (int) variant[1], r.getStatusCode().value(), "");
                    } catch (org.springframework.web.client.HttpStatusCodeException e) {
                        return new Outcome(variant[0], (int) variant[1], e.getStatusCode().value(),
                            e.getResponseBodyAsString());
                    }
                }));
            }
            start.countDown();
            List<Outcome> outcomes = new java.util.ArrayList<>();
            for (var f : futures) {
                outcomes.add(f.get(60, java.util.concurrent.TimeUnit.SECONDS));
            }

            assertThat(outcomes).allSatisfy(o -> assertThat(o.status()).isIn(200, 409));
            assertThat(outcomes).filteredOn(o -> o.status() == 409).allSatisfy(o ->
                assertThat(o.body()).containsAnyOf("payment.tierRaceConflict", "generic.conflict"));
            assertThat(activeTierCount()).isEqualTo(1);
            List<Outcome> winners = outcomes.stream().filter(o -> o.status() == 200).toList();
            assertThat(winners).isNotEmpty();
            if (winners.size() == 1) {
                assertThat(pricingOfCoach()).isEqualByComparingTo(String.valueOf(winners.get(0).price()));
                assertThat(jdbcTemplate.queryForObject(
                    "SELECT t.session_count FROM payment.session_pack_tiers t "
                        + "JOIN marketplace.coach_profiles c ON c.id = t.coach_id WHERE c.user_id = ? AND t.is_active",
                    Integer.class, COACH_ID)).isEqualTo(winners.get(0).count());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static Map<String, Object> pack(int count, double totalPrice, String label) {
        Map<String, Object> pack = new java.util.HashMap<>();
        pack.put("sessionCount", count);
        pack.put("totalPrice", totalPrice);
        pack.put("label", label);
        return pack;
    }

    private ResponseEntity<Map> putStep3(String cookies, double perSessionPrice, List<Map<String, Object>> packs) {
        return httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/steps/3",
            HttpMethod.PUT,
            Map.of("perSessionPrice", perSessionPrice, "sessionPacks", packs),
            authenticatedHeaders(cookies),
            Map.class
        );
    }

    private String errorBody(String cookies, List<Map<String, Object>> packs) {
        try {
            putStep3(cookies, 99.0, packs);
        } catch (HttpClientErrorException e) {
            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            return e.getResponseBodyAsString();
        }
        throw new AssertionError("expected a 400 for " + packs);
    }

    private void assertValidation400(String cookies, List<Map<String, Object>> packs, String field) {
        assertThat(errorBody(cookies, packs))
            .contains("\"errorKey\":\"validation.invalidData\"")
            .contains("\"field\":\"" + field + "\"")
            .doesNotContain("payment.invalidPackTier")
            .doesNotContain("generic.requestError");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> ownSessionPacks(String cookies) {
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE, HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);
        return (List<Map<String, Object>>) response.getBody().get("sessionPacks");
    }

    private List<Map<String, Object>> tiersOfCoach() {
        return jdbcTemplate.queryForList(
            "SELECT t.* FROM payment.session_pack_tiers t JOIN marketplace.coach_profiles c ON c.id = t.coach_id "
                + "WHERE c.user_id = ? ORDER BY t.created_at", COACH_ID);
    }

    private int activeTierCount() {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment.session_pack_tiers t JOIN marketplace.coach_profiles c ON c.id = t.coach_id "
                + "WHERE c.user_id = ? AND t.is_active", Integer.class, COACH_ID);
    }

    private java.math.BigDecimal pricingOfCoach() {
        return jdbcTemplate.queryForObject(
            "SELECT p.per_session_price FROM marketplace.coach_pricing p JOIN marketplace.coach_profiles c "
                + "ON c.id = p.coach_id WHERE c.user_id = ?", java.math.BigDecimal.class, COACH_ID);
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
        String photoKey = "coach_profile/" + COACH_ID + "/2026/06/" + java.util.UUID.randomUUID() + ".jpg";
        // saveStep5 only links a confirmed upload recorded as the caller's own.
        insertFileStorageObject(photoKey, COACH_EMAIL);
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
        // ownership via created_by — the row inserted above is what the real sign/confirm-upload flow writes.

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
        String photoKey = "coach_profile/" + COACH_ID + "/2026/06/" + java.util.UUID.randomUUID() + ".jpg";
        insertFileStorageObject(photoKey, COACH_EMAIL);
        httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/steps/5",
            HttpMethod.PUT,
            Map.of("photoUrl", photoKey),
            authenticatedHeaders(cookies),
            Map.class
        );
        // Soft-delete the row after linking — simulates the key pointing at a row that was already
        // soft-deleted independently (e.g. via StorageResource's own DELETE /api/storage/**).
        transactionTemplate.execute(status -> jdbcTemplate.update(
            "UPDATE main.file_storage_objects SET deleted_at = now() WHERE key = ?", photoKey));

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
        String photoKey = "coach_profile/" + COACH_ID + "/2026/06/" + java.util.UUID.randomUUID() + ".jpg";
        // created_by deliberately NOT this coach's login — mirrors the reachable real-world case of
        // an email change (UserProfileService.updateUserEmail updates the user's login but not any
        // already-created file_storage_objects row's created_by). owner_id is still the coach.
        insertFileStorageObject(photoKey, "someone-else@skillars-test.com");
        httpTestClient.makeHttpRequest(
            baseUrl() + PROFILE_BASE + "/steps/5",
            HttpMethod.PUT,
            Map.of("photoUrl", photoKey),
            authenticatedHeaders(cookies),
            Map.class
        );

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
