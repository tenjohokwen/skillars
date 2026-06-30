package com.softropic.skillars.platform.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.softropic.skillars.platform.admin.contract.GdprExportReadyEvent;
import com.softropic.skillars.platform.admin.repo.GdprRequest;
import com.softropic.skillars.platform.admin.repo.GdprRequestRepository;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.filestorage.service.FileStorageService;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.messaging.repo.MessageRepository;
import com.softropic.skillars.platform.payment.repo.BookingPaymentRepository;
import com.softropic.skillars.platform.payment.repo.ParentCreditLedgerRepository;
import com.softropic.skillars.platform.admin.repo.DisputeRepository;
import com.softropic.skillars.platform.reviews.repo.CoachReviewRepository;
import com.softropic.skillars.platform.security.contract.SkillarsRole;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

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
    private final BookingRepository bookingRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final ParentCreditLedgerRepository parentCreditLedgerRepository;
    private final BookingPaymentRepository bookingPaymentRepository;
    private final MessageRepository messageRepository;
    private final CoachReviewRepository coachReviewRepository;
    private final DisputeRepository disputeRepository;
    private final FileStorageService fileStorageService;
    private final ConfigService configService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

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

            byte[] zipBytes = baos.toByteArray();
            String storageKey = "gdpr/exports/" + requestId + ".zip";
            fileStorageService.storeBytes(zipBytes, storageKey, "application/zip",
                "attachment; filename=\"gdpr-export-" + requestId + ".zip\"");

            long hours = configService.getLong("gdpr.export.urlExpiryHours", 48L);
            String url = fileStorageService.signedDownloadUrl(storageKey, Duration.ofHours(hours));
            Instant expiresAt = Instant.now().plus(hours, ChronoUnit.HOURS);

            request.setStatus("COMPLETED");
            request.setDownloadUrl(url);
            request.setExpiresAt(expiresAt);
            request.setCompletedAt(Instant.now());
            gdprRequestRepository.save(request);

            eventPublisher.publishEvent(new GdprExportReadyEvent(this, requestId, userId));
            log.info("[GDPR_EXPORT_COMPLETED] requestId={} userId={} bytes={}", requestId, userId, zipBytes.length);

        } catch (Exception e) {
            log.error("[GDPR_EXPORT_FAILED] requestId={} userId={}", requestId, userId, e);
            request.setStatus("FAILED");
            gdprRequestRepository.save(request);
        }
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

    private List<Booking> buildBookings(User user, Long userId) {
        List<Booking> bookings = new java.util.ArrayList<>(
            bookingRepository.findAllByParentIdOrderByRequestedStartTimeAsc(userId));

        if (user.getSkillarsRole() == SkillarsRole.PLAYER) {
            bookings.addAll(bookingRepository.findAllByPlayerId(userId));
        }

        coachProfileRepository.findByUserId(userId).ifPresent(cp ->
            bookings.addAll(bookingRepository.findAllByCoachId(cp.getId())));

        return bookings.stream().distinct().collect(Collectors.toList());
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
