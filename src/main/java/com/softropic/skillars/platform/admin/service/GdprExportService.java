package com.softropic.skillars.platform.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.softropic.skillars.platform.config.service.ConfigBounds;
import com.softropic.skillars.platform.admin.contract.GdprExportReadyEvent;
import com.softropic.skillars.platform.admin.repo.GdprRequest;
import com.softropic.skillars.platform.admin.repo.GdprRequestRepository;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.development.repo.PlayerTimelineRepository;
import com.softropic.skillars.platform.development.repo.RadarAssessmentRepository;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.filestorage.service.FileStorageService;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.messaging.repo.MessageRepository;
import com.softropic.skillars.platform.payment.repo.BookingPaymentRepository;
import com.softropic.skillars.platform.payment.repo.ParentCreditLedgerRepository;
import com.softropic.skillars.platform.admin.repo.DisputeRepository;
import com.softropic.skillars.platform.reviews.repo.CoachReviewRepository;
import com.softropic.skillars.platform.security.contract.SkillarsRole;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@RequiredArgsConstructor
@Slf4j
public class GdprExportService {

    private final GdprRequestRepository gdprRequestRepository;
    private final UserRepository userRepository;
    private final PlayerProfileRepository playerProfileRepository;
    private final BookingRepository bookingRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final ParentCreditLedgerRepository parentCreditLedgerRepository;
    private final BookingPaymentRepository bookingPaymentRepository;
    private final MessageRepository messageRepository;
    private final CoachReviewRepository coachReviewRepository;
    private final DisputeRepository disputeRepository;
    private final PlayerTimelineRepository playerTimelineRepository;
    private final RadarAssessmentRepository radarAssessmentRepository;
    private final FileStorageService fileStorageService;
    private final ConfigService configService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final AdminNotificationRecipientResolver recipientResolver;

