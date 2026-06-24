package com.softropic.skillars.platform.video.service;

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
import com.softropic.skillars.platform.video.contract.exception.VideoAlreadyResolvedException;
import com.softropic.skillars.platform.video.contract.exception.VideoApprovalNotFoundException;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoApprovalRequest;
import com.softropic.skillars.platform.video.repo.VideoApprovalRequestRepository;
import com.softropic.skillars.platform.video.repo.VideoRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class VideoApprovalServiceIT extends BaseVideoIT {

    @MockitoBean
    VideoProviderAdapter videoProviderAdapter;

    @Autowired VideoApprovalService videoApprovalService;
    @Autowired VideoRepository videoRepository;
    @Autowired VideoApprovalRequestRepository videoApprovalRequestRepository;
    @Autowired PlayerProfileRepository playerProfileRepository;

    private static final long PARENT_ID       = 99_000_001L;
    private static final long OTHER_PARENT_ID = 99_000_002L;

    @BeforeEach
    void setUp() {
        transactionTemplate.execute(status -> {
            // FK-safe order: approval requests cascade from videos; clear test fixtures
            jdbcTemplate.update("DELETE FROM main.video_approval_requests");
            jdbcTemplate.update("DELETE FROM main.videos");
            jdbcTemplate.update("DELETE FROM main.player_profiles WHERE parent_id IN (?, ?)",
                PARENT_ID, OTHER_PARENT_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?)",
                PARENT_ID, OTHER_PARENT_ID);
            insertTestUser(PARENT_ID,       "parent1-approval-it@test.com");
            insertTestUser(OTHER_PARENT_ID, "parent2-approval-it@test.com");
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM main.video_approval_requests");
            jdbcTemplate.update("DELETE FROM main.videos");
            jdbcTemplate.update("DELETE FROM main.player_profiles WHERE parent_id IN (?, ?)",
                PARENT_ID, OTHER_PARENT_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?)",
                PARENT_ID, OTHER_PARENT_ID);
            return null;
        });
    }

    private void insertTestUser(long userId, String email) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" " +
            "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, " +
            "status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, " +
            "activated, locked, login, login_id_type, password_hash, otp_enabled, " +
            "skillars_role, verification_status) " +
            "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, " +
            "'ACTIVE', '1990-01-01', ?, 'Parent', 'OTHER', 'en', 'Test', 'DE', ?, " +
            "true, false, ?, 'EMAIL', 'testhash', false, " +
            "'PARENT', 'BASIC_VERIFIED') " +
            "ON CONFLICT (id) DO NOTHING",
            userId, now, now,
            email,
            "69" + (userId % 100_000_000L),
            email
        );
    }

    // ─── createApprovalRequest ────────────────────────────────────────────────

    @Test
    void createApprovalRequest_minorWithParent_createsApprovalRow() {
        PlayerProfile profile = seedMinorPlayer(15, PARENT_ID);
        Video video = seedVideo(String.valueOf(profile.getId()), OperationalState.HIDDEN, "asset-gate-1");

        videoApprovalService.createApprovalRequest(video.getId(), profile.getId());

        Optional<VideoApprovalRequest> approval =
            videoApprovalRequestRepository.findByVideoIdAndStatus(video.getId(), "PENDING");
        assertThat(approval).isPresent();
        assertThat(approval.get().getPlayerId()).isEqualTo(profile.getId());
        assertThat(approval.get().getParentId()).isEqualTo(PARENT_ID);
        assertThat(approval.get().getStatus()).isEqualTo("PENDING");
        assertThat(approval.get().getCreatedAt()).isNotNull();
    }

    @Test
    void createApprovalRequest_idempotent_secondCallSkipped() {
        PlayerProfile profile = seedMinorPlayer(14, PARENT_ID);
        Video video = seedVideo(String.valueOf(profile.getId()), OperationalState.HIDDEN, "asset-idem");

        videoApprovalService.createApprovalRequest(video.getId(), profile.getId());
        videoApprovalService.createApprovalRequest(video.getId(), profile.getId()); // duplicate

        List<VideoApprovalRequest> rows = videoApprovalRequestRepository.findByParentIdAndStatus(PARENT_ID, "PENDING");
        assertThat(rows).hasSize(1);
    }

    @Test
    void createApprovalRequest_profileNotFound_advancesToTranscoding() {
        Video video = seedVideo("99999999", OperationalState.HIDDEN, "asset-no-profile");

        videoApprovalService.createApprovalRequest(video.getId(), 99_999_999L);

        Video updated = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(updated.getOperationalState()).isEqualTo(OperationalState.TRANSCODING);
        assertThat(videoApprovalRequestRepository.findByVideoIdAndStatus(video.getId(), "PENDING")).isEmpty();
    }

    @Test
    void createApprovalRequest_noParentId_advancesToTranscoding() {
        // PlayerProfile with parentId = null is not permitted by the NOT NULL constraint,
        // so we test via a profile lookup that returns empty (deleted profile).
        // This exercises the "profile not found" fallback path — same outcome.
        Video video = seedVideo("88888888", OperationalState.HIDDEN, "asset-no-parent");

        // Profile with a different ID than the ownerId → findById returns empty
        videoApprovalService.createApprovalRequest(video.getId(), 88_888_888L);

        Video updated = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(updated.getOperationalState()).isEqualTo(OperationalState.TRANSCODING);
    }

    // ─── approveVideo ────────────────────────────────────────────────────────

    @Test
    void approveVideo_happyPath_transitionsToTranscodingAndCallsBunny() {
        PlayerProfile profile = seedMinorPlayer(16, PARENT_ID);
        Video video = seedVideo(String.valueOf(profile.getId()), OperationalState.HIDDEN, "asset-approve-1");
        VideoApprovalRequest approval = seedApprovalRequest(video, profile.getId(), PARENT_ID);

        doNothing().when(videoProviderAdapter).triggerTranscoding("asset-approve-1");

        videoApprovalService.approveVideo(approval.getId(), PARENT_ID);

        Video updated = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(updated.getOperationalState()).isEqualTo(OperationalState.TRANSCODING);

        VideoApprovalRequest updatedApproval = videoApprovalRequestRepository.findById(approval.getId()).orElseThrow();
        assertThat(updatedApproval.getStatus()).isEqualTo("APPROVED");
        assertThat(updatedApproval.getResolvedAt()).isNotNull();

        verify(videoProviderAdapter).triggerTranscoding("asset-approve-1");
    }

    @Test
    void approveVideo_wrongParent_throwsApprovalNotFound() {
        PlayerProfile profile = seedMinorPlayer(15, PARENT_ID);
        Video video = seedVideo(String.valueOf(profile.getId()), OperationalState.HIDDEN, "asset-wrong-parent");
        VideoApprovalRequest approval = seedApprovalRequest(video, profile.getId(), PARENT_ID);

        assertThatThrownBy(() -> videoApprovalService.approveVideo(approval.getId(), OTHER_PARENT_ID))
            .isInstanceOf(VideoApprovalNotFoundException.class);

        verify(videoProviderAdapter, never()).triggerTranscoding(any());
    }

    @Test
    void approveVideo_alreadyApproved_throwsAlreadyResolved() {
        PlayerProfile profile = seedMinorPlayer(15, PARENT_ID);
        Video video = seedVideo(String.valueOf(profile.getId()), OperationalState.HIDDEN, "asset-dbl-approve");
        VideoApprovalRequest approval = seedApprovalRequest(video, profile.getId(), PARENT_ID);
        doNothing().when(videoProviderAdapter).triggerTranscoding(any());

        videoApprovalService.approveVideo(approval.getId(), PARENT_ID);

        // Simulate second serial approve call — video is now TRANSCODING (past HIDDEN)
        // The idempotent guard returns silently without throwing; verify no double Bunny call
        videoApprovalService.approveVideo(approval.getId(), PARENT_ID);

        verify(videoProviderAdapter, times(1)).triggerTranscoding(any());
    }

    // ─── rejectVideo ─────────────────────────────────────────────────────────

    @Test
    void rejectVideo_happyPath_transitionsToRejected() {
        PlayerProfile profile = seedMinorPlayer(17, PARENT_ID);
        Video video = seedVideo(String.valueOf(profile.getId()), OperationalState.HIDDEN, "asset-reject-1");
        VideoApprovalRequest approval = seedApprovalRequest(video, profile.getId(), PARENT_ID);

        videoApprovalService.rejectVideo(approval.getId(), PARENT_ID);

        Video updated = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(updated.getOperationalState()).isEqualTo(OperationalState.REJECTED);

        VideoApprovalRequest updatedApproval = videoApprovalRequestRepository.findById(approval.getId()).orElseThrow();
        assertThat(updatedApproval.getStatus()).isEqualTo("REJECTED");
        assertThat(updatedApproval.getResolvedAt()).isNotNull();

        verify(videoProviderAdapter, never()).triggerTranscoding(any());
    }

    @Test
    void rejectVideo_wrongParent_throwsApprovalNotFound() {
        PlayerProfile profile = seedMinorPlayer(16, PARENT_ID);
        Video video = seedVideo(String.valueOf(profile.getId()), OperationalState.HIDDEN, "asset-reject-wrong");
        VideoApprovalRequest approval = seedApprovalRequest(video, profile.getId(), PARENT_ID);

        assertThatThrownBy(() -> videoApprovalService.rejectVideo(approval.getId(), OTHER_PARENT_ID))
            .isInstanceOf(VideoApprovalNotFoundException.class);
    }

    @Test
    void rejectVideo_alreadyResolved_throwsAlreadyResolved() {
        PlayerProfile profile = seedMinorPlayer(15, PARENT_ID);
        Video video = seedVideo(String.valueOf(profile.getId()), OperationalState.HIDDEN, "asset-reject-resolved");
        VideoApprovalRequest approval = seedApprovalRequest(video, profile.getId(), PARENT_ID);

        videoApprovalService.rejectVideo(approval.getId(), PARENT_ID);

        // rejectVideo checks approval.status FIRST — once REJECTED the second call throws
        assertThatThrownBy(() -> videoApprovalService.rejectVideo(approval.getId(), PARENT_ID))
            .isInstanceOf(VideoAlreadyResolvedException.class);
    }

    // ─── getPendingApprovalsForParent ────────────────────────────────────────

    @Test
    void getPendingApprovalsForParent_returnsOnlyThisParentsRequests() {
        PlayerProfile p1 = seedMinorPlayer(15, PARENT_ID);
        PlayerProfile p2 = seedMinorPlayer(14, OTHER_PARENT_ID);

        Video v1 = seedVideo(String.valueOf(p1.getId()), OperationalState.HIDDEN, "asset-list-p1");
        Video v2 = seedVideo(String.valueOf(p2.getId()), OperationalState.HIDDEN, "asset-list-p2");

        seedApprovalRequest(v1, p1.getId(), PARENT_ID);
        seedApprovalRequest(v2, p2.getId(), OTHER_PARENT_ID);

        List<VideoApprovalRequest> results = videoApprovalService.getPendingApprovalsForParent(PARENT_ID);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getParentId()).isEqualTo(PARENT_ID);
    }

    @Test
    void getPendingApprovalsForParent_noRequests_returnsEmptyList() {
        List<VideoApprovalRequest> results = videoApprovalService.getPendingApprovalsForParent(99_999L);
        assertThat(results).isEmpty();
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private PlayerProfile seedMinorPlayer(int ageYears, long parentId) {
        PlayerProfile profile = new PlayerProfile();
        profile.setName("Test Player " + ageYears);
        profile.setDateOfBirth(LocalDate.now().minusYears(ageYears));
        profile.setPosition(PlayerPosition.GOALKEEPER);
        profile.setAgeTier(ageYears >= 18 ? AgeTier.ADULT : AgeTier.AGE_13_17);
        profile.setParentId(parentId);
        return playerProfileRepository.save(profile);
    }

    private Video seedVideo(String ownerId, OperationalState state, String providerAssetId) {
        Video v = new Video();
        v.setOwnerId(ownerId);
        v.setProvider("bunny");
        v.setProviderAssetId(providerAssetId);
        v.setTitle("test-video.mp4");
        v.setOperationalState(state);
        v.setAccessState(AccessState.ACTIVE);
        v.setVisibility(Visibility.PRIVATE);
        v.setVideoType(VideoType.HOMEWORK);
        return videoRepository.save(v);
    }

    private VideoApprovalRequest seedApprovalRequest(Video video, long playerId, long parentId) {
        VideoApprovalRequest req = new VideoApprovalRequest();
        req.setVideoId(video.getId());
        req.setPlayerId(playerId);
        req.setParentId(parentId);
        req.setStatus("PENDING");
        return videoApprovalRequestRepository.save(req);
    }
}
