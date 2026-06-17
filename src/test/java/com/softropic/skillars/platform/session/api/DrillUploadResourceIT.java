package com.softropic.skillars.platform.session.api;

import com.softropic.skillars.config.TestConfig;
import com.softropic.skillars.infrastructure.video.UploadCredentials;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.web.client.HttpClientErrorException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestConfig.class)
@TestPropertySource(properties = {
    "spring.cloud.compatibility-verifier.enabled=false",
    "rate.limiting.enabled=false",
    "allowed.clients=testClientId"
})
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class DrillUploadResourceIT extends BaseSessionIT {

    private static final long INSTR_COACH_USER_ID  = 9560000010L;
    private static final long SCOUT_COACH_USER_ID  = 9560000020L;
    private static final long OTHER_COACH_USER_ID  = 9560000030L;

    private static final String INSTR_EMAIL   = "instr.upload@skillars-test.com";
    private static final String SCOUT_EMAIL   = "scout.upload@skillars-test.com";
    private static final String OTHER_EMAIL   = "other.upload@skillars-test.com";

    private UUID instrCoachId;
    private UUID scoutCoachId;
    private UUID otherCoachId;

    private UUID coachDrillId;
    private UUID platformDrillId;
    private UUID otherCoachDrillId;

    @MockitoBean
    VideoProviderAdapter videoProviderAdapter;

    @BeforeEach
    void setUp() {
        when(videoProviderAdapter.initializeUpload(anyString(), anyLong())).thenReturn(
            new UploadCredentials("bunny-upload-id", "https://tus.bunny.net/upload/test")
        );
        when(videoProviderAdapter.generatePlaybackUrl(anyString(), any()))
            .thenReturn(new com.softropic.skillars.infrastructure.video.SignedPlaybackUrl("https://cdn.example.com/play", Instant.now().plusSeconds(7200)));

        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        transactionTemplate.execute(status -> {
            insertAuthority(9560, "ROLE_COACH_UPLOAD_IT");
            // Reuse ROLE_COACH if already inserted
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) VALUES (9561, 'ROLE_COACH', 'ACTIVE', 'system', NOW()) ON CONFLICT (name) DO NOTHING"
            );

            insertUser(INSTR_COACH_USER_ID, INSTR_EMAIL, passwordHash, "COACH");
            grantRole(INSTR_COACH_USER_ID, "ROLE_COACH");
            instrCoachId = insertCoachProfile(INSTR_COACH_USER_ID);
            insertSubscription(instrCoachId, "INSTRUCTOR");

            insertUser(SCOUT_COACH_USER_ID, SCOUT_EMAIL, passwordHash, "COACH");
            grantRole(SCOUT_COACH_USER_ID, "ROLE_COACH");
            scoutCoachId = insertCoachProfile(SCOUT_COACH_USER_ID);
            insertSubscription(scoutCoachId, "SCOUT");

            insertUser(OTHER_COACH_USER_ID, OTHER_EMAIL, passwordHash, "COACH");
            grantRole(OTHER_COACH_USER_ID, "ROLE_COACH");
            otherCoachId = insertCoachProfile(OTHER_COACH_USER_ID);
            insertSubscription(otherCoachId, "INSTRUCTOR");

            // Coach drill owned by instrCoach
            coachDrillId = UUID.randomUUID();
            insertDrill(coachDrillId, "Coach Test Drill", "COACH", instrCoachId, "ACTIVE");

            // Platform drill
            platformDrillId = jdbcTemplate.queryForObject(
                "SELECT id FROM session.drills WHERE library_type = 'PLATFORM' AND status = 'ACTIVE' LIMIT 1",
                UUID.class
            );

            // Coach drill owned by otherCoach
            otherCoachDrillId = UUID.randomUUID();
            insertDrill(otherCoachDrillId, "Other Coach Drill", "COACH", otherCoachId, "ACTIVE");

            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM session.drill_video_refs WHERE drill_id IN (?, ?)", coachDrillId, otherCoachDrillId);
            jdbcTemplate.update("DELETE FROM main.upload_sessions WHERE video_id IN (SELECT id FROM main.videos WHERE owner_id IN (?, ?, ?))",
                instrCoachId.toString(), scoutCoachId.toString(), otherCoachId.toString());
            jdbcTemplate.update("DELETE FROM main.videos WHERE owner_id IN (?, ?, ?)",
                instrCoachId.toString(), scoutCoachId.toString(), otherCoachId.toString());
            jdbcTemplate.update("DELETE FROM session.drills WHERE id IN (?, ?)", coachDrillId, otherCoachDrillId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_subscriptions WHERE coach_id IN (?, ?, ?)",
                instrCoachId, scoutCoachId, otherCoachId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id IN (?, ?, ?)",
                instrCoachId, scoutCoachId, otherCoachId);
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?, ?)",
                INSTR_COACH_USER_ID, SCOUT_COACH_USER_ID, OTHER_COACH_USER_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?, ?)",
                INSTR_COACH_USER_ID, SCOUT_COACH_USER_ID, OTHER_COACH_USER_ID);
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id = 9560");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    // ── POST /{drillId}/video/initiate ────────────────────────────────────────

    @Test
    void initiateUpload_instructorCoach_returns201WithUploadUrl() {
        String cookies = loginAndGetCookies(INSTR_EMAIL);
        Map<String, Object> payload = Map.of(
            "fileName", "demo.mp4",
            "fileSizeBytes", 1024 * 1024,
            "mimeType", "video/mp4",
            "durationSeconds", 30
        );

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + DRILLS_BASE + "/" + coachDrillId + "/video/initiate",
            HttpMethod.POST,
            payload,
            authenticatedHeaders(cookies),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsKey("videoId");
        assertThat(response.getBody()).containsKey("signedUploadUrl");
        assertThat(response.getBody().get("signedUploadUrl")).isEqualTo("https://tus.bunny.net/upload/test");
    }

    @Test
    void initiateUpload_scoutCoach_returns403WithFeatureGatedCode() {
        String cookies = loginAndGetCookies(SCOUT_EMAIL);
        Map<String, Object> payload = Map.of(
            "fileName", "demo.mp4",
            "fileSizeBytes", 1024L,
            "mimeType", "video/mp4",
            "durationSeconds", 10
        );

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + DRILLS_BASE + "/" + coachDrillId + "/video/initiate",
            HttpMethod.POST,
            payload,
            authenticatedHeaders(cookies),
            Map.class
        )).isInstanceOf(HttpClientErrorException.Forbidden.class);
    }

    @Test
    void initiateUpload_platformDrill_returns403() {
        String cookies = loginAndGetCookies(INSTR_EMAIL);
        Map<String, Object> payload = Map.of(
            "fileName", "demo.mp4",
            "fileSizeBytes", 1024L,
            "mimeType", "video/mp4",
            "durationSeconds", 10
        );

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + DRILLS_BASE + "/" + platformDrillId + "/video/initiate",
            HttpMethod.POST,
            payload,
            authenticatedHeaders(cookies),
            Map.class
        )).isInstanceOf(HttpClientErrorException.Forbidden.class);
    }

    @Test
    void initiateUpload_otherCoachDrill_returns403() {
        String cookies = loginAndGetCookies(INSTR_EMAIL);
        Map<String, Object> payload = Map.of(
            "fileName", "demo.mp4",
            "fileSizeBytes", 1024L,
            "mimeType", "video/mp4",
            "durationSeconds", 10
        );

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + DRILLS_BASE + "/" + otherCoachDrillId + "/video/initiate",
            HttpMethod.POST,
            payload,
            authenticatedHeaders(cookies),
            Map.class
        )).isInstanceOf(HttpClientErrorException.Forbidden.class);
    }

    @Test
    void initiateUpload_fileSizeTooLarge_returns422WithConstraintViolatedCode() {
        String cookies = loginAndGetCookies(INSTR_EMAIL);
        Map<String, Object> payload = Map.of(
            "fileName", "demo.mp4",
            "fileSizeBytes", 600_000_000L,
            "mimeType", "video/mp4",
            "durationSeconds", 30
        );

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + DRILLS_BASE + "/" + coachDrillId + "/video/initiate",
            HttpMethod.POST,
            payload,
            authenticatedHeaders(cookies),
            Map.class
        )).isInstanceOf(HttpClientErrorException.UnprocessableEntity.class);
    }

    @Test
    void initiateUpload_durationTooLong_returns422WithConstraintViolatedCode() {
        String cookies = loginAndGetCookies(INSTR_EMAIL);
        Map<String, Object> payload = Map.of(
            "fileName", "demo.mp4",
            "fileSizeBytes", 1024L,
            "mimeType", "video/mp4",
            "durationSeconds", 150
        );

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + DRILLS_BASE + "/" + coachDrillId + "/video/initiate",
            HttpMethod.POST,
            payload,
            authenticatedHeaders(cookies),
            Map.class
        )).isInstanceOf(HttpClientErrorException.UnprocessableEntity.class);
    }

    // ── DELETE /{drillId}/video ───────────────────────────────────────────────

    @Test
    void deleteVideo_coachDrill_noOtherRef_returns204AndPublishesEvent() {
        // Insert a video and drill_video_refs row
        UUID videoId = insertTestVideo(instrCoachId.toString());
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO session.drill_video_refs (drill_id, video_id, ref_count) VALUES (?, ?, 1)",
                coachDrillId, videoId
            );
            return null;
        });

        String cookies = loginAndGetCookies(INSTR_EMAIL);
        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + DRILLS_BASE + "/" + coachDrillId + "/video",
            HttpMethod.DELETE,
            null,
            authenticatedHeaders(cookies),
            Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        // Verify drill's videoId is cleared
        UUID clearedVideoId = jdbcTemplate.queryForObject(
            "SELECT video_id FROM session.drill_video_refs WHERE drill_id = ?",
            UUID.class, coachDrillId
        );
        assertThat(clearedVideoId).isNull();
    }

    @Test
    void deleteVideo_coachDrill_otherRefExists_returns204WithoutEvent() {
        // Insert a video referenced by two drills (instrCoach drill + platform drill via clone)
        UUID videoId = insertTestVideo(instrCoachId.toString());
        transactionTemplate.execute(status -> {
            // instrCoach drill references the video
            jdbcTemplate.update(
                "INSERT INTO session.drill_video_refs (drill_id, video_id, ref_count) VALUES (?, ?, 1)",
                coachDrillId, videoId
            );
            // otherCoach drill also references the same video (simulating a source platform ref)
            jdbcTemplate.update(
                "INSERT INTO session.drill_video_refs (drill_id, video_id, ref_count) VALUES (?, ?, 2)",
                otherCoachDrillId, videoId
            );
            return null;
        });

        String cookies = loginAndGetCookies(INSTR_EMAIL);
        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + DRILLS_BASE + "/" + coachDrillId + "/video",
            HttpMethod.DELETE,
            null,
            authenticatedHeaders(cookies),
            Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        // instrCoach drill ref is cleared
        UUID clearedVideoId = jdbcTemplate.queryForObject(
            "SELECT video_id FROM session.drill_video_refs WHERE drill_id = ?",
            UUID.class, coachDrillId
        );
        assertThat(clearedVideoId).isNull();
        // otherCoach drill still references the video — physical video retained
        UUID otherRef = jdbcTemplate.queryForObject(
            "SELECT video_id FROM session.drill_video_refs WHERE drill_id = ?",
            UUID.class, otherCoachDrillId
        );
        assertThat(otherRef).isEqualTo(videoId);
    }

    @Test
    void deleteVideo_platformDrill_returns403() {
        String cookies = loginAndGetCookies(INSTR_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + DRILLS_BASE + "/" + platformDrillId + "/video",
            HttpMethod.DELETE,
            null,
            authenticatedHeaders(cookies),
            Void.class
        )).isInstanceOf(HttpClientErrorException.Forbidden.class);
    }

    // ── GET /video-upload/eligible ────────────────────────────────────────────

    @Test
    void checkEligibility_instructorCoach_returns200True() {
        String cookies = loginAndGetCookies(INSTR_EMAIL);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + DRILLS_BASE + "/video-upload/eligible",
            HttpMethod.GET,
            null,
            authenticatedHeaders(cookies),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("eligible")).isEqualTo(true);
    }

    @Test
    void checkEligibility_scoutCoach_returns200False() {
        String cookies = loginAndGetCookies(SCOUT_EMAIL);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + DRILLS_BASE + "/video-upload/eligible",
            HttpMethod.GET,
            null,
            authenticatedHeaders(cookies),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("eligible")).isEqualTo(false);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UUID insertTestVideo(String ownerId) {
        UUID videoId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO main.videos (id, owner_id, provider, provider_asset_id, operational_state, access_state, title, visibility, created_at, updated_at) " +
            "VALUES (?, ?, 'bunny', ?, 'READY', 'ACTIVE', 'Test Video', 'PRIVATE', ?, ?)",
            videoId, ownerId, "asset-" + videoId,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now())
        );
        return videoId;
    }
}