    /**
     * Runs from GdprEventListener's AFTER_COMMIT callback, where the request's own transaction has
     * already committed but its resources are still bound to the thread: a plain REQUIRED write here
     * would join that finished transaction and never commit (the request row stayed PENDING forever,
     * so the frontend's status poll never saw COMPLETED). REQUIRES_NEW gives the build its own
     * transaction — the same reason GdprErasureService.eraseTransactional is REQUIRES_NEW. Any
     * exception is caught below and recorded as FAILED inside this same transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void buildExport(UUID requestId, Long userId) {
        GdprRequest request = gdprRequestRepository.findById(requestId)
            .orElseThrow(() -> new RuntimeException("GdprRequest not found: " + requestId));
        request.setStatus("PROCESSING");
        gdprRequestRepository.save(request);

        try {
            User user = userRepository.findOneById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));

            ObjectMapper mapper = objectMapper.copy()
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(baos)) {
                addEntry(zos, "profile.json", mapper, buildProfile(user));
                addEntry(zos, "bookings.json", mapper, buildBookings(user, userId));
                addEntry(zos, "payments.json", mapper, buildPayments(user, userId));
                addEntry(zos, "messages.json", mapper, buildMessages(userId));
                addEntry(zos, "reviews.json", mapper, coachReviewRepository.findAllByAuthorId(userId));
                addEntry(zos, "disputes.json", mapper, disputeRepository.findByRaisedBy(userId));
            }

            storeAndComplete(request, baos.toByteArray(), userId);

        } catch (Exception e) {
            log.error("[GDPR_EXPORT_FAILED] requestId={} userId={}", requestId, userId, e);
            request.setStatus("FAILED");
            gdprRequestRepository.save(request);
        }
    }

    /**
     * V172: export of a parent-owned (shadow) child profile, requested by its parent. The child has no
     * account, messages, reviews or payments of its own — those are the parent's and are covered by the
     * parent's own export — so the archive holds the child's profile, its bookings and its development
     * record (timeline events, radar assessments). Ownership is re-checked against the profile here,
     * not only at request time.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW) // see buildExport
    public void buildChildExport(UUID requestId, Long parentUserId, Long playerId) {
        GdprRequest request = gdprRequestRepository.findById(requestId)
            .orElseThrow(() -> new RuntimeException("GdprRequest not found: " + requestId));
        request.setStatus("PROCESSING");
        gdprRequestRepository.save(request);

        try {
            PlayerProfile child = playerProfileRepository.findById(playerId)
                .filter(pp -> parentUserId.equals(pp.getParentId()))
                .orElseThrow(() -> new IllegalStateException(
                    "Player " + playerId + " not found or not owned by parent " + parentUserId));

            ObjectMapper mapper = objectMapper.copy()
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

            java.util.Map<String, Object> profile = new java.util.LinkedHashMap<>();
            profile.put("name", child.getName());
            profile.put("dateOfBirth", child.getDateOfBirth());
            profile.put("position", child.getPosition());
            profile.put("ageTier", child.getAgeTier());
            profile.put("consentAcceptedAt", child.getConsentAcceptedAt());
            profile.put("consentPolicyVersion", child.getConsentPolicyVersion());

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(baos)) {
                addEntry(zos, "profile.json", mapper, profile);
                addEntry(zos, "bookings.json", mapper, bookingRepository.findAllByPlayerId(playerId));
                addEntry(zos, "timeline.json", mapper, playerTimelineRepository
                    .findByPlayerIdOrderByOccurredAtDescIdDesc(playerId, Pageable.unpaged()).getContent());
                addEntry(zos, "assessments.json", mapper, radarAssessmentRepository.findByPlayerId(playerId));
            }

            storeAndComplete(request, baos.toByteArray(), parentUserId);

        } catch (Exception e) {
            log.error("[GDPR_EXPORT_FAILED] requestId={} parentUserId={} playerId={}",
                requestId, parentUserId, playerId, e);
            request.setStatus("FAILED");
            gdprRequestRepository.save(request);
        }
    }

    private void storeAndComplete(GdprRequest request, byte[] zipBytes, Long userId) {
        UUID requestId = request.getId();
        String storageKey = "gdpr/exports/" + requestId + ".zip";
        fileStorageService.storeBytes(zipBytes, storageKey, "application/zip",
            "attachment; filename=\"gdpr-export-" + requestId + ".zip\"");

        // skillars-deferred-107 AC2: 0 → a legally-required GDPR export link is dead on arrival
        // (failFast); huge → compliance exposure. Clamps to 48 + WARN.
        long hours = configService.getBoundedLong(ConfigBounds.GDPR_EXPORT_URL_EXPIRY_HOURS);
        String url = fileStorageService.signedDownloadUrl(storageKey, Duration.ofHours(hours));
        Instant expiresAt = Instant.now().plus(hours, ChronoUnit.HOURS);

        request.setStatus("COMPLETED");
        request.setDownloadUrl(url);
        request.setExpiresAt(expiresAt);
        request.setCompletedAt(Instant.now());
        gdprRequestRepository.save(request);

        // B6: the requester is told the export is ready (AdminOutcomeEmailListener, AFTER_COMMIT in its own
        // transaction, so a failed notice can never roll the export back). Addressed to the account holder directly: the download is on their own
        // profile page, and a child export's requester is already the parent.
        eventPublisher.publishEvent(new GdprExportReadyEvent(this, requestId, userId, expiresAt,
            recipientResolver.forAccountHolder(userId).orElse(null)));
        log.info("[GDPR_EXPORT_COMPLETED] requestId={} userId={} bytes={}", requestId, userId, zipBytes.length);
    }

    private java.util.Map<String, Object> buildProfile(User user) {
        java.util.Map<String, Object> profile = new java.util.LinkedHashMap<>();
        profile.put("firstName", user.getFirstName());
        profile.put("lastName", user.getLastName());
        profile.put("email", user.getEmail());
        profile.put("dateOfBirth", user.getDateOfBirth());
        profile.put("phone", user.getPhone() != null ? user.getPhone().toString() : null);
        return profile;
    }

    List<Booking> buildBookings(User user, Long userId) {
        List<Booking> bookings = new java.util.ArrayList<>(
            bookingRepository.findAllByParentIdOrderByRequestedStartTimeAsc(userId));

        if (user.getSkillarsRole() == SkillarsRole.PLAYER) {
            // Booking.playerId is a PlayerProfile PK, not a User id: resolve the player's own profile first.
            playerProfileRepository.findByUserId(userId).ifPresent(pp ->
                bookings.addAll(bookingRepository.findAllByPlayerId(pp.getId())));
        }

        coachProfileRepository.findByUserId(userId).ifPresent(cp ->
            bookings.addAll(bookingRepository.findAllByCoachId(cp.getId())));

        // Booking has no equals()/hashCode() override, so .stream().distinct() would use reference
        // identity and silently fail to dedupe rows fetched twice via different repository calls
        // (e.g. a self-registered player whose parentId == playerId). Dedupe by id instead, preserving
        // first-seen order (parent bookings first, matching the sequencing above).
        java.util.Map<UUID, Booking> byId = new java.util.LinkedHashMap<>();
        for (Booking b : bookings) {
            byId.putIfAbsent(b.getId(), b);
        }
        return new java.util.ArrayList<>(byId.values());
    }

    private java.util.Map<String, Object> buildPayments(User user, Long userId) {
        java.util.Map<String, Object> payments = new java.util.LinkedHashMap<>();
        if (user.getSkillarsRole() == SkillarsRole.PARENT || user.getSkillarsRole() == null) {
            payments.put("creditLedger", parentCreditLedgerRepository.findAllByParentId(userId));
            List<UUID> bookingIds = bookingRepository.findAllByParentIdOrderByRequestedStartTimeAsc(userId)
                .stream().map(Booking::getId).collect(Collectors.toList());
            if (!bookingIds.isEmpty()) {
                payments.put("bookingPayments", bookingPaymentRepository.findAllById(bookingIds));
            } else {
                payments.put("bookingPayments", List.of());
            }
        }
        return payments;
    }

    private List<?> buildMessages(Long userId) {
        return messageRepository.findNonDeletedBySenderId(userId);
    }

    private void addEntry(ZipOutputStream zos, String name, ObjectMapper mapper, Object data) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(mapper.writeValueAsString(data).getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }
}
