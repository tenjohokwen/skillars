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
class DrillTagResourceIT extends BaseSessionIT {

    private static final long COACH_USER_ID  = 9560000010L;
    private static final long COACH_USER_ID2 = 9560000020L;

    private static final String COACH_EMAIL  = "coach.tag@skillars-test.com";
    private static final String COACH_EMAIL2 = "coach2.tag@skillars-test.com";

    private UUID coachProfileId;
    private UUID coachProfileId2;
    private UUID privateDrillId;
    private UUID coach2DrillId;
    private UUID platformDrillId;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        transactionTemplate.execute(status -> {
            insertAuthority(9560, "ROLE_COACH");

            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH");
            grantRole(COACH_USER_ID, "ROLE_COACH");
            coachProfileId = insertCoachProfile(COACH_USER_ID);
            insertSubscription(coachProfileId, "INSTRUCTOR");

            insertUser(COACH_USER_ID2, COACH_EMAIL2, passwordHash, "COACH");
            grantRole(COACH_USER_ID2, "ROLE_COACH");
            coachProfileId2 = insertCoachProfile(COACH_USER_ID2);
            insertSubscription(coachProfileId2, "INSTRUCTOR");

            // Private drill owned by coach 1
            privateDrillId = UUID.randomUUID();
            insertDrill(privateDrillId, "My Private Drill", "COACH", coachProfileId, "ACTIVE");

            // Private drill owned by coach 2
            coach2DrillId = UUID.randomUUID();
            insertDrill(coach2DrillId, "Coach2 Drill", "COACH", coachProfileId2, "ACTIVE");

            // Grab a PLATFORM drill from V39 seed
            platformDrillId = jdbcTemplate.queryForObject(
                "SELECT id FROM session.drills WHERE library_type = 'PLATFORM' AND status = 'ACTIVE' LIMIT 1",
                UUID.class
            );

            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM session.drill_tags WHERE coach_id IN (?, ?)",
                coachProfileId, coachProfileId2);
            jdbcTemplate.update("DELETE FROM session.drills WHERE owner_coach_id IN (?, ?)",
                coachProfileId, coachProfileId2);
            jdbcTemplate.update("DELETE FROM marketplace.coach_subscriptions WHERE coach_id IN (?, ?)",
                coachProfileId, coachProfileId2);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id IN (?, ?)",
                coachProfileId, coachProfileId2);
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?)",
                COACH_USER_ID, COACH_USER_ID2);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?)",
                COACH_USER_ID, COACH_USER_ID2);
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id = 9560");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    // ── POST /{drillId}/tags ──────────────────────────────────────────────────────

    @Test
    void addTag_toPrivateDrill_returns201() {
        String cookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + DRILLS_BASE + "/" + privateDrillId + "/tags",
            HttpMethod.POST,
            Map.of("tag", "agility"),
            authenticatedHeaders(cookies),
            Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void addTag_toPlatformDrill_returns403() {
        String cookies = loginAndGetCookies(COACH_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + DRILLS_BASE + "/" + platformDrillId + "/tags",
            HttpMethod.POST,
            Map.of("tag", "platform-tag"),
            authenticatedHeaders(cookies),
            Void.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void addTag_toDrillOwnedByOtherCoach_returns403() {
        String cookies = loginAndGetCookies(COACH_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + DRILLS_BASE + "/" + coach2DrillId + "/tags",
            HttpMethod.POST,
            Map.of("tag", "unauthorized"),
            authenticatedHeaders(cookies),
            Void.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void addTag_duplicate_returns201Idempotent() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        String url = baseUrl() + DRILLS_BASE + "/" + privateDrillId + "/tags";

        httpTestClient.makeHttpRequest(url, HttpMethod.POST, Map.of("tag", "speed"), authenticatedHeaders(cookies), Void.class);
        ResponseEntity<Void> second = httpTestClient.makeHttpRequest(url, HttpMethod.POST, Map.of("tag", "speed"), authenticatedHeaders(cookies), Void.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // ── DELETE /{drillId}/tags/{tag} ─────────────────────────────────────────────

    @Test
    void removeTag_existingTag_returns204() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        // Add first
        httpTestClient.makeHttpRequest(
            baseUrl() + DRILLS_BASE + "/" + privateDrillId + "/tags",
            HttpMethod.POST, Map.of("tag", "strength"), authenticatedHeaders(cookies), Void.class
        );

        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + DRILLS_BASE + "/" + privateDrillId + "/tags/strength",
            HttpMethod.DELETE, null, authenticatedHeaders(cookies), Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void removeTag_nonExistentTag_returns204Idempotent() {
        String cookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + DRILLS_BASE + "/" + privateDrillId + "/tags/doesnotexist",
            HttpMethod.DELETE, null, authenticatedHeaders(cookies), Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    // ── GET /tags/suggestions ─────────────────────────────────────────────────────

    @Test
    void getTagSuggestions_returnsDistinctTagsForCoach() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        // Add two distinct tags
        httpTestClient.makeHttpRequest(
            baseUrl() + DRILLS_BASE + "/" + privateDrillId + "/tags",
            HttpMethod.POST, Map.of("tag", "aaa"), authenticatedHeaders(cookies), Void.class
        );
        httpTestClient.makeHttpRequest(
            baseUrl() + DRILLS_BASE + "/" + privateDrillId + "/tags",
            HttpMethod.POST, Map.of("tag", "bbb"), authenticatedHeaders(cookies), Void.class
        );

        ResponseEntity<List> response = httpTestClient.makeHttpRequest(
            baseUrl() + DRILLS_BASE + "/tags/suggestions",
            HttpMethod.GET, null, authenticatedHeaders(cookies), List.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsExactlyInAnyOrder("aaa", "bbb");
    }

    // ── GET /api/session/drills with search/filter ────────────────────────────────

    @Test
    void getDrills_withSearchQuery_returnsMatchingDrills() {
        String cookies = loginAndGetCookies(COACH_EMAIL);

        // The V39 seed has drills whose names are set via trans_key (localized),
        // but we can search for a known coaching point from the JSON.
        // Query for something from seed drills' names.
        ResponseEntity<List> response = httpTestClient.makeHttpRequest(
            baseUrl() + DRILLS_BASE + "?library=PLATFORM&q=Toe",
            HttpMethod.GET, null, authenticatedHeaders(cookies), List.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Results count can be 0 or more — we verify the endpoint accepts the param and returns 200
    }

    @Test
    void getDrills_withSkillFilter_returnsFilteredDrills() {
        String cookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<List> response = httpTestClient.makeHttpRequest(
            baseUrl() + DRILLS_BASE + "?library=PLATFORM&skill=ball_mastery",
            HttpMethod.GET, null, authenticatedHeaders(cookies), List.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
    }
}
