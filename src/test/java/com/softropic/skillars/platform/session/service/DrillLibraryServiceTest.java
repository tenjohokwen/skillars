package com.softropic.skillars.platform.session.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.video.PlaybackTokenClaims;
import com.softropic.skillars.infrastructure.video.SignedPlaybackUrl;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.infrastructure.sanitizer.ContactDetailSanitizer;
import jakarta.persistence.EntityManager;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.contract.CoachSubscriptionTier;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.security.contract.exception.FeatureGatedException;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.session.contract.DrillMetadata;
import com.softropic.skillars.platform.session.contract.DrillResponse;
import com.softropic.skillars.platform.session.contract.DrillUpsertRequest;
import com.softropic.skillars.platform.session.contract.InvalidParamException;
import com.softropic.skillars.platform.session.repo.Drill;
import com.softropic.skillars.platform.session.repo.DrillRepository;
import com.softropic.skillars.platform.session.repo.DrillTagRepository;
import com.softropic.skillars.platform.session.repo.DrillVideoRef;
import com.softropic.skillars.platform.session.repo.DrillVideoRefRepository;
import com.softropic.skillars.platform.video.contract.AccessState;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
    @Mock private PessimisticLockRetryer lockRetryer;
    @Mock private EntityManager entityManager;

    private SimpleMeterRegistry meterRegistry;
    private DrillLibraryService service;

    private static final Long COACH_USER_ID = 9500000010L;
    private static final UUID COACH_PROFILE_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new DrillLibraryService(drillRepository, drillVideoRefRepository, drillTagRepository, configService, coachProfileService, videoRepository, videoProviderAdapter, meterRegistry,
            new ContactDetailSanitizer(), lockRetryer, entityManager);
        // lenient: the pure resolveWeighting tests never resolve a coach.
        org.mockito.Mockito.lenient().when(coachProfileService.getCoachIdByUserId(COACH_USER_ID)).thenReturn(COACH_PROFILE_ID);
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

        assertThat(result.libraryType()).isEqualTo("PRIVATE");
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
        Drill source = buildDrill(sourceDrillId, "PRIVATE", "ACTIVE", UUID.randomUUID());

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

    @Test
    void checkSessionBuilderGate_allTiersDisabled_messageStatesUnavailableAtAnyTier() {
        // skillars-deferred-22 AC3: a fully-misconfigured gate (every tier disabled) must not
        // surface as the misleading "requires tier: NONE".
        when(coachProfileService.getCoachSubscriptionTier(COACH_PROFILE_ID)).thenReturn(CoachSubscriptionTier.SCOUT);
        when(configService.getBoolean("feature.sessionBuilder.enabled.SCOUT")).thenReturn(false);
        // Every tier explicitly disabled — not a wildcard any() stub — so the test proves
        // resolveMinEnabledTier's loop actually finds no enabled tier, rather than passing
        // vacuously off a blanket default.
        when(configService.find("feature.sessionBuilder.enabled.SCOUT")).thenReturn(Optional.empty());
        when(configService.find("feature.sessionBuilder.enabled.INSTRUCTOR")).thenReturn(Optional.empty());
        when(configService.find("feature.sessionBuilder.enabled.ACADEMY")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.checkSessionBuilderGate(COACH_USER_ID))
            .isInstanceOf(FeatureGatedException.class)
            .hasMessage("Feature 'session_builder' is not currently available at any subscription tier")
            .satisfies(e -> assertThat(((FeatureGatedException) e).getRequiredTier()).isNull());

        assertThat(meterRegistry.get("feature.gate.fully_disabled")
            .tag("feature", "sessionBuilder").counter().count()).isEqualTo(1.0);
    }

    @Test
    void checkSessionBuilderGate_instructorTierEnabled_doesNotIncrementFullyDisabledCounter() {
        when(coachProfileService.getCoachSubscriptionTier(COACH_PROFILE_ID)).thenReturn(CoachSubscriptionTier.INSTRUCTOR);
        when(configService.getBoolean("feature.sessionBuilder.enabled.INSTRUCTOR")).thenReturn(true);

        service.checkSessionBuilderGate(COACH_USER_ID);

        assertThat(meterRegistry.find("feature.gate.fully_disabled").counter()).isNull();
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

    // ── feat/coach-workflow-gaps D1: create / update / delete ──────────────────

    private void stubLockRetryer() {
        org.mockito.Mockito.lenient().when(lockRetryer.withBoundedRetry(org.mockito.ArgumentMatchers.anyString(), any()))
            .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(1)).get());
    }

    private DrillUpsertRequest upsert(String name, Map<String, Integer> weighting) {
        return new DrillUpsertRequest(
            name, "Call me on +44 7911 123456", List.of("passing", " passing "), List.of("first_touch"),
            weighting, 30, 3, 2, 2, 3, true, "U12", List.of("ball", "cones"), "2-4",
            List.of("Mail coach@example.com", "Open body shape"));
    }

    @Test
    void createDrill_savesPrivateActiveOwnedDrill_sanitizedAndWeightingDerived() {
        when(drillRepository.saveAndFlush(any(Drill.class))).thenAnswer(inv -> inv.getArgument(0));

        DrillResponse response = service.createDrill(upsert("  Rondo  ", null), COACH_USER_ID);

        ArgumentCaptor<Drill> captor = ArgumentCaptor.forClass(Drill.class);
        verify(drillRepository).saveAndFlush(captor.capture());
        Drill saved = captor.getValue();
        assertThat(saved.getLibraryType()).isEqualTo("PRIVATE");
        assertThat(saved.getOwnerCoachId()).isEqualTo(COACH_PROFILE_ID);
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");
        assertThat(saved.getName()).isEqualTo("Rondo");
        assertThat(saved.getDescription()).doesNotContain("7911").contains("[contact details removed]");
        assertThat(saved.getMetadata().primarySkills()).containsExactly("passing");
        assertThat(saved.getMetadata().skillWeighting()).isEqualTo(Map.of("passing", 70, "first_touch", 30));
        assertThat(saved.getMetadata().coachingPoints().get(0)).doesNotContain("coach@example.com");
        assertThat(saved.getMetadata().setupDiagram()).isNull();
        assertThat(saved.getSourceDrillId()).isNull();
        assertThat(response.libraryType()).isEqualTo("PRIVATE");
    }

    @Test
    void createDrill_explicitWeightingNotSummingTo100_throwsInvalidParam() {
        assertThatThrownBy(() -> service.createDrill(
            upsert("Rondo", Map.of("passing", 50, "first_touch", 10)), COACH_USER_ID))
            .isInstanceOf(InvalidParamException.class);
        verify(drillRepository, never()).saveAndFlush(any());
    }

    @Test
    void resolveWeighting_threeWaySplit_remainderGoesToFirstSkill() {
        Map<String, Integer> w = DrillLibraryService.resolveWeighting(
            null, List.of("a", "b", "c"), List.of(), null);
        assertThat(w).containsEntry("a", 34).containsEntry("b", 33).containsEntry("c", 33);
        assertThat(w.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(100);
    }

    @Test
    void updateDrill_ownedActiveDrill_replacesFieldsKeepingSetupDiagramAndMatchingWeighting() {
        stubLockRetryer();
        UUID drillId = UUID.randomUUID();
        Drill drill = buildDrill(drillId, "PRIVATE", "ACTIVE", COACH_PROFILE_ID);
        drill.setMetadata(new DrillMetadata(
            List.of("passing"), List.of("first_touch"), Map.of("passing", 60, "first_touch", 40),
            10, 1, 1, 1, 1, false, "U10", List.of(), null, List.of(), "diagram.png"));
        when(drillRepository.findById(drillId)).thenReturn(Optional.of(drill));
        when(drillRepository.findByIdForUpdate(drillId)).thenReturn(Optional.of(drill));
        when(drillRepository.saveAndFlush(drill)).thenReturn(drill);

        service.updateDrill(drillId, upsert("Rondo v2", null), COACH_USER_ID);

        assertThat(drill.getName()).isEqualTo("Rondo v2");
        assertThat(drill.getMetadata().setupDiagram()).isEqualTo("diagram.png");
        assertThat(drill.getMetadata().skillWeighting()).containsEntry("passing", 60).containsEntry("first_touch", 40);
        assertThat(drill.getMetadata().difficultyTier()).isEqualTo("U12");
        verify(entityManager).refresh(drill, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    void updateDrill_platformDrill_throws404WithoutLocking() {
        UUID drillId = UUID.randomUUID();
        Drill drill = buildDrill(drillId, "PLATFORM", "ACTIVE", null);
        when(drillRepository.findById(drillId)).thenReturn(Optional.of(drill));

        // 404, not 403: never confirm another coach's private drill exists; and no row lock taken.
        assertThatThrownBy(() -> service.updateDrill(drillId, upsert("X", null), COACH_USER_ID))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(drillRepository, never()).findByIdForUpdate(any());
        verify(drillRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateDrill_otherCoachsDrill_throws404WithoutLocking() {
        UUID drillId = UUID.randomUUID();
        Drill drill = buildDrill(drillId, "PRIVATE", "ACTIVE", UUID.randomUUID());
        when(drillRepository.findById(drillId)).thenReturn(Optional.of(drill));

        // 404, not 403: never confirm another coach's private drill exists; and no row lock taken.
        assertThatThrownBy(() -> service.updateDrill(drillId, upsert("X", null), COACH_USER_ID))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(drillRepository, never()).findByIdForUpdate(any());
        verify(drillRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateDrill_archivedDrill_throws404() {
        stubLockRetryer();
        UUID drillId = UUID.randomUUID();
        Drill drill = buildDrill(drillId, "PRIVATE", "ARCHIVED", COACH_PROFILE_ID);
        when(drillRepository.findById(drillId)).thenReturn(Optional.of(drill));
        when(drillRepository.findByIdForUpdate(drillId)).thenReturn(Optional.of(drill));

        assertThatThrownBy(() -> service.updateDrill(drillId, upsert("X", null), COACH_USER_ID))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deleteDrill_ownedDrill_archivesInsteadOfDeleting() {
        stubLockRetryer();
        UUID drillId = UUID.randomUUID();
        Drill drill = buildDrill(drillId, "PRIVATE", "ACTIVE", COACH_PROFILE_ID);
        when(drillRepository.findById(drillId)).thenReturn(Optional.of(drill));
        when(drillRepository.findByIdForUpdate(drillId)).thenReturn(Optional.of(drill));

        service.deleteDrill(drillId, COACH_USER_ID);

        assertThat(drill.getStatus()).isEqualTo("ARCHIVED");
        verify(drillRepository).save(drill);
        verify(drillRepository, never()).delete(any());
        verify(drillVideoRefRepository, never()).clearVideoId(any());
    }

    @Test
    void deleteDrill_withLinkedVideo_isRefusedAndStaysActive() {
        stubLockRetryer();
        UUID drillId = UUID.randomUUID();
        Drill drill = buildDrill(drillId, "PRIVATE", "ACTIVE", COACH_PROFILE_ID);
        when(drillRepository.findById(drillId)).thenReturn(Optional.of(drill));
        when(drillRepository.findByIdForUpdate(drillId)).thenReturn(Optional.of(drill));
        when(drillVideoRefRepository.findByDrillId(drillId))
            .thenReturn(Optional.of(buildVideoRef(drillId, UUID.randomUUID())));

        assertThatThrownBy(() -> service.deleteDrill(drillId, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class);
        assertThat(drill.getStatus()).isEqualTo("ACTIVE");
        verify(drillRepository, never()).save(any());
    }

    @Test
    void resolveWeighting_nullWeight_isRejected() {
        java.util.Map<String, Integer> requested = new java.util.HashMap<>();
        requested.put("passing", 100);
        requested.put("dribbling", null);
        assertThatThrownBy(() -> DrillLibraryService.resolveWeighting(
            requested, List.of("passing", "dribbling"), List.of(), null))
            .isInstanceOf(InvalidParamException.class);
    }

    @Test
    void deleteDrill_alreadyArchived_isIdempotentNoOp() {
        stubLockRetryer();
        UUID drillId = UUID.randomUUID();
        Drill drill = buildDrill(drillId, "PRIVATE", "ARCHIVED", COACH_PROFILE_ID);
        when(drillRepository.findById(drillId)).thenReturn(Optional.of(drill));
        when(drillRepository.findByIdForUpdate(drillId)).thenReturn(Optional.of(drill));

        service.deleteDrill(drillId, COACH_USER_ID);

        verify(drillRepository, never()).save(any());
    }

    @Test
    void deleteDrill_unknownDrill_throws404() {
        UUID drillId = UUID.randomUUID();
        when(drillRepository.findById(drillId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteDrill(drillId, COACH_USER_ID))
            .isInstanceOf(ResourceNotFoundException.class);
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
