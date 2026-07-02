package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.development.contract.PlayerTimelineEventType;
import com.softropic.skillars.platform.development.contract.SkillRadarEntry;
import com.softropic.skillars.platform.development.repo.CoachBranding;
import com.softropic.skillars.platform.development.repo.CoachBrandingRepository;
import com.softropic.skillars.platform.development.repo.PerformanceReport;
import com.softropic.skillars.platform.development.repo.PerformanceReportRepository;
import com.softropic.skillars.platform.development.repo.PlayerRadarBaselineRepository;
import com.softropic.skillars.platform.development.repo.PlayerRadarCompositeRepository;
import com.softropic.skillars.platform.development.repo.SkillDefinitionRepository;
import com.softropic.skillars.platform.development.repo.SluRepository;
import com.softropic.skillars.platform.filestorage.service.FileStorageService;
import com.softropic.skillars.platform.marketplace.contract.CoachProfileDto;
import com.softropic.skillars.platform.marketplace.contract.CoachSubscriptionTier;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.marketplace.service.PlayerProfileService;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.security.contract.exception.FeatureGatedException;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportGenerationServiceTest {

    @Mock private CoachProfileService coachProfileService;
    @Mock private PlayerProfileService playerProfileService;
    @Mock private SluRepository sluRepository;
    @Mock private SkillDefinitionRepository skillDefinitionRepository;
    @Mock private PlayerRadarCompositeRepository compositeRepository;
    @Mock private PlayerRadarBaselineRepository baselineRepository;
    @Mock private PerformanceReportRepository reportRepository;
    @Mock private CoachBrandingRepository brandingRepository;
    @Mock private FileStorageService fileStorageService;
    @Mock private TimelineEventListener timelineEventListener;
    @Mock private ApplicationEventPublisher publisher;
    @Mock private SecurityUtil securityUtil;
    @Mock private CoachPlayerAuthorizationService coachPlayerAuthorizationService;
    @Mock private PlayerProfileRepository playerProfileRepository;

    private ReportGenerationService service;

    private static final UUID COACH_ID    = UUID.randomUUID();
    private static final Long COACH_USER_ID = 1001L;
    private static final Long PLAYER_ID  = 9001L;

    @BeforeEach
    void setUp() {
        service = new ReportGenerationService(
            coachProfileService, playerProfileService,
            sluRepository, skillDefinitionRepository, compositeRepository, baselineRepository,
            reportRepository, brandingRepository, fileStorageService,
            timelineEventListener, publisher, securityUtil, coachPlayerAuthorizationService,
            playerProfileRepository
        );
        ReflectionTestUtils.setField(service, "baseUrl", "http://app.test");
    }

    private CoachProfileDto coachDto() {
        return new CoachProfileDto(COACH_ID, "Test Coach", null, "BASIC",
            List.of(), 0.0, 0, null, List.of(), null, null,
            List.of(), List.of(), null, null, List.of(), false, 0, List.of(), null);
    }

    private void stubGenerateReport(CoachSubscriptionTier tier) {
        when(coachProfileService.getCoachIdByUserId(COACH_USER_ID)).thenReturn(COACH_ID);
        when(coachProfileService.getCoachSubscriptionTier(COACH_ID)).thenReturn(tier);
        when(sluRepository.findLastSessionDate(PLAYER_ID, COACH_ID)).thenReturn(Instant.now().minusSeconds(3600));
        when(coachProfileService.getPublicProfile(COACH_ID)).thenReturn(coachDto());
        when(playerProfileService.getPlayerNameByPlayerId(PLAYER_ID)).thenReturn("Alex Player");
        when(playerProfileService.getPlayerAgeByPlayerId(PLAYER_ID)).thenReturn(12);
        when(skillDefinitionRepository.findAllByActiveTrueOrderByDisplayOrderAsc()).thenReturn(List.of());
        when(compositeRepository.findByIdPlayerId(PLAYER_ID)).thenReturn(List.of());
        when(baselineRepository.findByIdPlayerId(PLAYER_ID)).thenReturn(List.of());
        when(sluRepository.sumTotalSluByPlayerId(PLAYER_ID)).thenReturn(BigDecimal.valueOf(150));
        when(sluRepository.countDistinctSessions(PLAYER_ID)).thenReturn(5L);
        PerformanceReport saved = new PerformanceReport();
        saved.setId(UUID.randomUUID());
        saved.setCoachId(COACH_ID);
        saved.setPlayerId(PLAYER_ID);
        saved.setGeneratedAt(Instant.now());
        saved.setStorageKey("reports/test/report.pdf");
        saved.setNextSteps("Keep practicing dribbling.");
        when(reportRepository.saveAndFlush(any())).thenReturn(saved);
        when(playerProfileService.getParentEmailByPlayerId(PLAYER_ID)).thenReturn("parent@test.com");
    }

    @Test
    void generateReport_scoutCoach_throwsFeatureGatedException() {
        when(coachProfileService.getCoachIdByUserId(COACH_USER_ID)).thenReturn(COACH_ID);
        when(coachProfileService.getCoachSubscriptionTier(COACH_ID)).thenReturn(CoachSubscriptionTier.SCOUT);

        assertThatThrownBy(() -> service.generateReport(COACH_USER_ID, PLAYER_ID, "keep at it"))
            .isInstanceOf(FeatureGatedException.class);
    }

    @Test
    void generateReport_coachWithNoPlayerRelationship_throwsAccessDeniedException() {
        when(coachProfileService.getCoachIdByUserId(COACH_USER_ID)).thenReturn(COACH_ID);
        when(coachProfileService.getCoachSubscriptionTier(COACH_ID)).thenReturn(CoachSubscriptionTier.INSTRUCTOR);
        when(sluRepository.findLastSessionDate(PLAYER_ID, COACH_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.generateReport(COACH_USER_ID, PLAYER_ID, "keep at it"))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void generateReport_instructorCoach_generatesPdf() {
        stubGenerateReport(CoachSubscriptionTier.INSTRUCTOR);

        service.generateReport(COACH_USER_ID, PLAYER_ID, "Keep practicing dribbling.");

        ArgumentCaptor<byte[]> bytesCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(fileStorageService).storeBytes(bytesCaptor.capture(), anyString(), eq("application/pdf"), anyString());
        assertThat(bytesCaptor.getValue()).isNotEmpty();
        assertThat(bytesCaptor.getValue().length).isGreaterThan(100);
    }

    @Test
    void generateReport_savesReportAndWritesTimelineEvent() {
        stubGenerateReport(CoachSubscriptionTier.INSTRUCTOR);

        service.generateReport(COACH_USER_ID, PLAYER_ID, "Focus on passing.");

        verify(reportRepository).saveAndFlush(any(PerformanceReport.class));
        verify(timelineEventListener).writeTimelineEvent(
            eq(PLAYER_ID), eq(PlayerTimelineEventType.PERFORMANCE_REPORT),
            any(), eq("development"), any()
        );
    }

    @Test
    void generateReport_publishesParentNotificationEnvelope() {
        stubGenerateReport(CoachSubscriptionTier.INSTRUCTOR);

        service.generateReport(COACH_USER_ID, PLAYER_ID, "Work on shooting.");

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(Envelope.class);
        Envelope envelope = (Envelope) eventCaptor.getValue();
        assertThat(envelope.emailTemplate()).isEqualTo(EmailTemplate.PERFORMANCE_REPORT_SHARED);
    }

    @Test
    void generateReport_emailContainsReportsPageUrl_notSignedUrl() {
        stubGenerateReport(CoachSubscriptionTier.INSTRUCTOR);

        service.generateReport(COACH_USER_ID, PLAYER_ID, "Great progress!");

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(eventCaptor.capture());
        Envelope envelope = (Envelope) eventCaptor.getValue();
        assertThat(envelope.data()).containsKey("reportsPageUrl");
        assertThat(envelope.data()).doesNotContainKey("downloadUrl");
    }

    @Test
    void generateReport_academyWithBranding_logoFetchAttempted() {
        stubGenerateReport(CoachSubscriptionTier.ACADEMY);
        CoachBranding branding = new CoachBranding();
        branding.setCoachId(COACH_ID);
        branding.setLogoKey("logos/coach-logo.png");
        branding.setBrandColour("#3B82F6");
        when(brandingRepository.findById(COACH_ID)).thenReturn(Optional.of(branding));
        when(fileStorageService.downloadBytes("logos/coach-logo.png"))
            .thenThrow(new RuntimeException("S3 error — logo download falls back gracefully"));

        // Should not throw — logo failure is caught and falls back to text header
        service.generateReport(COACH_USER_ID, PLAYER_ID, "Great session.");

        ArgumentCaptor<byte[]> bytesCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(fileStorageService).storeBytes(bytesCaptor.capture(), anyString(), eq("application/pdf"), anyString());
        assertThat(bytesCaptor.getValue()).isNotEmpty();
    }

    @Test
    void generateReport_academyWithoutBranding_defaultHeaderRendered() {
        stubGenerateReport(CoachSubscriptionTier.ACADEMY);
        when(brandingRepository.findById(COACH_ID)).thenReturn(Optional.empty());

        service.generateReport(COACH_USER_ID, PLAYER_ID, "Excellent improvement!");

        ArgumentCaptor<byte[]> bytesCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(fileStorageService).storeBytes(bytesCaptor.capture(), anyString(), eq("application/pdf"), anyString());
        assertThat(bytesCaptor.getValue()).isNotEmpty();
    }

    @Test
    void generateReport_timelineWriteFailure_doesNotRollbackReport() {
        stubGenerateReport(CoachSubscriptionTier.INSTRUCTOR);
        doThrow(new RuntimeException("DB timeout")).when(timelineEventListener)
            .writeTimelineEvent(any(), any(), any(), any(), any());

        // Must not propagate the exception
        service.generateReport(COACH_USER_ID, PLAYER_ID, "Keep going.");

        verify(reportRepository).saveAndFlush(any(PerformanceReport.class));
    }

    @Test
    void listReports_callsSignedDownloadUrl() {
        PerformanceReport r1 = new PerformanceReport();
        r1.setId(UUID.randomUUID());
        r1.setCoachId(COACH_ID);
        r1.setPlayerId(PLAYER_ID);
        r1.setGeneratedAt(Instant.now());
        r1.setStorageKey("reports/r1/report.pdf");
        r1.setNextSteps("test");

        PerformanceReport r2 = new PerformanceReport();
        r2.setId(UUID.randomUUID());
        r2.setCoachId(COACH_ID);
        r2.setPlayerId(PLAYER_ID);
        r2.setGeneratedAt(Instant.now().minusSeconds(86400));
        r2.setStorageKey("reports/r2/report.pdf");
        r2.setNextSteps("test");

        when(reportRepository.findByPlayerIdOrderByGeneratedAtDesc(PLAYER_ID)).thenReturn(List.of(r1, r2));
        when(coachProfileService.getDisplayNamesByIds(Set.of(COACH_ID)))
            .thenReturn(Map.of(COACH_ID, "Test Coach"));
        when(fileStorageService.signedDownloadUrl(anyString())).thenReturn("https://s3.example.com/signed");

        var result = service.listReports(PLAYER_ID);

        assertThat(result).hasSize(2);
        verify(fileStorageService, times(2)).signedDownloadUrl(anyString());
    }

    @Test
    void listReports_multipleReportsFromSameCoach_batchesNameLookup() {
        UUID sameCoachId = UUID.randomUUID();
        List<PerformanceReport> reports = List.of(
            report(sameCoachId, "reports/1/report.pdf"),
            report(sameCoachId, "reports/2/report.pdf"),
            report(sameCoachId, "reports/3/report.pdf")
        );
        when(reportRepository.findByPlayerIdOrderByGeneratedAtDesc(PLAYER_ID)).thenReturn(reports);
        when(coachProfileService.getDisplayNamesByIds(Set.of(sameCoachId)))
            .thenReturn(Map.of(sameCoachId, "Batch Coach"));
        when(fileStorageService.signedDownloadUrl(anyString())).thenReturn("https://s3.example.com/signed");

        service.listReports(PLAYER_ID);

        verify(coachProfileService, times(1)).getDisplayNamesByIds(any());
    }

    @Test
    void saveBranding_nonAcademyCoach_throwsFeatureGatedException() {
        when(coachProfileService.getCoachSubscriptionTier(COACH_ID)).thenReturn(CoachSubscriptionTier.INSTRUCTOR);

        var request = new com.softropic.skillars.platform.development.contract.CoachBrandingRequest(null, null);
        assertThatThrownBy(() -> service.saveBranding(COACH_ID, request))
            .isInstanceOf(FeatureGatedException.class);
    }

    @Test
    void saveBranding_academyCoach_upsertsBranding() {
        when(coachProfileService.getCoachSubscriptionTier(COACH_ID)).thenReturn(CoachSubscriptionTier.ACADEMY);
        when(brandingRepository.findById(COACH_ID)).thenReturn(Optional.empty());

        var request = new com.softropic.skillars.platform.development.contract.CoachBrandingRequest(
            "logos/test.png", "#3B82F6");
        service.saveBranding(COACH_ID, request);

        ArgumentCaptor<CoachBranding> captor = ArgumentCaptor.forClass(CoachBranding.class);
        verify(brandingRepository).save(captor.capture());
        assertThat(captor.getValue().getCoachId()).isEqualTo(COACH_ID);
        assertThat(captor.getValue().getLogoKey()).isEqualTo("logos/test.png");
        assertThat(captor.getValue().getBrandColour()).isEqualTo("#3B82F6");
    }

    private PerformanceReport report(UUID coachId, String storageKey) {
        PerformanceReport r = new PerformanceReport();
        r.setId(UUID.randomUUID());
        r.setCoachId(coachId);
        r.setPlayerId(PLAYER_ID);
        r.setGeneratedAt(Instant.now());
        r.setStorageKey(storageKey);
        r.setNextSteps("test");
        return r;
    }
}
