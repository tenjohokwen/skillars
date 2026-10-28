package com.softropic.skillars.platform.development.service;

import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.softropic.skillars.infrastructure.web.FrontendLinkBuilder;
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
import com.softropic.skillars.platform.development.repo.PlayerTimelineEvent;
import com.softropic.skillars.platform.development.repo.PlayerTimelineRepository;
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
    private final CoachAccessWindow coachAccessWindow;
    private final SecurityUtil securityUtil;
    private final PlayerProfileRepository playerProfileRepository;
    private final PlayerTimelineRepository playerTimelineRepository;

    private final FrontendLinkBuilder frontendLinks;

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
    /**
     * skillars-deferred-155 code review, Decision 3(a): caps the "Session Notes" section. AC1 originally
     * specified full history with no bound, which reintroduced the unbounded-{@code List} shape
     * {@code skillars-deferred-153} AC4 had just removed from {@link PlayerTimelineRepository}'s sibling
     * query — one row per completed session, up to 2x500 chars each, hydrated as entities and rendered
     * into a single in-memory PDF {@code byte[]} carried through a Spring event, behind a 10/min
     * per-coach rate limit. 20 is a purpose-named constant rather than a reuse of
     * {@code SessionRepository.SESSION_WINDOW} (10): that constant means "session-builder sessions for
     * focus-thread ranking" and coupling report content to it would tie two unrelated semantics
     * together. The PDF heading states the cap so the truncation is visible rather than silent.
     */
    static final int REPORT_SESSION_NOTES_LIMIT = 20;

    private static final long PER_COACH_REPORTS = 10;
    private static final long PER_COACH_WINDOW_MINUTES = 1;
    /** Bucket name, kept distinct from the aspect's {@code report_generate} so the two cannot merge. */
    static final String PER_COACH_BUCKET = "report_generate_user";

    // skillars-deferred-156 AC4: classpath-relative, resolved by openpdf's own
    // RandomAccessFileOrArray(String,...) -> BaseFont.getResourceStream(...) fallback (no leading
    // slash — ClassLoader.getResourceAsStream semantics) once the plain `new File(name)` check
    // fails, which it always does here since no such path exists on disk. SIL Open Font License
    // 1.1 (src/main/resources/fonts/NotoSans-OFL.txt), not Apache-2.0 — corrected from this story's
    // AC4 text during implementation: Noto Sans ships OFL, and OFL explicitly permits embedding in
    // generated documents (the embedding restriction applies to redistributing the font software
    // itself under a different license, not to documents a tool produces with it).
    private static final String NOTO_SANS_REGULAR_PATH = "fonts/NotoSans-Regular.ttf";
    private static final String NOTO_SANS_BOLD_PATH = "fonts/NotoSans-Bold.ttf";

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
        // skillars-deferred-153 AC7 / skillars-12-10 AC2: the shared coach-access window (attended-session
        // clock, no open-booking arm -- a booked-but-never-attended player gets no report, as before).
        if (!coachAccessWindow.isOpen(lastSession)) {
            throw new AccessDeniedException("Coach's last session with this player predates the access window");
        }

        CoachProfileDto coach = coachProfileService.getPublicProfile(coachId);
        String coachName = coach.displayName();

        String playerName = playerProfileService.getPlayerNameByPlayerId(playerId);
        int playerAge = playerProfileService.getPlayerAgeByPlayerId(playerId);

        List<SkillRadarEntry> skills = buildSkillEntries(playerId);
        BigDecimal totalSlu = sluRepository.sumTotalSluByPlayerId(playerId);
        Long sessionCount = sluRepository.countDistinctSessions(playerId);

        // skillars-deferred-155 AC1: verbatim, this-coach-scoped, full-history recap tie-in. Filtered
        // on metadata->>'coachId' inside the repository query, NOT the entity's attendance-gated
        // coachId column — see PlayerTimelineRepository.findSessionNotesForReport's own Javadoc.
        List<SessionNoteEntry> sessionNotes = playerTimelineRepository
            .findSessionNotesForReport(playerId, coachId, REPORT_SESSION_NOTES_LIMIT).stream()
            .map(this::toSessionNoteEntry)
            .toList();

        Optional<CoachBranding> branding = tier == CoachSubscriptionTier.ACADEMY
            ? brandingRepository.findById(coachId)
            : Optional.empty();

        Instant generatedAt = Instant.now();
        byte[] pdfBytes = buildPdf(coach, coachName, playerName, playerAge, skills,
            totalSlu, sessionCount, nextSteps, sessionNotes, branding, generatedAt);

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
        CoachBranding b = brandingRepository.findById(coachId).orElseGet(CoachBranding::new);
        // The settings dialog sends the current logoKey back on a colour-only save. Re-checking an UNCHANGED
        // browser-upload key would lock a coach out of branding once that logo object is soft-deleted, so only a
        // new key (or an unchanged key that is not a browser upload, i.e. a server-written report/GDPR object) is
        // checked. assertOwnership fails for keys with no live file_storage_objects row owned by this coach.
        if (request.logoKey() != null) {
            boolean unchangedUpload = request.logoKey().equals(b.getLogoKey())
                && fileStorageService.isClientUploadKey(request.logoKey());
            if (!unchangedUpload) {
                fileStorageService.assertOwnership(request.logoKey(), coachUserId.toString());
            }
        }
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

            // The recipient is the player's parent: their development portal hosts the reports panel.
            String reportsPageUrl = frontendLinks.route("parent/players/" + playerId + "/development");
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

    /** skillars-deferred-155 AC1: one coach-authored recap, extracted for report rendering. */
    private record SessionNoteEntry(Instant occurredAt, String whatWeDid, String whatsNext, String whatWentWell) {
    }

    /**
     * (code review of skillars-deferred-156) Makes an unrenderable-glyph drop OBSERVABLE rather than
     * silent. The vendored Noto Sans (latin-greek-cyrillic build) closes the originally-reported
     * cp1252 bug for Latin Extended-A / Cyrillic / Greek, but it does not cover CJK, Arabic, Hebrew,
     * Devanagari or emoji — and openpdf drops an uncovered code point with no exception and no log, so
     * a coach or player whose own name is in one of those scripts loses it from every report with
     * nothing anywhere recording that it happened. {@code CreatePlayerProfileRequest.name} is
     * {@code @NotBlank @Size(max = 100)} with no charset restriction, so this is reachable with
     * ordinary user input, not just adversarial input.
     *
     * <p>This does NOT fix the rendering — closing that properly needs additional vendored faces plus
     * per-character font fallback, which openpdf 2.0.5 has no built-in support for (ledgered as
     * deferred work). It converts a silent, invisible data loss into a warning an operator can
     * actually find, which is the cheap half of the fix and the half that stops the failure being
     * undetectable.
     *
     * <p>Iterates by code point, not {@code char}, so an astral-plane character (emoji, rarer CJK) is
     * tested as the single code point it is rather than as two unpaired surrogates that would both
     * report as missing.
     */
    private void warnOnUnrenderableGlyphs(BaseFont font, String label, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        String unrenderable = text.codePoints()
            .distinct()
            .filter(cp -> !Character.isWhitespace(cp))
            .filter(cp -> !font.charExists(cp))
            .mapToObj(cp -> "U+%04X (%s)".formatted(cp, new String(Character.toChars(cp))))
            .collect(Collectors.joining(", "));
        if (!unrenderable.isEmpty()) {
            log.warn("[REPORT_PDF] {} contains {} code point(s) the embedded font cannot render — "
                    + "they will be DROPPED from the generated PDF: {}",
                label, unrenderable.split(", ").length, unrenderable);
        }
    }

    private SessionNoteEntry toSessionNoteEntry(PlayerTimelineEvent event) {
        Map<String, Object> metadata = event.getMetadata();
        Object whatWeDid = metadata.get(TimelineEventListener.METADATA_KEY_WHAT_WE_DID);
        Object whatsNext = metadata.get(TimelineEventListener.METADATA_KEY_WHATS_NEXT);
        Object whatWentWell = metadata.get(TimelineEventListener.METADATA_KEY_WHAT_WENT_WELL);
        return new SessionNoteEntry(
            event.getOccurredAt(),
            whatWeDid != null ? whatWeDid.toString() : null,
            whatsNext != null ? whatsNext.toString() : null,
            whatWentWell != null ? whatWentWell.toString() : null);
    }

    private byte[] buildPdf(CoachProfileDto coach, String coachName, String playerName,
                             int playerAge, List<SkillRadarEntry> skills, BigDecimal totalSlu,
                             Long sessionCount, String nextSteps, List<SessionNoteEntry> sessionNotes,
                             Optional<CoachBranding> branding, Instant generatedAt) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4);
        try {
            PdfWriter.getInstance(doc, out);
            doc.open();

            // skillars-deferred-156 AC4: the base-14 Type1 fonts FontFactory built here are WinAnsi
            // (cp1252)-only and silently DROP any out-of-range glyph rather than substituting one —
            // the names/recap text a coach or player enters are not bounded to cp1252 (Polish,
            // Scandinavian, Cyrillic, Greek characters all fall outside it). A vendored, classpath-
            // embedded Noto Sans TTF (IDENTITY_H encoding, EMBEDDED) covers Latin Extended-A, Cyrillic
            // and Greek instead. (The originally-cited proof of this bug, a literal "✓" U+2713
            // Dingbat in the verification badge below, is a narrower, separate case this font does
            // NOT cover either — see that badge's own comment for why the fix there is different.)
            // BaseFont.createFont caches by
            // (name, encoding, embedded) — callers across this @Async("reportExecutor") pool share
            // one parsed BaseFont per weight; per-document glyph-subset tracking lives in openpdf's
            // own per-PdfWriter FontDetails wrapper, not on the shared BaseFont, so concurrent report
            // generation against the same cached BaseFont is safe. Real bold weight (not a
            // synthetic Font.BOLD over the Regular face), to avoid faux-bold on every header/section/
            // table heading.
            BaseFont regularBase = BaseFont.createFont(NOTO_SANS_REGULAR_PATH, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            BaseFont boldBase = BaseFont.createFont(NOTO_SANS_BOLD_PATH, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            Font headerFont = new Font(boldBase, 18, Font.NORMAL);
            Font sectionFont = new Font(boldBase, 12, Font.NORMAL);
            Font normalFont = new Font(regularBase, 10, Font.NORMAL);
            Font tableHeaderFont = new Font(boldBase, 10, Font.NORMAL);

            // (code review of skillars-deferred-156) Scan every user-authored string that reaches this
            // PDF, so a glyph this font cannot render is logged instead of vanishing silently. Checked
            // against regularBase only: both weights are the same Noto Sans latin-greek-cyrillic
            // family with identical coverage, and every user-authored string below renders in
            // normalFont. Report-chrome literals are not scanned — they are ASCII by construction.
            warnOnUnrenderableGlyphs(regularBase, "playerName", playerName);
            warnOnUnrenderableGlyphs(regularBase, "coachName", coachName);
            warnOnUnrenderableGlyphs(regularBase, "nextSteps", nextSteps);
            for (SessionNoteEntry note : sessionNotes) {
                warnOnUnrenderableGlyphs(regularBase, "sessionNote.whatWeDid", note.whatWeDid());
                warnOnUnrenderableGlyphs(regularBase, "sessionNote.whatsNext", note.whatsNext());
                warnOnUnrenderableGlyphs(regularBase, "sessionNote.whatWentWell", note.whatWentWell());
            }

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

            // skillars-deferred-155 AC2/AC3: omitted entirely when empty — no heading with nothing
            // beneath it (skillars-12-5 AC4's "no empty wrapper" discipline, applied here too).
            if (!sessionNotes.isEmpty()) {
                // skillars-deferred-155 code review, Decision 3(a): name the cap in the heading so the
                // truncation is visible to the parent reading the report rather than silent — but only
                // when the query actually came back full, so a player with three sessions is not told
                // they are seeing "the most recent 20". Hardcoded English like the rest of this PDF
                // body (AC2: no i18n reaches buildPdf).
                String sessionNotesHeading = sessionNotes.size() == REPORT_SESSION_NOTES_LIMIT
                    ? "Session Notes (most recent " + REPORT_SESSION_NOTES_LIMIT + " sessions)"
                    : "Session Notes";
                doc.add(new Paragraph(sessionNotesHeading, sectionFont));
                for (SessionNoteEntry note : sessionNotes) {
                    String noteDate = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
                        .withLocale(Locale.ENGLISH)
                        .format(note.occurredAt().atZone(ZoneOffset.UTC).toLocalDate());
                    doc.add(new Paragraph(noteDate, normalFont));
                    // Each field rendered independently — never concatenated. buildPdf has no null
                    // handling anywhere else, and Java string concatenation with a null reference
                    // renders the literal text "null" (caught by review, AC2/AC3).
                    if (note.whatWeDid() != null) {
                        doc.add(new Paragraph("What we did: " + note.whatWeDid(), normalFont));
                    }
                    if (note.whatsNext() != null) {
                        doc.add(new Paragraph("What's next: " + note.whatsNext(), normalFont));
                    }
                    if (note.whatWentWell() != null) {
                        doc.add(new Paragraph("What went well: " + note.whatWentWell(), normalFont));
                    }
                    doc.add(new Paragraph(" "));
                }
            }

            // skillars-deferred-156 AC4 (implementation correction): U+2713 is a Dingbat, not Latin
            // Extended/Cyrillic/Greek — the vendored Noto Sans (latin-greek-cyrillic) TTF this AC adds
            // does NOT cover it either (verified against the real font's cmap), so swapping fonts
            // alone does not fix this specific glyph; it would still silently vanish. The actual fix
            // for this one, app-authored badge is to stop depending on a glyph outside this font's
            // (or any reasonably-sized single font's) coverage, not to chase it with a second font —
            // openpdf has no built-in per-character font-fallback, and introducing one for a single
            // decorative badge is not worth the complexity this AC's real target (user-authored names
            // and recap text in Latin Extended/Cyrillic/Greek scripts) does not need.
            String verificationNote = coach.verificationTier() != null && !coach.verificationTier().isBlank()
                ? " (Verified)" : "";
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
