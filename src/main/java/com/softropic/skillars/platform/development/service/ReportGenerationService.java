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
import com.softropic.skillars.platform.development.contract.CoachBrandingRequest;
import com.softropic.skillars.platform.development.contract.CoachBrandingResponse;
import com.softropic.skillars.platform.development.contract.PerformanceReportResponse;
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
import com.softropic.skillars.platform.marketplace.contract.CoachSubscriptionTier;
import com.softropic.skillars.platform.marketplace.contract.CoachProfileDto;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.marketplace.service.PlayerProfileService;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.security.contract.exception.FeatureGatedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final SkillDefinitionRepository skillDefinitionRepository;
    private final PlayerRadarCompositeRepository compositeRepository;
    private final PlayerRadarBaselineRepository baselineRepository;
    private final PerformanceReportRepository reportRepository;
    private final CoachBrandingRepository brandingRepository;
    private final FileStorageService fileStorageService;
    private final TimelineEventListener timelineEventListener;
    private final ApplicationEventPublisher publisher;

    @Value("${baseurl}")
    private String baseUrl;

    @Transactional
    public void generateReport(Long coachUserId, Long playerId, String nextSteps) {
        UUID coachId = coachProfileService.getCoachIdByUserId(coachUserId);

        CoachSubscriptionTier tier = coachProfileService.getCoachSubscriptionTier(coachId);
        if (!REPORT_ALLOWED_TIERS.contains(tier)) {
            throw new FeatureGatedException("development.report", "INSTRUCTOR");
        }

        // Coach-player relationship guard
        Instant lastSession = sluRepository.findLastSessionDate(playerId, coachId);
        if (lastSession == null) {
            throw new AccessDeniedException("Coach has no session history with this player");
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

        String storageKey = "reports/" + UUID.randomUUID() + "/report.pdf";
        fileStorageService.storeBytes(pdfBytes, storageKey, "application/pdf",
            "attachment; filename=\"performance-report.pdf\"");

        PerformanceReport report = new PerformanceReport();
        report.setCoachId(coachId);
        report.setPlayerId(playerId);
        report.setGeneratedAt(generatedAt);
        report.setStorageKey(storageKey);
        report.setNextSteps(nextSteps);
        report.setVersion(1);
        PerformanceReport persisted;
        try {
            persisted = reportRepository.saveAndFlush(report);
        } catch (Exception e) {
            log.error("Failed to persist report record — cleaning up orphan PDF: key={}", storageKey, e);
            try {
                fileStorageService.deleteRawBytes(storageKey);
            } catch (Exception ex) {
                log.error("S3 orphan cleanup also failed: key={}", storageKey, ex);
            }
            throw e;
        }

        try {
            timelineEventListener.writeTimelineEvent(
                playerId, PlayerTimelineEventType.PERFORMANCE_REPORT,
                persisted.getId(), "development",
                Map.of("coachName", coachName, "reportId", persisted.getId().toString())
            );
        } catch (Exception e) {
            log.error("Failed to write PERFORMANCE_REPORT timeline event: playerId={}, reportId={}",
                playerId, persisted.getId(), e);
        }

        notifyParent(playerId, playerName, coachName, report.getGeneratedAt());
    }

    public List<PerformanceReportResponse> listReports(Long playerId) {
        List<PerformanceReport> reports = reportRepository.findByPlayerIdOrderByGeneratedAtDesc(playerId);
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
        Optional<CoachBranding> b = brandingRepository.findById(coachId);
        String logoUrl = b.flatMap(br -> Optional.ofNullable(br.getLogoKey()))
            .map(fileStorageService::signedDownloadUrl).orElse(null);
        String colour = b.map(CoachBranding::getBrandColour).orElse(null);
        return new CoachBrandingResponse(logoUrl, colour);
    }

    @Transactional
    public void saveBranding(UUID coachId, CoachBrandingRequest request) {
        CoachSubscriptionTier tier = coachProfileService.getCoachSubscriptionTier(coachId);
        if (tier != CoachSubscriptionTier.ACADEMY) {
            throw new FeatureGatedException("development.branding", "ACADEMY");
        }
        if (request.logoKey() != null) {
            fileStorageService.assertOwnership(request.logoKey(), coachId.toString());
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
