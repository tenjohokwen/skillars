package com.softropic.skillars.platform.session.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.contract.CoachSubscriptionTier;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.security.contract.exception.FeatureGatedException;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.session.contract.DrillUploadInitiateRequest;
import com.softropic.skillars.platform.session.contract.DrillUploadInitiateResponse;
import com.softropic.skillars.platform.session.contract.VideoPhysicalDeletionEvent;
import com.softropic.skillars.platform.session.contract.exception.DrillConstraintViolationException;
import com.softropic.skillars.platform.session.repo.Drill;
import com.softropic.skillars.platform.session.repo.DrillRepository;
import com.softropic.skillars.platform.session.repo.DrillVideoRef;
import com.softropic.skillars.platform.session.repo.DrillVideoRefRepository;
import com.softropic.skillars.platform.video.contract.InitializeUploadResponse;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import com.softropic.skillars.platform.video.service.VideoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DrillUploadServiceTest {

    @Mock private DrillRepository drillRepository;
    @Mock private DrillVideoRefRepository drillVideoRefRepository;
    @Mock private VideoService videoService;
    @Mock private VideoRepository videoRepository;
    @Mock private ConfigService configService;
    @Mock private CoachProfileService coachProfileService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private DrillUploadService service;

    private static final Long COACH_USER_ID = 9500000010L;
    private static final UUID COACH_ID = UUID.randomUUID();
    private static final UUID DRILL_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DrillUploadService(
            drillRepository, drillVideoRefRepository, videoService,
            videoRepository, configService, coachProfileService, eventPublisher
        );
        when(coachProfileService.getCoachIdByUserId(COACH_USER_ID)).thenReturn(COACH_ID);
    }

    // ── initiateUpload happy path ─────────────────────────────────────────────

    @Test
    void initiateUpload_instructorTier_createsRefAndReturnsUrl() {
        Drill drill = coachDrill(DRILL_ID, COACH_ID);
        when(drillRepository.findById(DRILL_ID)).thenReturn(Optional.of(drill));
        when(coachProfileService.getCoachSubscriptionTier(COACH_ID)).thenReturn(CoachSubscriptionTier.INSTRUCTOR);
        when(configService.getBoolean("feature.drillVideoUpload.enabled.INSTRUCTOR")).thenReturn(true);
        when(configService.find("video.drillDemo.maxSizeBytes")).thenReturn(Optional.of("524288000"));
        when(configService.find("video.drillDemo.maxDurationSeconds")).thenReturn(Optional.of("120"));

        UUID videoId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(videoService.initializeUpload(any())).thenReturn(
            new InitializeUploadResponse(videoId, sessionId, "provider-id", "https://tus.example.com/upload", Instant.now().plusSeconds(3600))
        );
        when(drillVideoRefRepository.findByDrillId(DRILL_ID)).thenReturn(Optional.empty());

        DrillUploadInitiateRequest req = new DrillUploadInitiateRequest("demo.mp4", 1024L * 1024, "video/mp4", 30);
        DrillUploadInitiateResponse resp = service.initiateUpload(DRILL_ID, COACH_USER_ID, req);

        assertThat(resp.videoId()).isEqualTo(videoId);
        assertThat(resp.uploadSessionId()).isEqualTo(sessionId);
        assertThat(resp.signedUploadUrl()).isEqualTo("https://tus.example.com/upload");

        verify(drillVideoRefRepository).upsertVideoId(DRILL_ID, videoId);
    }

    @Test
    void initiateUpload_existingRef_updatesVideoId() {
        Drill drill = coachDrill(DRILL_ID, COACH_ID);
        when(drillRepository.findById(DRILL_ID)).thenReturn(Optional.of(drill));
        when(coachProfileService.getCoachSubscriptionTier(COACH_ID)).thenReturn(CoachSubscriptionTier.INSTRUCTOR);
        when(configService.getBoolean("feature.drillVideoUpload.enabled.INSTRUCTOR")).thenReturn(true);
        when(configService.find("video.drillDemo.maxSizeBytes")).thenReturn(Optional.of("524288000"));
        when(configService.find("video.drillDemo.maxDurationSeconds")).thenReturn(Optional.of("120"));

        UUID videoId = UUID.randomUUID();
        when(videoService.initializeUpload(any())).thenReturn(
            new InitializeUploadResponse(videoId, UUID.randomUUID(), "p", "https://tus.example.com/upload", Instant.now())
        );
        DrillVideoRef existing = buildRef(DRILL_ID, UUID.randomUUID());
        when(drillVideoRefRepository.findByDrillId(DRILL_ID)).thenReturn(Optional.of(existing));

        service.initiateUpload(DRILL_ID, COACH_USER_ID, new DrillUploadInitiateRequest("v.mp4", 1024L, "video/mp4", 10));

        verify(drillVideoRefRepository).setVideoId(DRILL_ID, videoId);
        verify(drillVideoRefRepository, never()).save(any(DrillVideoRef.class));
    }

    @Test
    void initiateUpload_existingRefWithReadyVideo_throwsOperationNotAllowed() {
        Drill drill = coachDrill(DRILL_ID, COACH_ID);
        when(drillRepository.findById(DRILL_ID)).thenReturn(Optional.of(drill));
        when(coachProfileService.getCoachSubscriptionTier(COACH_ID)).thenReturn(CoachSubscriptionTier.INSTRUCTOR);
        when(configService.getBoolean("feature.drillVideoUpload.enabled.INSTRUCTOR")).thenReturn(true);
        when(configService.find("video.drillDemo.maxSizeBytes")).thenReturn(Optional.of("524288000"));
        when(configService.find("video.drillDemo.maxDurationSeconds")).thenReturn(Optional.of("120"));

        UUID existingVideoId = UUID.randomUUID();
        DrillVideoRef existing = buildRef(DRILL_ID, existingVideoId);
        when(drillVideoRefRepository.findByDrillId(DRILL_ID)).thenReturn(Optional.of(existing));

        Video readyVideo = new Video();
        readyVideo.setOperationalState(OperationalState.READY);
        when(videoRepository.findById(existingVideoId)).thenReturn(Optional.of(readyVideo));

        assertThatThrownBy(() -> service.initiateUpload(DRILL_ID, COACH_USER_ID,
                new DrillUploadInitiateRequest("v.mp4", 1024L, "video/mp4", 10)))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasMessageContaining("Remove it before uploading");

        verify(videoService, never()).initializeUpload(any());
        verify(drillVideoRefRepository, never()).setVideoId(any(), any());
    }

    // ── initiateUpload gate checks ────────────────────────────────────────────

    @Test
    void initiateUpload_scoutTier_throwsFeatureGatedException() {
        Drill drill = coachDrill(DRILL_ID, COACH_ID);
        when(drillRepository.findById(DRILL_ID)).thenReturn(Optional.of(drill));
        when(coachProfileService.getCoachSubscriptionTier(COACH_ID)).thenReturn(CoachSubscriptionTier.SCOUT);
        when(configService.getBoolean("feature.drillVideoUpload.enabled.SCOUT")).thenReturn(false);
        when(configService.find(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.initiateUpload(DRILL_ID, COACH_USER_ID,
                new DrillUploadInitiateRequest("v.mp4", 1024L, "video/mp4", 10)))
            .isInstanceOf(FeatureGatedException.class);
    }

    @Test
    void initiateUpload_otherCoachDrill_throwsOperationNotAllowed() {
        Drill drill = coachDrill(DRILL_ID, UUID.randomUUID()); // different coach
        when(drillRepository.findById(DRILL_ID)).thenReturn(Optional.of(drill));

        assertThatThrownBy(() -> service.initiateUpload(DRILL_ID, COACH_USER_ID,
                new DrillUploadInitiateRequest("v.mp4", 1024L, "video/mp4", 10)))
            .isInstanceOf(OperationNotAllowedException.class);
    }

    @Test
    void initiateUpload_platformDrill_throwsOperationNotAllowed() {
        Drill drill = platformDrill(DRILL_ID);
        when(drillRepository.findById(DRILL_ID)).thenReturn(Optional.of(drill));

        assertThatThrownBy(() -> service.initiateUpload(DRILL_ID, COACH_USER_ID,
                new DrillUploadInitiateRequest("v.mp4", 1024L, "video/mp4", 10)))
            .isInstanceOf(OperationNotAllowedException.class);
    }

    // ── initiateUpload constraint checks ─────────────────────────────────────

    @Test
    void initiateUpload_sizeExceedsLimit_throwsDrillConstraintViolationException() {
        Drill drill = coachDrill(DRILL_ID, COACH_ID);
        when(drillRepository.findById(DRILL_ID)).thenReturn(Optional.of(drill));
        when(coachProfileService.getCoachSubscriptionTier(COACH_ID)).thenReturn(CoachSubscriptionTier.INSTRUCTOR);
        when(configService.getBoolean("feature.drillVideoUpload.enabled.INSTRUCTOR")).thenReturn(true);
        when(configService.find("video.drillDemo.maxSizeBytes")).thenReturn(Optional.of("524288000"));
        when(configService.find("video.drillDemo.maxDurationSeconds")).thenReturn(Optional.of("120"));

        assertThatThrownBy(() -> service.initiateUpload(DRILL_ID, COACH_USER_ID,
                new DrillUploadInitiateRequest("v.mp4", 600_000_000L, "video/mp4", 10)))
            .isInstanceOf(DrillConstraintViolationException.class)
            .hasMessageContaining("500 MB");
    }

    @Test
    void initiateUpload_durationExceedsLimit_throwsDrillConstraintViolationException() {
        Drill drill = coachDrill(DRILL_ID, COACH_ID);
        when(drillRepository.findById(DRILL_ID)).thenReturn(Optional.of(drill));
        when(coachProfileService.getCoachSubscriptionTier(COACH_ID)).thenReturn(CoachSubscriptionTier.INSTRUCTOR);
        when(configService.getBoolean("feature.drillVideoUpload.enabled.INSTRUCTOR")).thenReturn(true);
        when(configService.find("video.drillDemo.maxSizeBytes")).thenReturn(Optional.of("524288000"));
        when(configService.find("video.drillDemo.maxDurationSeconds")).thenReturn(Optional.of("120"));

        assertThatThrownBy(() -> service.initiateUpload(DRILL_ID, COACH_USER_ID,
                new DrillUploadInitiateRequest("v.mp4", 1024L, "video/mp4", 150)))
            .isInstanceOf(DrillConstraintViolationException.class)
            .hasMessageContaining("120 second");
    }

    @Test
    void initiateUpload_durationZero_skipsDurationCheck() {
        Drill drill = coachDrill(DRILL_ID, COACH_ID);
        when(drillRepository.findById(DRILL_ID)).thenReturn(Optional.of(drill));
        when(coachProfileService.getCoachSubscriptionTier(COACH_ID)).thenReturn(CoachSubscriptionTier.INSTRUCTOR);
        when(configService.getBoolean("feature.drillVideoUpload.enabled.INSTRUCTOR")).thenReturn(true);
        when(configService.find("video.drillDemo.maxSizeBytes")).thenReturn(Optional.of("524288000"));
        when(configService.find("video.drillDemo.maxDurationSeconds")).thenReturn(Optional.of("120"));
        when(videoService.initializeUpload(any())).thenReturn(
            new InitializeUploadResponse(UUID.randomUUID(), UUID.randomUUID(), "p", "https://tus.example.com/upload", Instant.now())
        );
        when(drillVideoRefRepository.findByDrillId(DRILL_ID)).thenReturn(Optional.empty());

        // durationSeconds=0 should NOT throw even when maxDuration=120
        service.initiateUpload(DRILL_ID, COACH_USER_ID, new DrillUploadInitiateRequest("v.mp4", 1024L, "video/mp4", 0));

        verify(videoService).initializeUpload(any());
    }

    @Test
    void initiateUpload_newRef_usesAtomicUpsert() {
        Drill drill = coachDrill(DRILL_ID, COACH_ID);
        when(drillRepository.findById(DRILL_ID)).thenReturn(Optional.of(drill));
        when(coachProfileService.getCoachSubscriptionTier(COACH_ID)).thenReturn(CoachSubscriptionTier.INSTRUCTOR);
        when(configService.getBoolean("feature.drillVideoUpload.enabled.INSTRUCTOR")).thenReturn(true);
        when(configService.find("video.drillDemo.maxSizeBytes")).thenReturn(Optional.of("524288000"));
        when(configService.find("video.drillDemo.maxDurationSeconds")).thenReturn(Optional.of("120"));

        UUID videoId = UUID.randomUUID();
        when(videoService.initializeUpload(any())).thenReturn(
            new InitializeUploadResponse(videoId, UUID.randomUUID(), "p", "https://tus.example.com/upload", Instant.now())
        );
        when(drillVideoRefRepository.findByDrillId(DRILL_ID)).thenReturn(Optional.empty());

        service.initiateUpload(DRILL_ID, COACH_USER_ID, new DrillUploadInitiateRequest("v.mp4", 1024L, "video/mp4", 10));

        verify(drillVideoRefRepository).upsertVideoId(DRILL_ID, videoId);
        verify(drillVideoRefRepository, never()).save(any(DrillVideoRef.class));
    }

    // ── deleteVideo ───────────────────────────────────────────────────────────

    @Test
    void deleteVideo_noOtherRefExists_publishesVideoPhysicalDeletionEvent() {
        Drill drill = coachDrill(DRILL_ID, COACH_ID);
        UUID videoId = UUID.randomUUID();
        when(drillRepository.findById(DRILL_ID)).thenReturn(Optional.of(drill));
        when(drillVideoRefRepository.findByDrillId(DRILL_ID)).thenReturn(Optional.of(buildRef(DRILL_ID, videoId)));
        when(drillVideoRefRepository.existsByVideoId(videoId)).thenReturn(false);

        service.deleteVideo(DRILL_ID, COACH_USER_ID);

        verify(drillVideoRefRepository).clearVideoId(DRILL_ID);
        ArgumentCaptor<VideoPhysicalDeletionEvent> eventCaptor = ArgumentCaptor.forClass(VideoPhysicalDeletionEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().videoId()).isEqualTo(videoId);
        assertThat(eventCaptor.getValue().drillId()).isEqualTo(DRILL_ID);
    }

    @Test
    void deleteVideo_otherRefExists_doesNotPublishEvent_clearsVideoId() {
        Drill drill = coachDrill(DRILL_ID, COACH_ID);
        UUID videoId = UUID.randomUUID();
        when(drillRepository.findById(DRILL_ID)).thenReturn(Optional.of(drill));
        when(drillVideoRefRepository.findByDrillId(DRILL_ID)).thenReturn(Optional.of(buildRef(DRILL_ID, videoId)));
        when(drillVideoRefRepository.existsByVideoId(videoId)).thenReturn(true);

        service.deleteVideo(DRILL_ID, COACH_USER_ID);

        verify(drillVideoRefRepository).clearVideoId(DRILL_ID);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void deleteVideo_noVideoId_isNoOp() {
        Drill drill = coachDrill(DRILL_ID, COACH_ID);
        when(drillRepository.findById(DRILL_ID)).thenReturn(Optional.of(drill));
        when(drillVideoRefRepository.findByDrillId(DRILL_ID)).thenReturn(Optional.of(buildRef(DRILL_ID, null)));

        service.deleteVideo(DRILL_ID, COACH_USER_ID);

        verify(drillVideoRefRepository, never()).clearVideoId(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ── isVideoUploadEligible ─────────────────────────────────────────────────

    @Test
    void isVideoUploadEligible_instructorTier_returnsTrue() {
        when(coachProfileService.getCoachSubscriptionTier(COACH_ID)).thenReturn(CoachSubscriptionTier.INSTRUCTOR);
        when(configService.getBoolean("feature.drillVideoUpload.enabled.INSTRUCTOR")).thenReturn(true);

        assertThat(service.isVideoUploadEligible(COACH_USER_ID)).isTrue();
    }

    @Test
    void isVideoUploadEligible_scoutTier_returnsFalse() {
        when(coachProfileService.getCoachSubscriptionTier(COACH_ID)).thenReturn(CoachSubscriptionTier.SCOUT);
        when(configService.getBoolean("feature.drillVideoUpload.enabled.SCOUT")).thenReturn(false);

        assertThat(service.isVideoUploadEligible(COACH_USER_ID)).isFalse();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Drill coachDrill(UUID id, UUID ownerCoachId) {
        Drill d = new Drill();
        d.setId(id);
        d.setName("Test Drill");
        d.setLibraryType("COACH");
        d.setOwnerCoachId(ownerCoachId);
        d.setStatus("ACTIVE");
        return d;
    }

    private Drill platformDrill(UUID id) {
        Drill d = new Drill();
        d.setId(id);
        d.setName("Platform Drill");
        d.setLibraryType("PLATFORM");
        d.setStatus("ACTIVE");
        return d;
    }

    private DrillVideoRef buildRef(UUID drillId, UUID videoId) {
        DrillVideoRef ref = new DrillVideoRef();
        ref.setDrillId(drillId);
        ref.setVideoId(videoId);
        ref.setRefCount(1);
        return ref;
    }
}
