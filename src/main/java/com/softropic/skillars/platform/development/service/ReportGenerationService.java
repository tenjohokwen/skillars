package com.softropic.skillars.platform.development.service;

import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.security.contract.exception.LoginRateLimitedException;
import com.softropic.skillars.platform.development.contract.CoachBrandingRequest;
import com.softropic.skillars.platform.development.contract.CoachBrandingResponse;
import com.softropic.skillars.platform.development.contract.PerformanceReportResponse;
import com.softropic.skillars.platform.development.contract.PlayerTimelineEventType;
import com.softropic.skillars.platform.development.contract.ReportGeneratedEvent;
import com.softropic.skillars.platform.development.contract.ReportStatus;
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
import com.softropic.skillars.platform.marketplace.contract.CoachSubscriptionTier;
import com.softropic.skillars.platform.marketplace.contract.CoachProfileDto;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.marketplace.service.PlayerProfileService;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.security.contract.exception.FeatureGatedException;
import com.softropic.skillars.platform.security.contract.util.AuthoritiesConstants;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.infrastructure.security.RateLimited;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReportGenerationService {

    private static final Set<CoachSubscriptionTier> REPORT_ALLOWED_TIERS =
        EnumSet.of(CoachSubscriptionTier.INSTRUCTOR, CoachSubscriptionTier.ACADEMY);

    private final CoachProfileService coachProfileService;
    private final PlayerProfileService playerProfileService;
    private final SluRepository sluRepository;
    private final CoachPlayerLastSessionResolver coachPlayerLastSessionResolver;
    private final SkillDefinitionRepository skillDefinitionRepository;
    private final PlayerRadarCompositeRepository compositeRepository;
    private final PlayerRadarBaselineRepository baselineRepository;
    private final PerformanceReportRepository reportRepository;
    private final CoachBrandingRepository brandingRepository;
    private final FileStorageService fileStorageService;
    private final TimelineEventListener timelineEventListener;
    private final ApplicationEventPublisher publisher;
    private final CoachPlayerAuthorizationService coachPlayerAuthorizationService;
    private final com.softropic.skillars.infrastructure.security.RateLimitingService rateLimitingService;
    private final ConfigService configService;
    private final SecurityUtil securityUtil;
    private final PlayerProfileRepository playerProfileRepository;

    @Value("${baseurl}")
    private String baseUrl;

    /**
     * Per-coach report-generation budget (skillars-deferred-92 AC18), enforced <em>in addition to</em>
     * the {@code @RateLimited} annotation below rather than instead of it.
     *
     * <p>{@code RateLimitingAspect} keys its bucket on the <strong>client IP</strong>, and collapses
     * to the single literal bucket {@code "report_generate:unknown"} whenever the IP cannot be
     * resolved ({@code RateLimitingAspect:80-81}). Two consequences, both live: every coach behind one
     * office or school NAT shares a single 10/minute budget, and every caller whose IP is
     * unresolvable shares one global bucket with every other such caller.
     *
     * <p>The fix mirrors {@code RegistrationOtpResendSupport.resendPhoneOtp} (skillars-deferred-89
     * AC7) exactly: an explicit per-user {@code tryConsume} inside the service.
     * {@code RateLimitingAspect}'s keying strategy is deliberately <strong>not</strong> changed — it
     * is a shared aspect with a much wider blast radius, which is why the ledger deferred that and
     * why this AC did not revisit it.
     *
     * <p>Both limits apply. The per-coach one is the meaningful bound; the IP one stays as the
     * anti-abuse floor for unauthenticated flooding.
     */
    private static final long PER_COACH_REPORTS = 10;
    private static final long PER_COACH_WINDOW_MINUTES = 1;
    /** Bucket name, kept distinct from the aspect's {@code report_generate} so the two cannot merge. */
    static final String PER_COACH_BUCKET = "report_generate_user";

    @Transactional
    @RateLimited(key = "report_generate", capacity = 10, duration = 1, unit = TimeUnit.MINUTES)
    public void generateReport(Long coachUserId, Long playerId, String nextSteps) {
        // Before any authorization or DB work, as in VideoService.initiateUpload: a rejected request
        // must cost as little as possible.
        if (!rateLimitingService.tryConsume(String.valueOf(coachUserId), PER_COACH_BUCKET,
                PER_COACH_REPORTS, PER_COACH_WINDOW_MINUTES, TimeUnit.MINUTES)) {
            throw new LoginRateLimitedException(
                "Report generation limit reached for this coach",
                java.util.Map.of("coachUserId", coachUserId, "bucket", PER_COACH_BUCKET),
                TimeUnit.MINUTES.toSeconds(PER_COACH_WINDOW_MINUTES));
        }
        coachPlayerAuthorizationService.requireCoachPlayerRelationship(coachUserId, playerId);
        UUID coachId = coachProfileService.getCoachIdByUserId(coachUserId);

        CoachSubscriptionTier tier = coachProfileService.getCoachSubscriptionTier(coachId);
        if (!REPORT_ALLOWED_TIERS.contains(tier)) {
            throw new FeatureGatedException("development.report", "INSTRUCTOR");
        }

        // Coach-player relationship guard. skillars-deferred-152 AC3: was the SLU-only
        // sluRepository.findLastSessionDate signal, the third and final of three independently-
        // discovered instances of that same narrow gate (CoachRosterService/TimelineQueryService/
        // SessionPrepService already use the merged resolver below) -- now widened to match. This
        // is a real, bounded authorization widening, not a pure mechanical swap: buildSkillEntries/
        // sumTotalSluByPlayerId/countDistinctSessions below are keyed by playerId alone (the
        // player's entire cross-coach SLU history), not scoped to this coach, so a newly-permitted
        // Quick-Complete-only coach's report now also shows that wider aggregate. Bounded severity:
        // requireCoachPlayerRelationship above already requires an active booking relationship;
        // this is a second, narrower layer being relaxed, not the only protection.
        Instant lastSession = coachPlayerLastSessionResolver.resolve(playerId, coachId).lastSessionDate();
        if (lastSession == null) {
            throw new AccessDeniedException("Coach has no session history with this player");
        }
        // skillars-deferred-153 AC7: report generation now shares TimelineQueryService's own
        // recency policy — a coach whose last session with this player predates the same
        // development.timeline.coachAccessExpiryDays window the timeline already refuses is
        // refused a report the same way, instead of accepting an arbitrarily stale signal.
        // Duplicated rather than extracted into a shared helper: TimelineQueryService still needs
        // the raw expiryDays value to populate TimelineResponse.accessExpiryDays, so centralizing
        // the read elsewhere would not remove a dependency from either call site.
        long expiryDays;
        try {
            expiryDays = configService.getBoundedLong("development.timeline.coachAccessExpiryDays", 1L, 3650L);
        } catch (Exception e) {
            log.warn("Config key 'development.timeline.coachAccessExpiryDays' missing — defaulting to 90 days", e);
            expiryDays = 90L;
        }
        if (lastSession.isBefore(Instant.now().minus(expiryDays, java.time.temporal.ChronoUnit.DAYS))) {
            throw new AccessDeniedException("Coach's last session with this player predates the access window");
        }

        CoachProfileDto coach = coachProfileService.getPublicProfile(coachId);
        String coachName = coach.displayName();

        String playerName = playerProfileService.getPlayerNameByPlayerId(playerId);
        int playerAge = playerProfileService.getPlayerAgeByPlayerId(playerId);

        List<SkillRadarEntry> skills = buildSkillEntries(playerId);
        BigDecimal totalSlu = sluRepository.sumTotalSluByPlayerId(playerId);
        Long sessionCount = sluRepository.countDistinctSessions(playerId);

        Optional<CoachBranding> branding = tier == CoachSubscriptionTier.ACADEMY
            ? brandingRepository.findById(coachId)
            : Optional.empty();

        Instant generatedAt = Instant.now();
        byte[] pdfBytes = buildPdf(coach, coachName, playerName, playerAge, skills,
            totalSlu, sessionCount, nextSteps, branding, generatedAt);

        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new RuntimeException("PDF generation produced empty output");
        }

        // Deferred-77 AC2: the report row is saved PENDING_UPLOAD, with no storage_key yet — the S3
        // upload (external I/O) moves to an async post-commit handler so it no longer holds this
        // method's DB connection for its duration. listReports only ever returns READY reports, so a
        // still-uploading or failed-upload row is never handed out as a signed URL to a PDF that
        // doesn't exist.
        PerformanceReport report = new PerformanceReport();
        report.setCoachId(coachId);
        report.setPlayerId(playerId);
        report.setGeneratedAt(generatedAt);
        report.setNextSteps(nextSteps);
        report.setVersion(1);
        report.setStatus(ReportStatus.PENDING_UPLOAD);
        PerformanceReport persisted = reportRepository.saveAndFlush(report);

        publisher.publishEvent(new ReportGeneratedEvent(
            persisted.getId(), playerId, coachName, playerName, generatedAt, pdfBytes));
    }

    /**
     * Uploads the already-built PDF to S3 and, only once that succeeds, flips the report to READY,
     * writes its timeline event, and notifies the parent — none of which should happen for a report
     * whose PDF never made it to S3. On failure the report is marked UPLOAD_FAILED and stays hidden
     * from listReports; there is currently no automatic retry (matches this story's scope).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    // skillars-deferred-92 code review (D2): its own pool, not the shared @Async one. The upload
    // below is a full S3 round trip with no outbox row and no retry behind it, and taskExecutor's
    // 2s shutdown slice is sized for tasks that are never individually long-running.
    @Async("reportExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onReportGenerated(ReportGeneratedEvent event) {
        String storageKey = "reports/" + UUID.randomUUID() + "/report.pdf";
        try {
            fileStorageService.storeBytes(event.pdfBytes(), storageKey, "application/pdf",
                "attachment; filename=\"performance-report.pdf\"");
            reportRepository.updateStatusAndStorageKey(event.reportId(), ReportStatus.READY, storageKey);
        } catch (Exception e) {
            log.error("Failed to upload PDF for report {} — marking UPLOAD_FAILED", event.reportId(), e);
            reportRepository.updateStatusAndStorageKey(event.reportId(), ReportStatus.UPLOAD_FAILED, null);
            return;
        }

        try {
            timelineEventListener.writeTimelineEvent(
                event.playerId(), PlayerTimelineEventType.PERFORMANCE_REPORT,
                event.reportId(), "development",
                Map.of("coachName", event.coachName(), "reportId", event.reportId().toString())
            );
        } catch (Exception e) {
            log.error("Failed to write PERFORMANCE_REPORT timeline event: playerId={}, reportId={}",
                event.playerId(), event.reportId(), e);
        }

        notifyParent(event.playerId(), event.playerName(), event.coachName(), event.generatedAt());
    }

    public List<PerformanceReportResponse> listReports(Long playerId) {
        coachPlayerAuthorizationService.requireCoachPlayerRelationshipIfCoach(playerId);
        List<PerformanceReport> reports =
            reportRepository.findByPlayerIdAndStatusOrderByGeneratedAtDesc(playerId, ReportStatus.READY);

        // skillars-deferred-154 AC1: requireCoachPlayerRelationshipIfCoach above only answers "may
        // this coach see ANY report for this player" (widened by skillars-deferred-153 AC5/AC6 to
        // READ_RELATIONSHIP_STATUSES, matching the roster's own membership width) — it does not
        // scope WHICH reports, so without this filter a coach whose only relationship with this
        // player is e.g. DISPUTED could read every READY report ever generated by every coach. Mirrors
        // requireCoachPlayerRelationshipIfCoach's own condition shape exactly, including its
        // self-owner escape arm, so a coach who is also this player's own parent/self-owner (not
        // reachable today, but defended against by that same method) is not filtered either. A
        // parent/player caller skips this block entirely and keeps seeing every coach's reports.
        //
        // (code review 2026-10-17) The role check MUST come first and gate everything else, which is
        // what makes the sentence above actually true. An earlier shape computed currentUserId and both
        // ownership exists-checks unconditionally and only then tested the role, which broke two things:
        //   - securityUtil.getCurrentCoachUserId() THROWS for a caller it cannot resolve — it has no
        //     null-returning branch (IllegalStateException("User not found!") via getCurrentUser() when
        //     there is no SecurityContext, InsufficientAuthenticationException on a blank/non-numeric
        //     businessId). ReportGenerationServiceIT calls this method directly on the test thread with
        //     no SecurityContext at all, and passed only because requireCoachPlayerRelationshipIfCoach
        //     short-circuits on isCurrentUserInRole (which null-checks and returns false). Resolving the
        //     principal before the role check turned that IT red.
        //   - a parent/player caller paid up to two DB round-trips whose result was then discarded —
        //     on top of the identical pair PlayerOwnershipGuard.check already ran in @PreAuthorize.
        // CoachPlayerAuthorizationService.requireCoachPlayerRelationshipIfCoach puts the role test first
        // for exactly this reason; "mirrors its condition shape exactly" has to include its ordering.
        if (securityUtil.isCurrentUserInRole(AuthoritiesConstants.COACH)) {
            Long currentUserId = securityUtil.getCurrentCoachUserId();
            boolean isCoachOwner = playerProfileRepository.existsByIdAndParentId(playerId, currentUserId)
                    || playerProfileRepository.existsByIdAndUserId(playerId, currentUserId);
            if (!isCoachOwner) {
                UUID currentCoachId = coachProfileService.getCoachIdByUserId(currentUserId);
                reports = reports.stream().filter(r -> r.getCoachId().equals(currentCoachId)).toList();
            }
        }

        Set<UUID> coachIds = reports.stream().map(PerformanceReport::getCoachId).collect(Collectors.toSet());
        Map<UUID, String> coachNames = coachProfileService.getDisplayNamesByIds(coachIds);
        return reports.stream()
            .map(r -> {
                String url = null;
                try {
                    url = fileStorageService.signedDownloadUrl(r.getStorageKey());
                } catch (Exception e) {
                    log.warn("Failed to sign download URL for report {}: {}", r.getId(), e.getMessage());
                }
                return new PerformanceReportResponse(
                    r.getId(),
                    coachNames.getOrDefault(r.getCoachId(), "Unknown Coach"),
                    r.getGeneratedAt(),
                    url);
            })
            .toList();
    }

    public CoachBrandingResponse getBranding(UUID coachId) {
        // Deferred-80 AC3: mirrors saveBranding's own tier gate on the identical row, so a coach who
        // downgrades from ACADEMY stops seeing their old logo/colour as "still set" on this
        // settings-view GET. Unlike saveBranding's write-attempt gate, this does NOT throw
        // FeatureGatedException — a settings-page load must not hard-error for every downgraded
        // coach forever; it renders as if branding was never set instead.
        CoachSubscriptionTier tier = coachProfileService.getCoachSubscriptionTier(coachId);
        if (tier != CoachSubscriptionTier.ACADEMY) {
            return new CoachBrandingResponse(null, null, null);
        }
        Optional<CoachBranding> b = brandingRepository.findById(coachId);
        String logoKey = b.map(CoachBranding::getLogoKey).orElse(null);
        String logoUrl = Optional.ofNullable(logoKey)
            .map(fileStorageService::signedDownloadUrl).orElse(null);
        String colour = b.map(CoachBranding::getBrandColour).orElse(null);
        return new CoachBrandingResponse(logoUrl, logoKey, colour);
    }

    @Transactional
    public void saveBranding(UUID coachId, Long coachUserId, CoachBrandingRequest request) {
        CoachSubscriptionTier tier = coachProfileService.getCoachSubscriptionTier(coachId);
        if (tier != CoachSubscriptionTier.ACADEMY) {
            throw new FeatureGatedException("development.branding", "ACADEMY");
        }
        if (request.logoKey() != null) {
            fileStorageService.assertOwnership(request.logoKey(), coachUserId.toString());
        }
        CoachBranding b = brandingRepository.findById(coachId).orElseGet(CoachBranding::new);
        b.setCoachId(coachId);
        b.setLogoKey(request.logoKey());
        b.setBrandColour(request.brandColour());
        b.setUpdatedAt(Instant.now());
        brandingRepository.save(b);
    }

    private List<SkillRadarEntry> buildSkillEntries(Long playerId) {
        var compositeMap = compositeRepository.findByIdPlayerId(playerId).stream()
            .collect(Collectors.toMap(c -> c.getId().getSkillCode(), c -> c));
        var baselineMap = baselineRepository.findByIdPlayerId(playerId).stream()
            .collect(Collectors.toMap(b -> b.getId().getSkillCode(), b -> b));

        return skillDefinitionRepository.findAllByActiveTrueOrderByDisplayOrderAsc().stream()
            .map(def -> {
                var comp = compositeMap.get(def.getCode());
                var base = baselineMap.get(def.getCode());
                return new SkillRadarEntry(
                    def.getCode(),
                    def.getDisplayName(),
                    comp != null ? comp.getCompositeScore() : null,
                    base != null ? base.getBaselineScore() : null,
                    comp != null ? comp.getEntryCount() : null,
                    comp != null ? comp.getDistinctCoachCount() : null,
                    comp != null ? comp.getLastUpdatedAt() : null
                );
            })
            .filter(e -> e.compositeScore() != null)
            .toList();
    }

    private void notifyParent(Long playerId, String playerName, String coachName, Instant generatedAt) {
        try {
            String parentEmail = playerProfileService.getParentEmailByPlayerId(playerId);
            if (parentEmail == null || parentEmail.isBlank()) {
                log.warn("Parent email not found for playerId={} — report notification skipped", playerId);
                return;
            }

            String reportsPageUrl = baseUrl + "/portal/players/" + playerId + "/reports";
            String reportDate = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
                .withLocale(Locale.ENGLISH)
                .format(generatedAt.atZone(ZoneOffset.UTC).toLocalDate());

            Map<String, Object> data = new HashMap<>();
            data.put("coachName", coachName);
            data.put("playerName", playerName);
            data.put("reportsPageUrl", reportsPageUrl);
            data.put("reportDate", reportDate);

            Recipient recipient = new Recipient();
            recipient.setEmail(parentEmail);
            recipient.setLangKey("en");

            publisher.publishEvent(new Envelope(
                List.of(recipient),
                EmailTemplate.PERFORMANCE_REPORT_SHARED,
                Instant.now().plus(Duration.ofHours(48)),
                data,
                UUID.randomUUID().toString()
            ));
        } catch (Exception e) {
            log.error("Failed to notify parent for playerId={}", playerId, e);
        }
    }

    private byte[] buildPdf(CoachProfileDto coach, String coachName, String playerName,
                             int playerAge, List<SkillRadarEntry> skills, BigDecimal totalSlu,
                             Long sessionCount, String nextSteps,
                             Optional<CoachBranding> branding, Instant generatedAt) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4);
        try {
            PdfWriter.getInstance(doc, out);
            doc.open();

            Font headerFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18);
            Font sectionFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);
            Font normalFont = FontFactory.getFont(FontFactory.HELVETICA, 10);
            Font tableHeaderFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);

            // Header — Academy branding or Skillars default
            boolean hasLogo = branding.isPresent() && branding.get().getLogoKey() != null;
            if (hasLogo) {
                try {
                    byte[] logoBytes = fileStorageService.downloadBytes(branding.get().getLogoKey());
                    Image logo = Image.getInstance(logoBytes);
                    logo.scaleToFit(200, 80);
                    logo.setAlignment(Image.LEFT);
                    doc.add(logo);
                } catch (Exception e) {
                    log.warn("Failed to embed logo for coach {} — using text header", coach.id(), e);
                    doc.add(new Paragraph("Skillars Performance Report", headerFont));
                }
                if (branding.get().getBrandColour() != null) {
                    try {
                        Color brandColor = Color.decode(branding.get().getBrandColour());
                        PdfPTable colorBar = new PdfPTable(1);
                        colorBar.setWidthPercentage(100);
                        PdfPCell colorCell = new PdfPCell(new Phrase(" "));
                        colorCell.setBackgroundColor(brandColor);
                        colorCell.setFixedHeight(6f);
                        colorCell.setBorder(PdfPCell.NO_BORDER);
                        colorBar.addCell(colorCell);
                        doc.add(colorBar);
                    } catch (Exception e) {
                        log.warn("Failed to render brand colour bar for coach {}", coach.id(), e);
                    }
                }
            } else {
                doc.add(new Paragraph("Skillars Performance Report", headerFont));
            }

            doc.add(new Paragraph(" "));
            doc.add(new Paragraph("Player: " + playerName + " (Age " + playerAge + ")", normalFont));

            String reportDate = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
                .withLocale(Locale.ENGLISH)
                .format(generatedAt.atZone(ZoneOffset.UTC).toLocalDate());
            doc.add(new Paragraph("Report Date: " + reportDate, normalFont));
            doc.add(new Paragraph(" "));

            // Radar chart
            if (!skills.isEmpty()) {
                try {
                    byte[] chartPng = SkillsRadarChartRenderer.renderToPng(skills, 400);
                    Image chartImg = Image.getInstance(chartPng);
                    chartImg.scaleToFit(400, 400);
                    chartImg.setAlignment(Image.MIDDLE);
                    doc.add(chartImg);
                } catch (Exception e) {
                    log.warn("Failed to render radar chart for report — skipping chart", e);
                }
            }

            doc.add(new Paragraph(" "));

            // Skill score table
            PdfPTable table = new PdfPTable(4);
            table.setWidthPercentage(100);
            for (String h : new String[]{"Skill", "Baseline", "Current", "Improvement"}) {
                PdfPCell cell = new PdfPCell(new Phrase(h, tableHeaderFont));
                cell.setBackgroundColor(new Color(230, 230, 230));
                table.addCell(cell);
            }
            for (SkillRadarEntry s : skills) {
                table.addCell(new PdfPCell(new Phrase(
                    s.displayName() != null ? s.displayName() : s.skillCode(), normalFont)));
                table.addCell(new PdfPCell(new Phrase(
                    s.baselineScore() != null ? s.baselineScore().toPlainString() : "—", normalFont)));
                table.addCell(new PdfPCell(new Phrase(
                    s.compositeScore() != null ? s.compositeScore().toPlainString() : "—", normalFont)));
                String improvement = "—";
                if (s.compositeScore() != null && s.baselineScore() != null) {
                    if (s.baselineScore().compareTo(BigDecimal.ZERO) != 0) {
                        BigDecimal delta = s.compositeScore().subtract(s.baselineScore());
                        BigDecimal pct = delta.divide(s.baselineScore(), 1, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100));
                        improvement = (pct.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "") + pct.toPlainString() + "%";
                    } else {
                        BigDecimal delta = s.compositeScore().subtract(s.baselineScore());
                        improvement = (delta.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "") + delta.toPlainString();
                    }
                }
                table.addCell(new PdfPCell(new Phrase(improvement, normalFont)));
            }
            doc.add(table);
            doc.add(new Paragraph(" "));

            doc.add(new Paragraph("Sessions: " + (sessionCount != null ? sessionCount : 0) +
                " | Total SLU: " + (totalSlu != null ? totalSlu.toPlainString() : "0"), normalFont));
            doc.add(new Paragraph(" "));

            doc.add(new Paragraph("Next Steps", sectionFont));
            doc.add(new Paragraph(nextSteps, normalFont));
            doc.add(new Paragraph(" "));

            String verificationNote = coach.verificationTier() != null && !coach.verificationTier().isBlank()
                ? " ✓ Verified" : "";
            doc.add(new Paragraph("Prepared by: " + coachName + verificationNote, normalFont));

        } catch (Exception e) {
            log.error("Failed to build PDF for coach={}", coach.id(), e);
            throw new RuntimeException("PDF generation failed", e);
        } finally {
            if (doc.isOpen()) {
                doc.close();
            }
        }
        return out.toByteArray();
    }
}
