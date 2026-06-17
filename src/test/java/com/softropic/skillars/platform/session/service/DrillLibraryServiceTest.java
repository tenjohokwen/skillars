package com.softropic.skillars.platform.session.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.video.PlaybackTokenClaims;
import com.softropic.skillars.infrastructure.video.SignedPlaybackUrl;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.contract.CoachSubscriptionTier;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.security.contract.exception.FeatureGatedException;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.session.contract.DrillMetadata;
import com.softropic.skillars.platform.session.contract.DrillResponse;
import com.softropic.skillars.platform.session.repo.Drill;
import com.softropic.skillars.platform.session.repo.DrillRepository;
import com.softropic.skillars.platform.session.repo.DrillTagRepository;
import com.softropic.skillars.platform.session.repo.DrillVideoRef;
import com.softropic.skillars.platform.session.repo.DrillVideoRefRepository;
import com.softropic.skillars.platform.video.contract.AccessState;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DrillLibraryServiceTest {

    @Mock private DrillRepository drillRepository;
    @Mock private DrillVideoRefRepository drillVideoRefRepository;
    @Mock private DrillTagRepository drillTagRepository;
    @Mock private ConfigService configService;
    @Mock private CoachProfileService coachProfileService;
    @Mock private VideoRepository videoRepository;
    @Mock private VideoProviderAdapter videoProviderAdapter;

    private DrillLibraryService service;

    private static final Long COACH_USER_ID = 9500000010L;
    private static final UUID COACH_PROFILE_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DrillLibraryService(drillRepository, drillVideoRefRepository, drillTagRepository, configService, coachProfileService, videoRepository, videoProviderAdapter);
        when(coachProfileService.getCoachIdByUserId(COACH_USER_ID)).thenReturn(COACH_PROFILE_ID);
    }

    // ── cloneDrill ──────────────────────────────────────────────────────────────

    @Test
    void cloneDrill_platformDrillWithVideoRef_savesCloneAndIncrementsRefCount() {
        UUID sourceDrillId = UUID.randomUUID();
        Drill source = buildDrill(sourceDrillId, "PLATFORM", "ACTIVE", null);
        DrillVideoRef sourceRef = buildVideoRef(sourceDrillId, UUID.randomUUID());

        UUID savedCloneId = UUID.randomUUID();
        when(drillRepository.findById(sourceDrillId)).thenReturn(Optional.of(source));
        when(drillVideoRefRepository.findByDrillId(any(UUID.class)))
            .thenReturn(Optional.of(sourceRef))
            .thenReturn(Optional.of(buildVideoRef(savedCloneId, sourceRef.getVideoId())));
        when(drillRepository.save(any(Drill.class))).thenAnswer(inv -> {
            Drill d = inv.getArgument(0);
            d.setId(savedCloneId);
            d.setCreatedAt(Instant.now());
            return d;
        });

        DrillResponse result = service.cloneDrill(sourceDrillId, COACH_USER_ID);

        assertThat(result.libraryType()).isEqualTo("COACH");
        assertThat(result.ownerCoachId()).isEqualTo(COACH_PROFILE_ID);
        assertThat(result.hasVideo()).isTrue();

        ArgumentCaptor<DrillVideoRef> refCaptor = ArgumentCaptor.forClass(DrillVideoRef.class);
        verify(drillVideoRefRepository).save(refCaptor.capture());
        assertThat(refCaptor.getValue().getVideoId()).isEqualTo(sourceRef.getVideoId());
        verify(drillVideoRefRepository).incrementRefCount(sourceDrillId);
    }

    @Test
    void cloneDrill_platformDrillWithoutVideoRef_savesCloneWithoutIncrementingRefCount() {
        UUID sourceDrillId = UUID.randomUUID();
        Drill source = buildDrill(sourceDrillId, "PLATFORM", "ACTIVE", null);

        when(drillRepository.findById(sourceDrillId)).thenReturn(Optional.of(source));
        when(drillRepository.save(any(Drill.class))).thenAnswer(inv -> {
            Drill d = inv.getArgument(0);
            d.setId(UUID.randomUUID());
            d.setCreatedAt(Instant.now());
            return d;
        });
        when(drillVideoRefRepository.findByDrillId(any(UUID.class))).thenReturn(Optional.empty());

        service.cloneDrill(sourceDrillId, COACH_USER_ID);

        verify(drillVideoRefRepository, never()).save(any(DrillVideoRef.class));
        verify(drillVideoRefRepository, never()).incrementRefCount(any(UUID.class));
    }

    @Test
    void cloneDrill_coachTypeDrill_throws403() {
        UUID sourceDrillId = UUID.randomUUID();
        Drill source = buildDrill(sourceDrillId, "COACH", "ACTIVE", UUID.randomUUID());

        when(drillRepository.findById(sourceDrillId)).thenReturn(Optional.of(source));

        assertThatThrownBy(() -> service.cloneDrill(sourceDrillId, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class);
    }

    @Test
    void cloneDrill_archivedDrill_throws404() {
        UUID sourceDrillId = UUID.randomUUID();
        Drill source = buildDrill(sourceDrillId, "PLATFORM", "ARCHIVED", null);

        when(drillRepository.findById(sourceDrillId)).thenReturn(Optional.of(source));

        assertThatThrownBy(() -> service.cloneDrill(sourceDrillId, COACH_USER_ID))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void cloneDrill_drillNotFound_throws404() {
        UUID sourceDrillId = UUID.randomUUID();
        when(drillRepository.findById(sourceDrillId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cloneDrill(sourceDrillId, COACH_USER_ID))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── checkSessionBuilderGate ─────────────────────────────────────────────────

    @Test
    void checkSessionBuilderGate_scoutTier_throwsFeatureGatedException() {
        when(coachProfileService.getCoachSubscriptionTier(COACH_PROFILE_ID)).thenReturn(CoachSubscriptionTier.SCOUT);
        when(configService.getBoolean("feature.sessionBuilder.enabled.SCOUT")).thenReturn(false);

        assertThatThrownBy(() -> service.checkSessionBuilderGate(COACH_USER_ID))
            .isInstanceOf(FeatureGatedException.class);
    }

    @Test
    void checkSessionBuilderGate_instructorTier_doesNotThrow() {
        when(coachProfileService.getCoachSubscriptionTier(COACH_PROFILE_ID)).thenReturn(CoachSubscriptionTier.INSTRUCTOR);
        when(configService.getBoolean("feature.sessionBuilder.enabled.INSTRUCTOR")).thenReturn(true);

        service.checkSessionBuilderGate(COACH_USER_ID);
    }

    // ── batchVideoLookup (via listDrills PLATFORM path) ──────────────────────

    @Test
    void batchVideoLookup_videoReady_returnsHasVideoTrueWithSignedUrl() {
        UUID drillId = UUID.randomUUID();
        UUID videoId = UUID.randomUUID();
        Drill drill = buildDrill(drillId, "PLATFORM", "ACTIVE", null);

        when(drillRepository.findByLibraryTypeAndStatus("PLATFORM", "ACTIVE")).thenReturn(List.of(drill));
        when(drillVideoRefRepository.findByDrillIdIn(any())).thenReturn(List.of(buildVideoRef(drillId, videoId)));

        Video readyVideo = buildVideo(videoId, OperationalState.READY, AccessState.ACTIVE, "asset-123");
        when(videoRepository.findReadyAndActiveByIds(any())).thenReturn(List.of(readyVideo));
        when(videoProviderAdapter.generatePlaybackUrl(any(), any(PlaybackTokenClaims.class)))
            .thenReturn(new SignedPlaybackUrl("https://cdn.example.com/play/asset-123", Instant.now().plusSeconds(7200)));

        List<DrillResponse> results = service.listDrills("PLATFORM", null, null, null, null, null, COACH_USER_ID);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).hasVideo()).isTrue();
        assertThat(results.get(0).videoUrl()).isEqualTo("https://cdn.example.com/play/asset-123");
    }

    @Test
    void batchVideoLookup_videoInUploadingState_returnsHasVideoTrueAndNullUrl() {
        UUID drillId = UUID.randomUUID();
        UUID videoId = UUID.randomUUID();
        Drill drill = buildDrill(drillId, "PLATFORM", "ACTIVE", null);

        when(drillRepository.findByLibraryTypeAndStatus("PLATFORM", "ACTIVE")).thenReturn(List.of(drill));
        when(drillVideoRefRepository.findByDrillIdIn(any())).thenReturn(List.of(buildVideoRef(drillId, videoId)));
        // UPLOADING video is NOT returned by findReadyAndActiveByIds
        when(videoRepository.findReadyAndActiveByIds(any())).thenReturn(List.of());

        List<DrillResponse> results = service.listDrills("PLATFORM", null, null, null, null, null, COACH_USER_ID);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).hasVideo()).isTrue();
        assertThat(results.get(0).videoUrl()).isNull();
    }

    @Test
    void batchVideoLookup_noRef_returnsHasVideoFalse() {
        UUID drillId = UUID.randomUUID();
        Drill drill = buildDrill(drillId, "PLATFORM", "ACTIVE", null);

        when(drillRepository.findByLibraryTypeAndStatus("PLATFORM", "ACTIVE")).thenReturn(List.of(drill));
        when(drillVideoRefRepository.findByDrillIdIn(any())).thenReturn(List.of());

        List<DrillResponse> results = service.listDrills("PLATFORM", null, null, null, null, null, COACH_USER_ID);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).hasVideo()).isFalse();
        assertThat(results.get(0).videoUrl()).isNull();
    }

    @Test
    void batchVideoLookup_refVideoIdNull_returnsHasVideoFalse() {
        UUID drillId = UUID.randomUUID();
        Drill drill = buildDrill(drillId, "PLATFORM", "ACTIVE", null);

        when(drillRepository.findByLibraryTypeAndStatus("PLATFORM", "ACTIVE")).thenReturn(List.of(drill));
        when(drillVideoRefRepository.findByDrillIdIn(any())).thenReturn(List.of(buildVideoRef(drillId, null)));

        List<DrillResponse> results = service.listDrills("PLATFORM", null, null, null, null, null, COACH_USER_ID);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).hasVideo()).isFalse();
        assertThat(results.get(0).videoUrl()).isNull();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private Drill buildDrill(UUID id, String libraryType, String status, UUID ownerCoachId) {
        Drill d = new Drill();
        d.setId(id);
        d.setName("Test Drill");
        d.setDescription("Desc");
        d.setLibraryType(libraryType);
        d.setStatus(status);
        d.setOwnerCoachId(ownerCoachId);
        d.setCreatedAt(Instant.now());
        d.setUpdatedAt(Instant.now());
        d.setMetadata(new DrillMetadata(
            List.of("ball_mastery"), List.of("coordination"),
            Map.of("ball_mastery", 70), 20, 2, 1, 1, 1, false,
            "U12", List.of("ball"), "2–6", List.of("Keep eyes up"), null
        ));
        return d;
    }

    private DrillVideoRef buildVideoRef(UUID drillId, UUID videoId) {
        DrillVideoRef ref = new DrillVideoRef();
        ref.setDrillId(drillId);
        ref.setVideoId(videoId);
        ref.setRefCount(1);
        return ref;
    }

    private Video buildVideo(UUID id, OperationalState opState, AccessState accState, String providerAssetId) {
        Video v = new Video();
        v.setId(id);
        v.setOperationalState(opState);
        v.setAccessState(accState);
        v.setProviderAssetId(providerAssetId);
        v.setOwnerId("coach-" + id);
        v.setProvider("bunny");
        return v;
    }
}
