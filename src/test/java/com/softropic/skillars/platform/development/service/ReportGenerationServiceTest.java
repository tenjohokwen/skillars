package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.security.contract.exception.LoginRateLimitedException;
import com.softropic.skillars.platform.development.contract.PlayerTimelineEventType;
import com.softropic.skillars.platform.development.contract.ReportGeneratedEvent;
import com.softropic.skillars.platform.development.contract.ReportStatus;
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
import com.softropic.skillars.platform.security.contract.util.AuthoritiesConstants;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
    @Mock private CoachPlayerLastSessionResolver coachPlayerLastSessionResolver;
    @Mock private SkillDefinitionRepository skillDefinitionRepository;
    @Mock private PlayerRadarCompositeRepository compositeRepository;
    @Mock private PlayerRadarBaselineRepository baselineRepository;
    @Mock private PerformanceReportRepository reportRepository;
    @Mock private CoachBrandingRepository brandingRepository;
    @Mock private FileStorageService fileStorageService;
    @Mock private TimelineEventListener timelineEventListener;
    @Mock private ApplicationEventPublisher publisher;
    @Mock private CoachPlayerAuthorizationService coachPlayerAuthorizationService;
    @Mock private com.softropic.skillars.platform.config.service.ConfigService configService;
    @Mock private SecurityUtil securityUtil;
    @Mock private PlayerProfileRepository playerProfileRepository;
    // skillars-deferred-92 AC18: a real instance, not a mock. The per-coach limit is the
    // point of the AC, and a mock would return false for tryConsume and silently reject
    // every call, or be stubbed to always allow and prove nothing.
    // skillars-deferred-117 AC4: RateLimitingService now needs a ConfigService for its
    // eviction-sweep TTL — nothing in this test triggers the sweep, so an unstubbed mock suffices.
    private final com.softropic.skillars.infrastructure.security.RateLimitingService rateLimitingService =
        new com.softropic.skillars.infrastructure.security.RateLimitingService(
            org.mockito.Mockito.mock(com.softropic.skillars.platform.config.service.ConfigService.class));

    private ReportGenerationService service;

    private static final UUID COACH_ID    = UUID.randomUUID();
    private static final Long COACH_USER_ID = 1001L;
    private static final Long PLAYER_ID  = 9001L;

    @BeforeEach
    void setUp() {
        service = new ReportGenerationService(
            coachProfileService, playerProfileService,
            sluRepository, coachPlayerLastSessionResolver,
            skillDefinitionRepository, compositeRepository, baselineRepository,
            reportRepository, brandingRepository, fileStorageService,
            timelineEventListener, publisher, coachPlayerAuthorizationService,
            rateLimitingService, configService, securityUtil, playerProfileRepository
        );
        ReflectionTestUtils.setField(service, "baseUrl", "http://app.test");
        // skillars-deferred-153 AC7: a bare @Mock ConfigService does not throw on an unstubbed call —
        // getBoundedLong returns Mockito's default 0L, which would make every existing happy-path
        // generateReport test (lastSessionDate stubbed as "1 hour ago") start throwing
        // AccessDeniedException. lenient() — the stale-signal test below overrides with its own value.
        org.mockito.Mockito.lenient().when(configService.getBoundedLong(
            "development.timeline.coachAccessExpiryDays", 1L, 3650L)).thenReturn(90L);
    }

    private CoachProfileDto coachDto() {
        return new CoachProfileDto(COACH_ID, "Test Coach", null, "BASIC",
            List.of(), 0.0, 0, null, List.of(), null, null,
            List.of(), List.of(), null, null, List.of(), false, 0, List.of(), null);
    }

    private void stubGenerateReport(CoachSubscriptionTier tier) {
        when(coachProfileService.getCoachIdByUserId(COACH_USER_ID)).thenReturn(COACH_ID);
        when(coachProfileService.getCoachSubscriptionTier(COACH_ID)).thenReturn(tier);
        when(coachPlayerLastSessionResolver.resolve(PLAYER_ID, COACH_ID)).thenReturn(
            new CoachPlayerLastSessionResolver.LastCoachSessionSignal(Instant.now().minusSeconds(3600), null));
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
        saved.setStatus(ReportStatus.PENDING_UPLOAD);
        saved.setNextSteps("Keep practicing dribbling.");
        when(reportRepository.saveAndFlush(any())).thenReturn(saved);
    }

    // ---- skillars-deferred-92 AC18: the per-coach rate limit ----

    /**
     * The bound that actually matters. {@code @RateLimited} keys on the client IP, so before this
     * every coach behind one office or school NAT shared a single 10/minute budget -- and any caller
     * whose IP could not be resolved shared one global {@code "report_generate:unknown"} bucket with
     * every other such caller.
     */
    @Test
    void generateReport_perCoachLimitExhausted_isRejected() {
        stubGenerateReport(CoachSubscriptionTier.INSTRUCTOR);

        for (int i = 0; i < 10; i++) {
            service.generateReport(COACH_USER_ID, PLAYER_ID, "Keep practicing dribbling.");
        }

        assertThatThrownBy(() -> service.generateReport(COACH_USER_ID, PLAYER_ID, "One too many"))
            .as("the 11th report inside the window must be refused for this coach")
            .isInstanceOf(LoginRateLimitedException.class)
            .hasMessageContaining("Report generation limit");
    }

    /**
     * The half that proves the limit is really per-coach. Under the IP-keyed aspect alone these two
     * coaches would share one budget, so the second would be refused once the first had spent the
     * quota -- which is the defect, not the guard.
     */
    @Test
    void generateReport_twoCoachesOnOneIp_doNotExhaustEachOther() {
        Long otherCoachUserId = COACH_USER_ID + 1;
        UUID otherCoachId = UUID.randomUUID();
        stubGenerateReport(CoachSubscriptionTier.INSTRUCTOR);
        when(coachProfileService.getCoachIdByUserId(otherCoachUserId)).thenReturn(otherCoachId);
        when(coachProfileService.getCoachSubscriptionTier(otherCoachId)).thenReturn(CoachSubscriptionTier.INSTRUCTOR);
        when(coachPlayerLastSessionResolver.resolve(PLAYER_ID, otherCoachId)).thenReturn(
            new CoachPlayerLastSessionResolver.LastCoachSessionSignal(Instant.now().minusSeconds(3600), null));
        when(coachProfileService.getPublicProfile(otherCoachId)).thenReturn(coachDto());

        for (int i = 0; i < 10; i++) {
            service.generateReport(COACH_USER_ID, PLAYER_ID, "Keep practicing dribbling.");
        }
        assertThatThrownBy(() -> service.generateReport(COACH_USER_ID, PLAYER_ID, "One too many"))
            .isInstanceOf(LoginRateLimitedException.class);

        assertThatCode(() -> service.generateReport(otherCoachUserId, PLAYER_ID, "First for this coach"))
            .as("A second coach must have their own budget. If this throws, the limit is keyed on "
                + "something shared - which is exactly the IP-keyed behaviour AC18 supplements.")
            .doesNotThrowAnyException();
    }

    // ---- generateReport (synchronous: authz, PDF build, PENDING_UPLOAD save, event publish) ----

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
        when(coachPlayerLastSessionResolver.resolve(PLAYER_ID, COACH_ID)).thenReturn(
            new CoachPlayerLastSessionResolver.LastCoachSessionSignal(null, null));

        assertThatThrownBy(() -> service.generateReport(COACH_USER_ID, PLAYER_ID, "keep at it"))
            .isInstanceOf(AccessDeniedException.class);
    }

    // skillars-deferred-153 AC7: report generation now shares TimelineQueryService's own recency
    // policy — a non-null but stale lastSessionDate (predating the configured
    // development.timeline.coachAccessExpiryDays window) is denied, not just a null one.
    @Test
    void generateReport_lastSessionPredatesAccessWindow_throwsAccessDeniedException() {
        when(coachProfileService.getCoachIdByUserId(COACH_USER_ID)).thenReturn(COACH_ID);
        when(coachProfileService.getCoachSubscriptionTier(COACH_ID)).thenReturn(CoachSubscriptionTier.INSTRUCTOR);
        when(coachPlayerLastSessionResolver.resolve(PLAYER_ID, COACH_ID)).thenReturn(
            new CoachPlayerLastSessionResolver.LastCoachSessionSignal(Instant.now().minus(200, java.time.temporal.ChronoUnit.DAYS), null));
        when(configService.getBoundedLong("development.timeline.coachAccessExpiryDays", 1L, 3650L)).thenReturn(90L);

        assertThatThrownBy(() -> service.generateReport(COACH_USER_ID, PLAYER_ID, "stale"))
            .isInstanceOf(AccessDeniedException.class);
        verify(reportRepository, never()).saveAndFlush(any());
    }

    // skillars-deferred-152 AC3/Task 3: the third and final SLU-only report-generation gate now
    // reads the merged signal CoachRosterService/TimelineQueryService/SessionPrepService already
    // use, so a Quick-Complete-only coach (no SLU row, only a coach-scoped timeline
    // SESSION_COMPLETED event) can generate a report.
    //
    // skillars-deferred-152 code review (2026-10-17): this test pins the CALL-SITE SWAP, which is
    // all it can pin -- with the resolver mocked, an SLU-backed and a timeline-only signal are the
    // same LastCoachSessionSignal value, so no stub here can distinguish them. (An earlier version
    // re-stubbed resolve(...) with a value byte-for-byte identical to stubGenerateReport's and
    // claimed in a comment to be constructing the Quick-Complete-only shape; that stub was a
    // literal no-op and has been removed.) The merge itself lives in CoachPlayerLastSessionResolver
    // and is exercised end-to-end against a real DB by SessionPrepResourceIT and, since 2026-10-17,
    // by CoachPlayerLastSessionResolverTest's own unit cases -- including the timeline-only-no-SLU
    // shape (the Quick-Complete-only coach) that this test deliberately does not claim to cover.
    @Test
    void generateReport_accessGateReadsMergedResolverNotSluRepositoryDirectly() {
        stubGenerateReport(CoachSubscriptionTier.INSTRUCTOR);

        assertThatCode(() -> service.generateReport(COACH_USER_ID, PLAYER_ID, "First report for this coach"))
            .doesNotThrowAnyException();

        verify(coachPlayerLastSessionResolver).resolve(PLAYER_ID, COACH_ID);
        verify(sluRepository, never()).findLastSessionDate(any(), any());
    }

    @Test
    void generateReport_instructorCoach_savesPendingUploadReportAndPublishesEvent() {
        stubGenerateReport(CoachSubscriptionTier.INSTRUCTOR);

        service.generateReport(COACH_USER_ID, PLAYER_ID, "Keep practicing dribbling.");

        ArgumentCaptor<PerformanceReport> reportCaptor = ArgumentCaptor.forClass(PerformanceReport.class);
        verify(reportRepository).saveAndFlush(reportCaptor.capture());
        assertThat(reportCaptor.getValue().getStatus()).isEqualTo(ReportStatus.PENDING_UPLOAD);
        assertThat(reportCaptor.getValue().getStorageKey()).isNull();

        ArgumentCaptor<ReportGeneratedEvent> eventCaptor = ArgumentCaptor.forClass(ReportGeneratedEvent.class);
        verify(publisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().pdfBytes()).isNotEmpty();
        assertThat(eventCaptor.getValue().pdfBytes().length).isGreaterThan(100);
        assertThat(eventCaptor.getValue().playerId()).isEqualTo(PLAYER_ID);

        verify(fileStorageService, never()).storeBytes(any(), anyString(), anyString(), anyString());
        verify(timelineEventListener, never()).writeTimelineEvent(any(), any(), any(), any(), any());
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

        ArgumentCaptor<ReportGeneratedEvent> eventCaptor = ArgumentCaptor.forClass(ReportGeneratedEvent.class);
        verify(publisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().pdfBytes()).isNotEmpty();
    }

    @Test
    void generateReport_academyWithoutBranding_defaultHeaderRendered() {
        stubGenerateReport(CoachSubscriptionTier.ACADEMY);
        when(brandingRepository.findById(COACH_ID)).thenReturn(Optional.empty());

        service.generateReport(COACH_USER_ID, PLAYER_ID, "Excellent improvement!");

        ArgumentCaptor<ReportGeneratedEvent> eventCaptor = ArgumentCaptor.forClass(ReportGeneratedEvent.class);
        verify(publisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().pdfBytes()).isNotEmpty();
    }

    // ---- onReportGenerated (async post-commit: S3 upload, status flip, timeline, notify) ----

    private ReportGeneratedEvent generatedEvent() {
        return new ReportGeneratedEvent(UUID.randomUUID(), PLAYER_ID, "Test Coach", "Alex Player",
            Instant.now(), "pdf-bytes".getBytes());
    }

    @Test
    void onReportGenerated_uploadSucceeds_marksReadyAndWritesTimelineAndNotifiesParent() {
        ReportGeneratedEvent event = generatedEvent();
        when(playerProfileService.getParentEmailByPlayerId(PLAYER_ID)).thenReturn("parent@test.com");

        service.onReportGenerated(event);

        verify(fileStorageService).storeBytes(eq(event.pdfBytes()), anyString(), eq("application/pdf"), anyString());
        verify(reportRepository).updateStatusAndStorageKey(eq(event.reportId()), eq(ReportStatus.READY), anyString());
        verify(timelineEventListener).writeTimelineEvent(
            eq(PLAYER_ID), eq(PlayerTimelineEventType.PERFORMANCE_REPORT),
            eq(event.reportId()), eq("development"), any());

        ArgumentCaptor<Object> notifyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(notifyCaptor.capture());
        assertThat(notifyCaptor.getValue()).isInstanceOf(Envelope.class);
        assertThat(((Envelope) notifyCaptor.getValue()).emailTemplate()).isEqualTo(EmailTemplate.PERFORMANCE_REPORT_SHARED);
    }

    @Test
    void onReportGenerated_uploadFails_marksUploadFailedAndSkipsTimelineAndNotification() {
        ReportGeneratedEvent event = generatedEvent();
        doThrow(new RuntimeException("S3 unavailable")).when(fileStorageService)
            .storeBytes(any(), anyString(), anyString(), anyString());

        service.onReportGenerated(event);

        verify(reportRepository).updateStatusAndStorageKey(event.reportId(), ReportStatus.UPLOAD_FAILED, null);
        verify(timelineEventListener, never()).writeTimelineEvent(any(), any(), any(), any(), any());
        verify(publisher, never()).publishEvent(any(Envelope.class));
    }

    @Test
    void onReportGenerated_timelineWriteFailure_doesNotThrowAndStillNotifiesParent() {
        ReportGeneratedEvent event = generatedEvent();
        when(playerProfileService.getParentEmailByPlayerId(PLAYER_ID)).thenReturn("parent@test.com");
        doThrow(new RuntimeException("DB timeout")).when(timelineEventListener)
            .writeTimelineEvent(any(), any(), any(), any(), any());

        assertThatCode(() -> service.onReportGenerated(event)).doesNotThrowAnyException();

        verify(reportRepository).updateStatusAndStorageKey(eq(event.reportId()), eq(ReportStatus.READY), anyString());
        verify(publisher).publishEvent(any(Envelope.class));
    }

    // ---- listReports (READY-only) ----

    // skillars-deferred-153 AC6 code review (2026-10-17): NEW. Task 9 claimed coverage of the
    // newly-shared listReports authorization call but none was added — the pre-existing listReports_*
    // tests below pass only because the mocked CoachPlayerAuthorizationService no-ops, so dropping
    // the call entirely (re-opening the IDOR the AC6 consolidation closed) would have kept them
    // green. This asserts the delegation to the consolidated predicate explicitly.
    @Test
    void listReports_delegatesToConsolidatedIfCoachAuthorizationCheck() {
        when(reportRepository.findByPlayerIdAndStatusOrderByGeneratedAtDesc(PLAYER_ID, ReportStatus.READY))
            .thenReturn(List.of());

        service.listReports(PLAYER_ID);

        verify(coachPlayerAuthorizationService).requireCoachPlayerRelationshipIfCoach(PLAYER_ID);
    }

    // ...and it runs BEFORE the repository read, so a rejected caller never reaches the data.
    @Test
    void listReports_authorizationRejects_neverReadsReports() {
        doThrow(new AccessDeniedException("no relationship"))
            .when(coachPlayerAuthorizationService).requireCoachPlayerRelationshipIfCoach(PLAYER_ID);

        assertThatThrownBy(() -> service.listReports(PLAYER_ID))
            .isInstanceOf(AccessDeniedException.class);

        verify(reportRepository, never())
            .findByPlayerIdAndStatusOrderByGeneratedAtDesc(any(), any());
        verify(fileStorageService, never()).signedDownloadUrl(anyString());
    }

    @Test
    void listReports_callsSignedDownloadUrl() {
        PerformanceReport r1 = readyReport(COACH_ID, "reports/r1/report.pdf");
        PerformanceReport r2 = readyReport(COACH_ID, "reports/r2/report.pdf");

        when(reportRepository.findByPlayerIdAndStatusOrderByGeneratedAtDesc(PLAYER_ID, ReportStatus.READY))
            .thenReturn(List.of(r1, r2));
        when(coachProfileService.getDisplayNamesByIds(Set.of(COACH_ID)))
            .thenReturn(Map.of(COACH_ID, "Test Coach"));
        when(fileStorageService.signedDownloadUrl(anyString())).thenReturn("https://s3.example.com/signed");

        var result = service.listReports(PLAYER_ID);

        assertThat(result).hasSize(2);
        verify(fileStorageService, times(2)).signedDownloadUrl(anyString());
    }

    // skillars-deferred-154 AC1: requireCoachPlayerRelationshipIfCoach only gates whether a coach
    // may see ANY report for this player; without this filter a coach whose only relationship is
    // e.g. DISPUTED could read every READY report ever generated by every coach.
    @Test
    void listReports_coachCaller_seesOnlyOwnReports() {
        UUID otherCoachId = UUID.randomUUID();
        PerformanceReport ownReport = readyReport(COACH_ID, "reports/own/report.pdf");
        PerformanceReport otherCoachReport = readyReport(otherCoachId, "reports/other/report.pdf");
        when(reportRepository.findByPlayerIdAndStatusOrderByGeneratedAtDesc(PLAYER_ID, ReportStatus.READY))
            .thenReturn(List.of(ownReport, otherCoachReport));
        when(securityUtil.isCurrentUserInRole(AuthoritiesConstants.COACH)).thenReturn(true);
        when(securityUtil.getCurrentCoachUserId()).thenReturn(COACH_USER_ID);
        when(playerProfileRepository.existsByIdAndParentId(PLAYER_ID, COACH_USER_ID)).thenReturn(false);
        when(playerProfileRepository.existsByIdAndUserId(PLAYER_ID, COACH_USER_ID)).thenReturn(false);
        when(coachProfileService.getCoachIdByUserId(COACH_USER_ID)).thenReturn(COACH_ID);
        when(coachProfileService.getDisplayNamesByIds(Set.of(COACH_ID)))
            .thenReturn(Map.of(COACH_ID, "Test Coach"));
        when(fileStorageService.signedDownloadUrl(anyString())).thenReturn("https://s3.example.com/signed");

        var result = service.listReports(PLAYER_ID);

        assertThat(result).hasSize(1);
        verify(fileStorageService, times(1)).signedDownloadUrl("reports/own/report.pdf");
        verify(fileStorageService, never()).signedDownloadUrl("reports/other/report.pdf");
    }

    // A parent/player caller (the ownership-guard @PreAuthorize branch) must keep seeing every
    // coach's reports for their own child, unfiltered — only the ROLE_COACH branch narrows.
    @Test
    void listReports_parentOrPlayerCaller_seesEveryCoachsReportsUnfiltered() {
        UUID otherCoachId = UUID.randomUUID();
        PerformanceReport r1 = readyReport(COACH_ID, "reports/r1/report.pdf");
        PerformanceReport r2 = readyReport(otherCoachId, "reports/r2/report.pdf");
        when(reportRepository.findByPlayerIdAndStatusOrderByGeneratedAtDesc(PLAYER_ID, ReportStatus.READY))
            .thenReturn(List.of(r1, r2));
        when(securityUtil.isCurrentUserInRole(AuthoritiesConstants.COACH)).thenReturn(false);
        when(coachProfileService.getDisplayNamesByIds(Set.of(COACH_ID, otherCoachId)))
            .thenReturn(Map.of(COACH_ID, "Coach One", otherCoachId, "Coach Two"));
        when(fileStorageService.signedDownloadUrl(anyString())).thenReturn("https://s3.example.com/signed");

        var result = service.listReports(PLAYER_ID);

        assertThat(result).hasSize(2);
        verify(coachProfileService, never()).getCoachIdByUserId(any());
    }

    // The escape arm mirrors requireCoachPlayerRelationshipIfCoach's own defensive shape: a coach
    // who is ALSO this player's own parent/self-owner (unreachable in production today) must not
    // have the filter applied either.
    @Test
    void listReports_coachWhoIsAlsoPlayerOwner_notFiltered() {
        UUID otherCoachId = UUID.randomUUID();
        PerformanceReport r1 = readyReport(COACH_ID, "reports/r1/report.pdf");
        PerformanceReport r2 = readyReport(otherCoachId, "reports/r2/report.pdf");
        when(reportRepository.findByPlayerIdAndStatusOrderByGeneratedAtDesc(PLAYER_ID, ReportStatus.READY))
            .thenReturn(List.of(r1, r2));
        when(securityUtil.isCurrentUserInRole(AuthoritiesConstants.COACH)).thenReturn(true);
        when(securityUtil.getCurrentCoachUserId()).thenReturn(COACH_USER_ID);
        when(playerProfileRepository.existsByIdAndParentId(PLAYER_ID, COACH_USER_ID)).thenReturn(true);
        when(coachProfileService.getDisplayNamesByIds(Set.of(COACH_ID, otherCoachId)))
            .thenReturn(Map.of(COACH_ID, "Coach One", otherCoachId, "Coach Two"));
        when(fileStorageService.signedDownloadUrl(anyString())).thenReturn("https://s3.example.com/signed");

        var result = service.listReports(PLAYER_ID);

        assertThat(result).hasSize(2);
        verify(coachProfileService, never()).getCoachIdByUserId(any());
    }

    @Test
    void listReports_multipleReportsFromSameCoach_batchesNameLookup() {
        UUID sameCoachId = UUID.randomUUID();
        List<PerformanceReport> reports = List.of(
            readyReport(sameCoachId, "reports/1/report.pdf"),
            readyReport(sameCoachId, "reports/2/report.pdf"),
            readyReport(sameCoachId, "reports/3/report.pdf")
        );
        when(reportRepository.findByPlayerIdAndStatusOrderByGeneratedAtDesc(PLAYER_ID, ReportStatus.READY))
            .thenReturn(reports);
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
        assertThatThrownBy(() -> service.saveBranding(COACH_ID, COACH_USER_ID, request))
            .isInstanceOf(FeatureGatedException.class);
    }

    @Test
    void saveBranding_academyCoach_upsertsBranding() {
        when(coachProfileService.getCoachSubscriptionTier(COACH_ID)).thenReturn(CoachSubscriptionTier.ACADEMY);
        when(brandingRepository.findById(COACH_ID)).thenReturn(Optional.empty());

        var request = new com.softropic.skillars.platform.development.contract.CoachBrandingRequest(
            "logos/test.png", "#3B82F6");
        service.saveBranding(COACH_ID, COACH_USER_ID, request);

        ArgumentCaptor<CoachBranding> captor = ArgumentCaptor.forClass(CoachBranding.class);
        verify(brandingRepository).save(captor.capture());
        assertThat(captor.getValue().getCoachId()).isEqualTo(COACH_ID);
        assertThat(captor.getValue().getLogoKey()).isEqualTo("logos/test.png");
        assertThat(captor.getValue().getBrandColour()).isEqualTo("#3B82F6");
    }

    // ---- skillars-deferred-151 AC3: saveBranding's ownership check, exercised against a REAL
    // FileStorageService (not the full mock every other test in this file uses), the gap that let
    // the id-space bug (CoachProfile UUID vs. the caller's own numeric user id) ship untested. Only
    // fileStorageObjectRepository is mocked here — assertOwnership's one real dependency — with a
    // FileStorageObject fixture shaped exactly like what signUpload/confirmUpload actually stamp.
    //
    // Mutation check: revert CoachBrandingResource/ReportGenerationService to compare
    // `coachId.toString()` instead of `coachUserId.toString()` and
    // saveBranding_realFileStorageService_ownershipCheckPassesForCallersOwnUserId goes RED
    // (AuthorizationException), since COACH_ID (a UUID) can never equal the fixture's numeric
    // ownerId.
    @Test
    void saveBranding_realFileStorageService_ownershipCheckPassesForCallersOwnUserId() {
        com.softropic.skillars.platform.filestorage.repo.FileStorageObjectRepository realRepoDeps =
            org.mockito.Mockito.mock(com.softropic.skillars.platform.filestorage.repo.FileStorageObjectRepository.class);
        com.softropic.skillars.platform.filestorage.repo.FileStorageObject fixture =
            com.softropic.skillars.platform.filestorage.repo.FileStorageObject.builder()
                .key("coach_branding/logo.png")
                .ownerId(COACH_USER_ID.toString())
                .provider("s3")
                .bucket("test-bucket")
                .sizeBytes(1024L)
                .build();
        when(realRepoDeps.findByKey("coach_branding/logo.png")).thenReturn(Optional.of(fixture));

        com.softropic.skillars.platform.filestorage.service.FileStorageService realFileStorageService =
            new com.softropic.skillars.platform.filestorage.service.FileStorageService(
                org.mockito.Mockito.mock(com.softropic.skillars.platform.filestorage.service.ValidationChain.class),
                realRepoDeps,
                org.mockito.Mockito.mock(com.softropic.skillars.platform.filestorage.service.StorageKeyGenerator.class),
                org.mockito.Mockito.mock(software.amazon.awssdk.services.s3.presigner.S3Presigner.class),
                org.mockito.Mockito.mock(com.softropic.skillars.infrastructure.blobstore.config.BlobstoreProperties.class),
                org.mockito.Mockito.mock(com.softropic.skillars.platform.filestorage.config.FileStorageProperties.class),
                org.mockito.Mockito.mock(software.amazon.awssdk.services.s3.S3Client.class),
                org.mockito.Mockito.mock(com.softropic.skillars.platform.filestorage.repo.OutboxReplicationJobRepository.class),
                org.mockito.Mockito.mock(ApplicationEventPublisher.class),
                org.mockito.Mockito.mock(org.springframework.transaction.support.TransactionTemplate.class),
                org.mockito.Mockito.mock(com.softropic.skillars.platform.filestorage.repo.StorageAccessEventRepository.class),
                org.mockito.Mockito.mock(com.softropic.skillars.infrastructure.blobstore.service.StorageMetrics.class),
                org.mockito.Mockito.mock(SecurityUtil.class));

        ReportGenerationService realService = new ReportGenerationService(
            coachProfileService, playerProfileService,
            sluRepository, coachPlayerLastSessionResolver,
            skillDefinitionRepository, compositeRepository, baselineRepository,
            reportRepository, brandingRepository, realFileStorageService,
            timelineEventListener, publisher, coachPlayerAuthorizationService,
            rateLimitingService, configService, securityUtil, playerProfileRepository
        );
        ReflectionTestUtils.setField(realService, "baseUrl", "http://app.test");

        when(coachProfileService.getCoachSubscriptionTier(COACH_ID)).thenReturn(CoachSubscriptionTier.ACADEMY);
        when(brandingRepository.findById(COACH_ID)).thenReturn(Optional.empty());

        var request = new com.softropic.skillars.platform.development.contract.CoachBrandingRequest(
            "coach_branding/logo.png", "#3B82F6");

        assertThatCode(() -> realService.saveBranding(COACH_ID, COACH_USER_ID, request))
            .doesNotThrowAnyException();
    }

    // ---- getBranding (Deferred-80 AC3) ----

    @Test
    void getBranding_academyTierWithSavedBranding_returnsSignedUrlAndColour() {
        when(coachProfileService.getCoachSubscriptionTier(COACH_ID)).thenReturn(CoachSubscriptionTier.ACADEMY);
        CoachBranding branding = new CoachBranding();
        branding.setCoachId(COACH_ID);
        branding.setLogoKey("logos/test.png");
        branding.setBrandColour("#3B82F6");
        when(brandingRepository.findById(COACH_ID)).thenReturn(Optional.of(branding));
        when(fileStorageService.signedDownloadUrl("logos/test.png")).thenReturn("https://cdn.test/logos/test.png");

        var response = service.getBranding(COACH_ID);

        assertThat(response.logoSignedUrl()).isEqualTo("https://cdn.test/logos/test.png");
        assertThat(response.logoKey()).isEqualTo("logos/test.png");
        assertThat(response.brandColour()).isEqualTo("#3B82F6");
    }

    @Test
    void getBranding_downgradedTierWithExistingRow_returnsEmptyBrandingWithoutQueryingRepository() {
        when(coachProfileService.getCoachSubscriptionTier(COACH_ID)).thenReturn(CoachSubscriptionTier.INSTRUCTOR);

        var response = service.getBranding(COACH_ID);

        assertThat(response.logoSignedUrl()).isNull();
        assertThat(response.brandColour()).isNull();
        verify(brandingRepository, never()).findById(any());
    }

    @Test
    void getBranding_academyTierNoSavedBranding_returnsEmptyBranding() {
        when(coachProfileService.getCoachSubscriptionTier(COACH_ID)).thenReturn(CoachSubscriptionTier.ACADEMY);
        when(brandingRepository.findById(COACH_ID)).thenReturn(Optional.empty());

        var response = service.getBranding(COACH_ID);

        assertThat(response.logoSignedUrl()).isNull();
        assertThat(response.brandColour()).isNull();
    }

    private PerformanceReport readyReport(UUID coachId, String storageKey) {
        PerformanceReport r = new PerformanceReport();
        r.setId(UUID.randomUUID());
        r.setCoachId(coachId);
        r.setPlayerId(PLAYER_ID);
        r.setGeneratedAt(Instant.now());
        r.setStorageKey(storageKey);
        r.setNextSteps("test");
        r.setStatus(ReportStatus.READY);
        return r;
    }
}
