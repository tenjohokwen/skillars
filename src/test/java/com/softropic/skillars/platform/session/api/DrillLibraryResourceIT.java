package com.softropic.skillars.platform.session.api;

import com.softropic.skillars.config.TestConfig;
import com.softropic.skillars.platform.security.SecurityIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.web.client.HttpClientErrorException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestConfig.class)
@TestPropertySource(properties = {
    "spring.cloud.compatibility-verifier.enabled=false",
    "rate.limiting.enabled=false",
    "allowed.clients=testClientId"
})
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class DrillLibraryResourceIT extends BaseSessionIT {

    private static final long COACH_USER_ID        = 9550000010L;
    private static final long COACH_USER_ID2       = 9550000020L;
    private static final long PARENT_USER_ID       = 9550000030L;
    private static final long SCOUT_COACH_USER_ID  = 9550000040L;
    private static final long INSTR_COACH_USER_ID  = 9550000050L;

    private static final String COACH_EMAIL  = "coach.drill@skillars-test.com";
    private static final String COACH_EMAIL2 = "coach2.drill@skillars-test.com";
    private static final String PARENT_EMAIL = "parent.drill@skillars-test.com";
    private static final String SCOUT_EMAIL  = "scout.drill@skillars-test.com";
    private static final String INSTR_EMAIL  = "instructor.drill@skillars-test.com";

    private UUID coachProfileId;
    private UUID coachProfileId2;
    private UUID scoutCoachProfileId;
    private UUID instructorCoachProfileId;

    private UUID platformDrillId;
    private UUID anotherCoachDrillId;
    private UUID archivedDrillId;
    private UUID sameNamePlatformDrillIdA;
    private UUID sameNamePlatformDrillIdB;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        transactionTemplate.execute(status -> {
            insertAuthority(9550, "ROLE_COACH");
            insertAuthority(9551, "ROLE_PARENT");

            // Coach 1 — main coach (INSTRUCTOR tier)
            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH");
            grantRole(COACH_USER_ID, "ROLE_COACH");
            coachProfileId = insertCoachProfile(COACH_USER_ID);
            insertSubscription(coachProfileId, "INSTRUCTOR");

            // Coach 2 — used for COACH-type drill test
            insertUser(COACH_USER_ID2, COACH_EMAIL2, passwordHash, "COACH");
            grantRole(COACH_USER_ID2, "ROLE_COACH");
            coachProfileId2 = insertCoachProfile(COACH_USER_ID2);
            insertSubscription(coachProfileId2, "INSTRUCTOR");

            // Parent user (for 403 test)
            insertUser(PARENT_USER_ID, PARENT_EMAIL, passwordHash, "PARENT");
            grantRole(PARENT_USER_ID, "ROLE_PARENT");

            // Scout coach (for feature gate test)
            insertUser(SCOUT_COACH_USER_ID, SCOUT_EMAIL, passwordHash, "COACH");
            grantRole(SCOUT_COACH_USER_ID, "ROLE_COACH");
            scoutCoachProfileId = insertCoachProfile(SCOUT_COACH_USER_ID);
            insertSubscription(scoutCoachProfileId, "SCOUT");

            // Instructor coach (for feature gate test)
            insertUser(INSTR_COACH_USER_ID, INSTR_EMAIL, passwordHash, "COACH");
            grantRole(INSTR_COACH_USER_ID, "ROLE_COACH");
            instructorCoachProfileId = insertCoachProfile(INSTR_COACH_USER_ID);
            insertSubscription(instructorCoachProfileId, "INSTRUCTOR");

            // Grab an existing PLATFORM drill id from the V39 seed
            platformDrillId = jdbcTemplate.queryForObject(
                "SELECT id FROM session.drills WHERE library_type = 'PLATFORM' AND status = 'ACTIVE' LIMIT 1",
                UUID.class
            );

            // Insert a COACH-type drill owned by coach2 (to test clone-of-coach-drill returns 403)
            anotherCoachDrillId = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO session.drills (id, name, library_type, owner_coach_id, status, metadata, version) " +
                "VALUES (?, 'Coach Drill', 'COACH', ?, 'ACTIVE', ?::jsonb, 0)",
                anotherCoachDrillId, coachProfileId2,
                "{\"primarySkills\":[\"dribbling\"],\"secondarySkills\":[],\"skillWeighting\":{\"dribbling\":100}," +
                "\"repDensity\":10,\"intensity\":2,\"pressureLevel\":1,\"cognitiveLoad\":1,\"matchRealism\":2," +
                "\"weakFootBias\":false,\"difficultyTier\":\"U12\",\"equipmentRequired\":[\"ball\"]," +
                "\"recommendedGroupSize\":\"2\",\"coachingPoints\":[\"Keep it simple\"]}"
            );

            // Insert an ARCHIVED PLATFORM drill (to test clone returns 404)
            archivedDrillId = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO session.drills (id, name, library_type, owner_coach_id, status, metadata, version) " +
                "VALUES (?, 'Archived Drill', 'PLATFORM', NULL, 'ARCHIVED', ?::jsonb, 0)",
                archivedDrillId,
                "{\"primarySkills\":[\"ball_mastery\"],\"secondarySkills\":[],\"skillWeighting\":{\"ball_mastery\":100}," +
                "\"repDensity\":10,\"intensity\":1,\"pressureLevel\":1,\"cognitiveLoad\":1,\"matchRealism\":1," +
                "\"weakFootBias\":false,\"difficultyTier\":\"U8\",\"equipmentRequired\":[\"ball\"]," +
                "\"recommendedGroupSize\":\"4\",\"coachingPoints\":[\"Focus\"]}"
            );

            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM session.drill_video_refs WHERE drill_id IN " +
                "(SELECT id FROM session.drills WHERE owner_coach_id IN (?, ?, ?, ?))",
                coachProfileId, coachProfileId2, scoutCoachProfileId, instructorCoachProfileId);
            jdbcTemplate.update("DELETE FROM session.drills WHERE owner_coach_id IN (?, ?, ?, ?)",
                coachProfileId, coachProfileId2, scoutCoachProfileId, instructorCoachProfileId);
            jdbcTemplate.update("DELETE FROM session.drills WHERE id IN (?, ?, ?, ?)",
                anotherCoachDrillId, archivedDrillId, sameNamePlatformDrillIdA, sameNamePlatformDrillIdB);
            jdbcTemplate.update("DELETE FROM marketplace.coach_subscriptions WHERE coach_id IN (?, ?, ?, ?)",
                coachProfileId, coachProfileId2, scoutCoachProfileId, instructorCoachProfileId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id IN (?, ?, ?, ?)",
                coachProfileId, coachProfileId2, scoutCoachProfileId, instructorCoachProfileId);
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?, ?, ?, ?)",
                COACH_USER_ID, COACH_USER_ID2, PARENT_USER_ID, SCOUT_COACH_USER_ID, INSTR_COACH_USER_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?, ?, ?, ?)",
                COACH_USER_ID, COACH_USER_ID2, PARENT_USER_ID, SCOUT_COACH_USER_ID, INSTR_COACH_USER_ID);
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id IN (9550, 9551)");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    // ── GET /api/session/drills?library=PLATFORM ─────────────────────────────

    @Test
    void getDrills_platformLibrary_returns200With20Drills() {
        String cookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<List> response = httpTestClient.makeHttpRequest(
            baseUrl() + DRILLS_BASE + "?library=PLATFORM",
            HttpMethod.GET, null, authenticatedHeaders(cookies), List.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(20);
    }

    // ── GET /api/session/drills?library=PRIVATE ──────────────────────────────

    @Test
    void getDrills_privateLibrary_noPrivateDrills_returns200EmptyList() {
        String cookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<List> response = httpTestClient.makeHttpRequest(
            baseUrl() + DRILLS_BASE + "?library=PRIVATE",
            HttpMethod.GET, null, authenticatedHeaders(cookies), List.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    // ── GET /api/session/drills — missing library param ──────────────────────

    @Test
    void getDrills_missingLibraryParam_returns400() {
        String cookies = loginAndGetCookies(COACH_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + DRILLS_BASE,
            HttpMethod.GET, null, authenticatedHeaders(cookies), List.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ── GET /api/session/drills?library=INVALID ──────────────────────────────

    @Test
    void getDrills_invalidLibraryParam_returns400() {
        String cookies = loginAndGetCookies(COACH_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + DRILLS_BASE + "?library=INVALID",
            HttpMethod.GET, null, authenticatedHeaders(cookies), List.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ── POST /api/session/drills/{id}/clone — PLATFORM active drill ──────────

    @Test
    void cloneDrill_activePlatformDrill_returns201AndDrillInPrivateLibrary() {
        String cookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + DRILLS_BASE + "/" + platformDrillId + "/clone",
            HttpMethod.POST, null, authenticatedHeaders(cookies), Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("libraryType")).isEqualTo("COACH");

        // Verify clone appears in private library
        String cookies2 = loginAndGetCookies(COACH_EMAIL);
        ResponseEntity<List> privateList = httpTestClient.makeHttpRequest(
            baseUrl() + DRILLS_BASE + "?library=PRIVATE",
            HttpMethod.GET, null, authenticatedHeaders(cookies2), List.class
        );
        assertThat(privateList.getBody()).isNotEmpty();
    }

    // ── POST /api/session/drills/{unknownId}/clone — unknown drill ───────────

    @Test
    void cloneDrill_unknownDrillId_returns404() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        UUID unknownId = UUID.randomUUID();

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + DRILLS_BASE + "/" + unknownId + "/clone",
            HttpMethod.POST, null, authenticatedHeaders(cookies), Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ── POST clone — COACH-type source drill returns 403 ─────────────────────

    @Test
    void cloneDrill_coachTypeDrill_returns403() {
        String cookies = loginAndGetCookies(COACH_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + DRILLS_BASE + "/" + anotherCoachDrillId + "/clone",
            HttpMethod.POST, null, authenticatedHeaders(cookies), Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ── POST clone — ARCHIVED source drill returns 404 ───────────────────────

    @Test
    void cloneDrill_archivedDrill_returns404() {
        String cookies = loginAndGetCookies(COACH_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + DRILLS_BASE + "/" + archivedDrillId + "/clone",
            HttpMethod.POST, null, authenticatedHeaders(cookies), Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ── POST clone twice — duplicate private drill name returns 409 ──────────

    @Test
    void cloneSameDrillTwice_returns409() {
        String cookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Map> firstClone = httpTestClient.makeHttpRequest(
            baseUrl() + DRILLS_BASE + "/" + platformDrillId + "/clone",
            HttpMethod.POST, null, authenticatedHeaders(cookies), Map.class
        );
        assertThat(firstClone.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + DRILLS_BASE + "/" + platformDrillId + "/clone",
            HttpMethod.POST, null, authenticatedHeaders(cookies), Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    // ── POST clone two different drills sharing a name — duplicate private drill name returns 409 ──

    @Test
    void cloneTwoDifferentDrillsWithSameName_secondReturns409() {
        String cookies = loginAndGetCookies(COACH_EMAIL);

        // Two distinct PLATFORM drills sharing the same name — exercises idx_drills_coach_name_unique
        // (AC 5's "two different source drills cloned to the same name" case), which is a narrower
        // scenario than cloning the same drill twice (that hits the pre-existing idx_drills_clone_uniqueness).
        // Scoped to this test only (not setUp()) so it doesn't inflate the platform-drill count other tests assert on.
        transactionTemplate.execute(status -> {
            sameNamePlatformDrillIdA = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO session.drills (id, name, library_type, owner_coach_id, status, metadata, version) " +
                "VALUES (?, 'Duplicate Name Drill', 'PLATFORM', NULL, 'ACTIVE', ?::jsonb, 0)",
                sameNamePlatformDrillIdA,
                "{\"primarySkills\":[\"passing\"],\"secondarySkills\":[],\"skillWeighting\":{\"passing\":100}," +
                "\"repDensity\":10,\"intensity\":2,\"pressureLevel\":1,\"cognitiveLoad\":1,\"matchRealism\":2," +
                "\"weakFootBias\":false,\"difficultyTier\":\"U12\",\"equipmentRequired\":[\"ball\"]," +
                "\"recommendedGroupSize\":\"2\",\"coachingPoints\":[\"Pass early\"]}"
            );
            sameNamePlatformDrillIdB = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO session.drills (id, name, library_type, owner_coach_id, status, metadata, version) " +
                "VALUES (?, 'Duplicate Name Drill', 'PLATFORM', NULL, 'ACTIVE', ?::jsonb, 0)",
                sameNamePlatformDrillIdB,
                "{\"primarySkills\":[\"shooting\"],\"secondarySkills\":[],\"skillWeighting\":{\"shooting\":100}," +
                "\"repDensity\":10,\"intensity\":2,\"pressureLevel\":1,\"cognitiveLoad\":1,\"matchRealism\":2," +
                "\"weakFootBias\":false,\"difficultyTier\":\"U12\",\"equipmentRequired\":[\"ball\"]," +
                "\"recommendedGroupSize\":\"2\",\"coachingPoints\":[\"Shoot often\"]}"
            );
            return null;
        });

        ResponseEntity<Map> firstClone = httpTestClient.makeHttpRequest(
            baseUrl() + DRILLS_BASE + "/" + sameNamePlatformDrillIdA + "/clone",
            HttpMethod.POST, null, authenticatedHeaders(cookies), Map.class
        );
        assertThat(firstClone.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + DRILLS_BASE + "/" + sameNamePlatformDrillIdB + "/clone",
            HttpMethod.POST, null, authenticatedHeaders(cookies), Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    // ── GET /api/session/drills as parent returns 403 ────────────────────────

    @Test
    void getDrills_asParent_returns403() {
        String cookies = loginAndGetCookies(PARENT_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + DRILLS_BASE + "?library=PLATFORM",
            HttpMethod.GET, null, authenticatedHeaders(cookies), List.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }
}
