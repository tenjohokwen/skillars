package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.arachnid.ArachnidClient;
import com.softropic.skillars.infrastructure.arachnid.ArachnidException;
import com.softropic.skillars.infrastructure.arachnid.ArachnidScanResult;
import com.softropic.skillars.infrastructure.feature.AppFeature;
import com.softropic.skillars.infrastructure.feature.FeatureToggleService;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.infrastructure.videointel.VideoIntelClient;
import com.softropic.skillars.infrastructure.videointel.VideoIntelException;
import com.softropic.skillars.infrastructure.videointel.VideoIntelScanResult;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.security.contract.event.AccountSuspensionRequestedEvent;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.service.AgePolicyService;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.event.VideoModerationAdminAlertEvent;
import com.softropic.skillars.platform.video.contract.event.VideoModerationOwnerNotificationEvent;
import com.softropic.skillars.platform.video.contract.event.VideoModerationRetryEvent;
import com.softropic.skillars.platform.video.contract.event.VideoUploadedEvent;
import com.softropic.skillars.platform.video.contract.exception.TerminalStateViolationException;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ModerationOrchestrationServiceTest {

    @Mock VideoLifecycleService videoLifecycleService;
    @Mock VideoService videoService;
    @Mock VideoRepository videoRepository;
    @Mock VideoModerationScanPersistenceService scanPersistenceService;
    @Mock ArachnidClient arachnidClient;
    @Mock VideoIntelClient videoIntelClient;
    @Mock VideoProviderAdapter videoProviderAdapter;
    @Mock FeatureToggleService featureToggleService;
    @Mock ConfigService configService;
    @Mock ApplicationEventPublisher publisher;
    @Mock TransactionTemplate transactionTemplate;
    @Mock AgePolicyService agePolicyService;
    @Mock PlayerProfileRepository playerProfileRepository;
    @Mock VideoApprovalService videoApprovalService;

    @InjectMocks
    ModerationOrchestrationService service;

    private UUID videoId;
    private String ownerId;
    private Video video;

    @BeforeEach
    void setUp() {
        videoId = UUID.randomUUID();
        ownerId = "owner@example.com";
        video = new Video();
        video.setOwnerId(ownerId);
        video.setProviderAssetId("asset-123");
        video.setOperationalState(OperationalState.PROCESSING);

        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            org.springframework.transaction.support.TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });
        lenient().when(videoRepository.findById(videoId)).thenReturn(Optional.of(video));
        lenient().when(videoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(configService.getLong("platform.moderation_lock_timeout_minutes")).thenReturn(15L);
        lenient().when(videoProviderAdapter.getRawVideoUrl(any())).thenReturn("https://cdn.example.com/asset-123/original");
    }

    @Test
    void arachnidDisabled_skipsLayer1_andPipelineContinues() {
        when(featureToggleService.isEnabled(AppFeature.ARACHNID_ENABLED)).thenReturn(false);
        when(featureToggleService.isEnabled(AppFeature.VIDEOINTEL_ENABLED)).thenReturn(false);

        service.onVideoUploaded(new VideoUploadedEvent(videoId, ownerId));

        verify(scanPersistenceService).upsertScanRecord(videoId, "ARACHNID", "SKIPPED", null, "Feature flag disabled");
        verify(arachnidClient, never()).scan(any());
        // Pipeline continued: MINOR_GATE recorded, transcoding triggered
        verify(scanPersistenceService).upsertScanRecord(eq(videoId), eq("MINOR_GATE"), eq("SKIPPED"), any(), any());
        verify(videoProviderAdapter).triggerTranscoding("asset-123");
    }

    @Test
    void arachnidMatch_locksVideo_suspendsAccount_alertsAdmin() {
        when(featureToggleService.isEnabled(AppFeature.ARACHNID_ENABLED)).thenReturn(true);
        when(arachnidClient.scan(any())).thenReturn(new ArachnidScanResult(true, "CHILD_SEXUAL_EXPLOITATION"));

        service.onVideoUploaded(new VideoUploadedEvent(videoId, ownerId));

        verify(videoLifecycleService).transitionOperationalState(videoId, OperationalState.SCANNING);
        verify(videoLifecycleService).transitionOperationalState(videoId, OperationalState.LOCKED);
        verify(scanPersistenceService).upsertScanRecord(eq(videoId), eq("ARACHNID"), eq("FLAGGED"), any(), any());

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(publisher, atLeastOnce()).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues())
            .anyMatch(e -> e instanceof VideoModerationAdminAlertEvent a && a.urgent())
            .anyMatch(e -> e instanceof AccountSuspensionRequestedEvent s && s.ownerId().equals(ownerId));
        // Pipeline stopped — VideoIntel should NOT be called
        verify(videoIntelClient, never()).detectExplicitContent(any());
    }

    @Test
    void arachnidException_failClosed_adminAlert_videStaysScanning() {
        when(featureToggleService.isEnabled(AppFeature.ARACHNID_ENABLED)).thenReturn(true);
        when(arachnidClient.scan(any())).thenThrow(new ArachnidException("timeout", new RuntimeException()));

        service.onVideoUploaded(new VideoUploadedEvent(videoId, ownerId));

        verify(scanPersistenceService).upsertScanRecord(eq(videoId), eq("ARACHNID"), eq("FAILED"), any(), any());
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(publisher, atLeastOnce()).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues())
            .anyMatch(e -> e instanceof VideoModerationAdminAlertEvent);
        // Fail-closed: LOCKED state NOT set, transcoding NOT triggered
        verify(videoLifecycleService, never()).transitionOperationalState(videoId, OperationalState.LOCKED);
        verify(videoProviderAdapter, never()).triggerTranscoding(any());
    }

    @Test
    void videoIntelFlagged_locksVideo_notifiesOwner() {
        when(featureToggleService.isEnabled(AppFeature.ARACHNID_ENABLED)).thenReturn(true);
        when(featureToggleService.isEnabled(AppFeature.VIDEOINTEL_ENABLED)).thenReturn(true);
        when(arachnidClient.scan(any())).thenReturn(new ArachnidScanResult(false, null));
        when(videoIntelClient.detectExplicitContent(any()))
            .thenReturn(new VideoIntelScanResult(true, 0.92, "explicit content detected"));

        service.onVideoUploaded(new VideoUploadedEvent(videoId, ownerId));

        verify(videoLifecycleService).transitionOperationalState(videoId, OperationalState.LOCKED);
        verify(scanPersistenceService).upsertScanRecord(eq(videoId), eq("VIDEOINTEL"), eq("FLAGGED"), any(), any());
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(publisher, atLeastOnce()).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues())
            .anyMatch(e -> e instanceof VideoModerationOwnerNotificationEvent n && n.ownerId().equals(ownerId));
        verify(videoProviderAdapter, never()).triggerTranscoding(any());
    }

    @Test
    void videoIntelException_failClosed_adminAlert() {
        when(featureToggleService.isEnabled(AppFeature.ARACHNID_ENABLED)).thenReturn(true);
        when(featureToggleService.isEnabled(AppFeature.VIDEOINTEL_ENABLED)).thenReturn(true);
        when(arachnidClient.scan(any())).thenReturn(new ArachnidScanResult(false, null));
        when(videoIntelClient.detectExplicitContent(any()))
            .thenThrow(new VideoIntelException("GCP timeout", new RuntimeException()));

        service.onVideoUploaded(new VideoUploadedEvent(videoId, ownerId));

        verify(scanPersistenceService).upsertScanRecord(eq(videoId), eq("VIDEOINTEL"), eq("FAILED"), any(), any());
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher, atLeastOnce()).publishEvent(captor.capture());
        assertThat(captor.getAllValues()).anyMatch(e -> e instanceof VideoModerationAdminAlertEvent);
        verify(videoProviderAdapter, never()).triggerTranscoding(any());
    }

    @Test
    void allLayersPass_triggersTranscoding_andTransitionsToTranscoding() {
        when(featureToggleService.isEnabled(AppFeature.ARACHNID_ENABLED)).thenReturn(true);
        when(featureToggleService.isEnabled(AppFeature.VIDEOINTEL_ENABLED)).thenReturn(true);
        when(arachnidClient.scan(any())).thenReturn(new ArachnidScanResult(false, null));
        when(videoIntelClient.detectExplicitContent(any()))
            .thenReturn(new VideoIntelScanResult(false, null, null));

        service.onVideoUploaded(new VideoUploadedEvent(videoId, ownerId));

        verify(scanPersistenceService).upsertScanRecord(videoId, "ARACHNID", "PASSED", null, null);
        verify(scanPersistenceService).upsertScanRecord(videoId, "VIDEOINTEL", "PASSED", null, null);
        verify(videoProviderAdapter).triggerTranscoding("asset-123");
        verify(videoLifecycleService).transitionOperationalState(videoId, OperationalState.TRANSCODING);
    }

    @Test
    void encodingCompletedBeforeModeration_fastPath_completesTranscoding() {
        video.setEncodingCompletedAt(java.time.Instant.now().minusSeconds(60));
        when(featureToggleService.isEnabled(AppFeature.ARACHNID_ENABLED)).thenReturn(false);
        when(featureToggleService.isEnabled(AppFeature.VIDEOINTEL_ENABLED)).thenReturn(false);

        service.onVideoUploaded(new VideoUploadedEvent(videoId, ownerId));

        // Fast-path: triggerTranscoding is NOT called (encoding already done)
        verify(videoProviderAdapter, never()).triggerTranscoding(any());
        // SCANNING→TRANSCODING transition happens directly
        verify(videoLifecycleService).transitionOperationalState(videoId, OperationalState.TRANSCODING);
        // completeTranscoding() drives TRANSCODING→READY
        verify(videoService).completeTranscoding(videoId);
    }

    @Test
    void terminalStateOnStart_skipsEntirePipeline() {
        doThrow(new TerminalStateViolationException(videoId, "LOCKED"))
            .when(videoLifecycleService).transitionOperationalState(videoId, OperationalState.SCANNING);

        service.onVideoUploaded(new VideoUploadedEvent(videoId, ownerId));

        verify(arachnidClient, never()).scan(any());
        verify(videoIntelClient, never()).detectExplicitContent(any());
        verify(videoProviderAdapter, never()).triggerTranscoding(any());
    }

    @Test
    void encodingCompletedDuringScanning_fastPath_completesTranscoding_doesNotTriggerBunny() {
        // Simulates: encoding.success arrived while video was in SCANNING (webhook recorded encodingCompletedAt).
        // When moderation finishes, advanceToTranscoding() fast-path should call completeTranscoding()
        // directly instead of calling triggerTranscoding() (Bunny was never called — encoding already done).
        video.setEncodingCompletedAt(java.time.Instant.now());
        when(featureToggleService.isEnabled(AppFeature.ARACHNID_ENABLED)).thenReturn(false);
        when(featureToggleService.isEnabled(AppFeature.VIDEOINTEL_ENABLED)).thenReturn(false);

        service.onVideoUploaded(new VideoUploadedEvent(videoId, ownerId));

        // Fast-path: SCANNING→TRANSCODING transition then completeTranscoding()
        verify(videoLifecycleService).transitionOperationalState(videoId, OperationalState.TRANSCODING);
        verify(videoService).completeTranscoding(videoId);
        // triggerTranscoding() must NOT be called — encoding is already done
        verify(videoProviderAdapter, never()).triggerTranscoding(any());
    }

    @Test
    void slaRetry_runsFullPipeline_skipsProcessingToScanningTransition() {
        when(featureToggleService.isEnabled(AppFeature.ARACHNID_ENABLED)).thenReturn(false);
        when(featureToggleService.isEnabled(AppFeature.VIDEOINTEL_ENABLED)).thenReturn(false);
        video.setOperationalState(OperationalState.SCANNING);

        service.onModerationRetry(new VideoModerationRetryEvent(videoId, ownerId));

        // SLA retry does NOT call PROCESSING→SCANNING; goes straight to pipeline
        verify(videoLifecycleService, never()).transitionOperationalState(videoId, OperationalState.SCANNING);
        verify(videoProviderAdapter).triggerTranscoding("asset-123");
    }

    // ─── Minor Safety Gate (Story 6.6) ───────────────────────────────────────

    @Test
    void minorGate_coachOwnedVideo_skipped_advancesToTranscoding() {
        // Coach ownerId is a UUID string — not parseable as Long → SKIPPED path
        when(featureToggleService.isEnabled(AppFeature.ARACHNID_ENABLED)).thenReturn(false);
        when(featureToggleService.isEnabled(AppFeature.VIDEOINTEL_ENABLED)).thenReturn(false);
        // ownerId = "owner@example.com" (non-numeric) set in setUp()

        service.onVideoUploaded(new VideoUploadedEvent(videoId, ownerId));

        verify(scanPersistenceService).upsertScanRecord(eq(videoId), eq("MINOR_GATE"), eq("SKIPPED"), any(), any());
        verify(videoProviderAdapter).triggerTranscoding("asset-123");
        verify(videoApprovalService, never()).createApprovalRequest(any(), any());
    }

    @Test
    void minorGate_adultPlayer_passed_advancesToTranscoding() {
        String playerOwnerId = "777000001";
        video.setOwnerId(playerOwnerId);
        when(featureToggleService.isEnabled(AppFeature.ARACHNID_ENABLED)).thenReturn(false);
        when(featureToggleService.isEnabled(AppFeature.VIDEOINTEL_ENABLED)).thenReturn(false);

        PlayerProfile profile = new PlayerProfile();
        profile.setId(777_000_001L);
        profile.setDateOfBirth(LocalDate.now().minusYears(25));
        when(playerProfileRepository.findById(777_000_001L)).thenReturn(Optional.of(profile));
        when(agePolicyService.isMinor(profile.getDateOfBirth())).thenReturn(false);

        service.onVideoUploaded(new VideoUploadedEvent(videoId, playerOwnerId));

        verify(scanPersistenceService).upsertScanRecord(eq(videoId), eq("MINOR_GATE"), eq("PASSED"), any(), any());
        verify(videoProviderAdapter).triggerTranscoding("asset-123");
        verify(videoApprovalService, never()).createApprovalRequest(any(), any());
    }

    @Test
    void minorGate_minorPlayer_flagged_setsHiddenAndCreatesApprovalRequest() {
        String playerOwnerId = "777000002";
        video.setOwnerId(playerOwnerId);
        when(featureToggleService.isEnabled(AppFeature.ARACHNID_ENABLED)).thenReturn(false);
        when(featureToggleService.isEnabled(AppFeature.VIDEOINTEL_ENABLED)).thenReturn(false);

        PlayerProfile profile = new PlayerProfile();
        profile.setId(777_000_002L);
        profile.setDateOfBirth(LocalDate.now().minusYears(14));
        when(playerProfileRepository.findById(777_000_002L)).thenReturn(Optional.of(profile));
        when(agePolicyService.isMinor(profile.getDateOfBirth())).thenReturn(true);
        // createApprovalRequest must return true here to simulate the standard parental-approval path.
        // Mockito defaults boolean returns to false, which would silently trigger the "no parent linked"
        // fallback in ModerationOrchestrationService.runMinorSafetyGate() — recording PASSED instead of
        // FLAGGED and calling triggerTranscoding(). Always stub this explicitly for minor-gate tests.
        when(videoApprovalService.createApprovalRequest(videoId, 777_000_002L)).thenReturn(true);

        service.onVideoUploaded(new VideoUploadedEvent(videoId, playerOwnerId));

        verify(scanPersistenceService).upsertScanRecord(eq(videoId), eq("MINOR_GATE"), eq("FLAGGED"), any(), any());
        verify(videoLifecycleService).transitionOperationalState(videoId, OperationalState.HIDDEN);
        verify(videoApprovalService).createApprovalRequest(videoId, 777_000_002L);
        verify(videoProviderAdapter, never()).triggerTranscoding(any());
    }

    @Test
    void minorGate_noPlayerProfile_fallsThroughToTranscoding() {
        String playerOwnerId = "777000003";
        video.setOwnerId(playerOwnerId);
        when(featureToggleService.isEnabled(AppFeature.ARACHNID_ENABLED)).thenReturn(false);
        when(featureToggleService.isEnabled(AppFeature.VIDEOINTEL_ENABLED)).thenReturn(false);
        when(playerProfileRepository.findById(777_000_003L)).thenReturn(Optional.empty());

        service.onVideoUploaded(new VideoUploadedEvent(videoId, playerOwnerId));

        // No profile → safe fallback: PASSED + advance to transcoding
        verify(scanPersistenceService).upsertScanRecord(eq(videoId), eq("MINOR_GATE"), eq("PASSED"), any(), any());
        verify(videoProviderAdapter).triggerTranscoding("asset-123");
        verify(videoApprovalService, never()).createApprovalRequest(any(), any());
    }
}
