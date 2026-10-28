package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.filestorage.service.FileStorageService;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.security.contract.AgeTier;
import com.softropic.skillars.platform.security.contract.PlayerPosition;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.video.BaseVideoIT;
import com.softropic.skillars.platform.video.contract.AccessState;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.VideoType;
import com.softropic.skillars.platform.video.contract.Visibility;
import com.softropic.skillars.platform.video.contract.event.VideoUploadedEvent;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoModerationScanRepository;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.AopTestUtils;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class MinorSafetyGateIT extends BaseVideoIT {

    @MockitoBean
    VideoProviderAdapter videoProviderAdapter;

    // skillars-deferred-160 AC8: present so this class shares the {VideoProviderAdapter, FileStorageService}
    // mock set (and Spring context) with its peers. Keep this exact field name; it is part of the cache key.
    @MockitoBean FileStorageService fileStorageService;

    @Autowired ModerationOrchestrationService moderationOrchestrationServiceProxy;
    @Autowired VideoRepository videoRepository;
    @Autowired VideoModerationScanRepository videoModerationScanRepository;
    @Autowired PlayerProfileRepository playerProfileRepository;

    private static final long PARENT_ID = 98_000_001L;
    private static final long SELF_PLAYER_USER_ID = 98_000_002L;

    private ModerationOrchestrationService orchestrationService;

    @BeforeEach
    void setUp() {
        // Bypass @Async proxy so the pipeline runs synchronously in the test thread
        orchestrationService = AopTestUtils.getUltimateTargetObject(moderationOrchestrationServiceProxy);
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM main.video_moderation_scans");
            jdbcTemplate.update("DELETE FROM main.videos");
            jdbcTemplate.update("DELETE FROM main.player_profiles WHERE parent_id = ? OR user_id = ?",
                PARENT_ID, SELF_PLAYER_USER_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?)", PARENT_ID, SELF_PLAYER_USER_ID);
            insertUser(PARENT_ID, "PARENT");
            insertUser(SELF_PLAYER_USER_ID, "PLAYER");
            return null;
        });
        doNothing().when(videoProviderAdapter).triggerTranscoding(any());
    }


    // ADR "Minors never upload" (2026-10-28): uploads by minors are refused at initiation, so this layer
    // is only a defensive re-check — it fails CLOSED (HIDDEN + admin alert), never "approve".

    @Test
    void minorPlayer_reachingModeration_failsClosed_hiddenAndNeverTranscoded() {
        PlayerProfile minor = seedManagedPlayer(LocalDate.now().minusYears(15));
        Video video = seedVideoInScanning(String.valueOf(minor.getId()));

        orchestrationService.onVideoUploaded(new VideoUploadedEvent(video.getId(), String.valueOf(minor.getId())));

        Video updated = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(updated.getOperationalState()).isEqualTo(OperationalState.HIDDEN);

        var scan = videoModerationScanRepository.findByVideoIdAndLayer(video.getId(), "MINOR_GATE");
        assertThat(scan).isPresent();
        assertThat(scan.get().getOutcome()).isEqualTo("FLAGGED");

        verify(videoProviderAdapter, never()).triggerTranscoding(any());
    }

    @Test
    void selfRegisteredAdultPlayer_gatePassed_advancesToTranscoding() {
        PlayerProfile adult = seedSelfRegisteredPlayer(LocalDate.now().minusYears(22));
        Video video = seedVideoInScanning(String.valueOf(adult.getId()));

        orchestrationService.onVideoUploaded(new VideoUploadedEvent(video.getId(), String.valueOf(adult.getId())));

        Video updated = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(updated.getOperationalState()).isEqualTo(OperationalState.TRANSCODING);

        var scan = videoModerationScanRepository.findByVideoIdAndLayer(video.getId(), "MINOR_GATE");
        assertThat(scan).isPresent();
        assertThat(scan.get().getOutcome()).isEqualTo("PASSED");

        verify(videoProviderAdapter).triggerTranscoding(video.getProviderAssetId());
    }

    @Test
    void parentManagedProfile_evenWithAdultDob_failsClosed() {
        PlayerProfile managed = seedManagedPlayer(LocalDate.now().minusYears(22));
        Video video = seedVideoInScanning(String.valueOf(managed.getId()));

        orchestrationService.onVideoUploaded(new VideoUploadedEvent(video.getId(), String.valueOf(managed.getId())));

        assertThat(videoRepository.findById(video.getId()).orElseThrow().getOperationalState())
            .isEqualTo(OperationalState.HIDDEN);
        verify(videoProviderAdapter, never()).triggerTranscoding(any());
    }

    @Test
    void coachVideo_uuidOwnerId_gateSkipped_advancesToTranscoding() {
        String coachOwnerId = "550e8400-e29b-41d4-a716-446655440000";
        Video video = seedVideoInScanning(coachOwnerId);

        orchestrationService.onVideoUploaded(new VideoUploadedEvent(video.getId(), coachOwnerId));

        Video updated = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(updated.getOperationalState()).isEqualTo(OperationalState.TRANSCODING);

        var scan = videoModerationScanRepository.findByVideoIdAndLayer(video.getId(), "MINOR_GATE");
        assertThat(scan).isPresent();
        assertThat(scan.get().getOutcome()).isEqualTo("SKIPPED");
    }

    @Test
    void orphanedOwnerId_noPlayerProfile_failsClosed() {
        // Previously "treated as adult" and transcoded; an unresolvable owner is now hidden.
        String orphanedId = "88888888";
        Video video = seedVideoInScanning(orphanedId);

        orchestrationService.onVideoUploaded(new VideoUploadedEvent(video.getId(), orphanedId));

        assertThat(videoRepository.findById(video.getId()).orElseThrow().getOperationalState())
            .isEqualTo(OperationalState.HIDDEN);
        verify(videoProviderAdapter, never()).triggerTranscoding(any());
    }

    @Test
    void minorGate_duplicateEvent_staysHidden() {
        PlayerProfile minor = seedManagedPlayer(LocalDate.now().minusYears(13));
        Video video = seedVideoInScanning(String.valueOf(minor.getId()));

        orchestrationService.onVideoUploaded(new VideoUploadedEvent(video.getId(), String.valueOf(minor.getId())));
        // Second event (retry) — video is now HIDDEN; the SCANNING transition is refused and skipped.
        orchestrationService.onVideoUploaded(new VideoUploadedEvent(video.getId(), String.valueOf(minor.getId())));

        assertThat(videoRepository.findById(video.getId()).orElseThrow().getOperationalState())
            .isEqualTo(OperationalState.HIDDEN);
        verify(videoProviderAdapter, never()).triggerTranscoding(any());
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private PlayerProfile seedManagedPlayer(LocalDate dob) {
        PlayerProfile profile = basePlayer(dob);
        profile.setParentId(PARENT_ID);
        return playerProfileRepository.save(profile);
    }

    private PlayerProfile seedSelfRegisteredPlayer(LocalDate dob) {
        PlayerProfile profile = basePlayer(dob);
        profile.setUserId(SELF_PLAYER_USER_ID);
        return playerProfileRepository.save(profile);
    }

    private static PlayerProfile basePlayer(LocalDate dob) {
        int age = LocalDate.now().getYear() - dob.getYear();
        PlayerProfile profile = new PlayerProfile();
        profile.setName("Gate Test Player");
        profile.setDateOfBirth(dob);
        profile.setPosition(PlayerPosition.MIDFIELDER);
        profile.setAgeTier(age >= 18 ? AgeTier.ADULT : AgeTier.AGE_13_17);
        return profile;
    }

    private Video seedVideoInScanning(String ownerId) {
        Video v = new Video();
        v.setOwnerId(ownerId);
        v.setProvider("bunny");
        v.setProviderAssetId("asset-gate-" + System.nanoTime());
        v.setTitle("gate-test.mp4");
        v.setOperationalState(OperationalState.PROCESSING);
        v.setAccessState(AccessState.ACTIVE);
        v.setVisibility(Visibility.PRIVATE);
        v.setVideoType(VideoType.HOMEWORK);
        return videoRepository.save(v);
    }

    private void insertUser(long userId, String role) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" " +
            "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, " +
            "status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, " +
            "activated, locked, login, login_id_type, password_hash, otp_enabled, " +
            "skillars_role, verification_status) " +
            "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, " +
            "'ACTIVE', '1980-01-01', ?, 'Gate', 'OTHER', 'en', 'Parent', 'DE', ?, " +
            "true, false, ?, 'EMAIL', 'testhash', false, " +
            "?, 'BASIC_VERIFIED') ON CONFLICT (id) DO NOTHING",
            userId, now, now,
            "gate-user-" + userId + "@test.com",
            "69" + (userId % 100_000_000L),
            "gate-user-" + userId + "@test.com",
            role
        );
    }
}
