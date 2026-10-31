package com.softropic.skillars.platform.marketplace.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.config.service.ConfigBounds;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.filestorage.service.BlobDeletionOutboxSupport;
import com.softropic.skillars.platform.filestorage.service.FileStorageService;
import com.softropic.skillars.platform.marketplace.contract.CoachSafeguardingExpiringEvent;
import com.softropic.skillars.platform.marketplace.contract.CoachSafeguardingStatusResponse;
import com.softropic.skillars.platform.marketplace.contract.MarketplaceException;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachSafeguardingCheck;
import com.softropic.skillars.platform.marketplace.repo.CoachSafeguardingCheckRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachSafeguardingRequest;
import com.softropic.skillars.platform.marketplace.repo.CoachSafeguardingRequestRepository;
import com.softropic.skillars.platform.security.contract.util.Cryptopher;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * S-09: the OPTIONAL coach safeguarding check (DE: erweitertes Fuehrungszeugnis, {@link #DEFAULT_CHECK_TYPE}).
 * Nothing in the platform is gated on it: the coach's public profile just says whether a check is on record.
 *
 * <p><strong>Who can set the badge.</strong> Only staff, through {@link #markInspected}, after inspecting the
 * certificate; a coach can only <em>ask</em> ({@link #requestVerification}) and withdraw. A badge cannot be set for
 * a coach who has not asked (consent).
 *
 * <p><strong>Data minimisation (GDPR Art. 10 / Art. 5(1)(c), the section 72a SGB VIII pattern).</strong> The record
 * keeps the coach, type, inspection date, expiry and the staff user id - nothing from the certificate. The optional
 * upload sits in the private bucket, application-encrypted ({@code Cryptopher}, AES-256, the same secret as the
 * provider PINs), under a key no storage endpoint can sign (no {@code file_storage_objects} row). It is deleted -
 * through the durable blob-deletion outbox - when staff decide, when the request is replaced or withdrawn, on
 * erasure, and by {@link #sweepStaleUploads} after {@code coach.safeguarding.uploadRetentionDays}.
 *
 * <p><strong>Erasure race.</strong> {@code GdprErasureService} commits the coach recap scrub (and its
 * {@code recap_data_erased_at} tombstone) before the user is locked. {@link #requestVerification} therefore takes
 * the coach row lock and checks that tombstone, so a request cannot land after the erasure's delete and survive it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoachSafeguardingService {

    /** Germany: erweitertes Fuehrungszeugnis. Other jurisdictions add their own generic code. */
    public static final String DEFAULT_CHECK_TYPE = "DE_EFZ";

    static final long MAX_UPLOAD_BYTES = 5L * 1024 * 1024;
    /** A renewal request is accepted once the current check has less than this left. */
    static final Duration RENEWAL_WINDOW = Duration.ofDays(90);
    /** The expiry reminder goes out when a valid check has at most this long left. */
    static final Duration REMINDER_LEAD = Duration.ofDays(30);
    static final int BATCH_SIZE = 100;
    static final int MAX_BATCHES_PER_RUN = 50;
    private static final String KEY_PREFIX = "safeguarding/";

    private final CoachProfileRepository coachProfileRepository;
    private final CoachSafeguardingCheckRepository checkRepository;
    private final CoachSafeguardingRequestRepository requestRepository;
    private final FileStorageService fileStorageService;
    private final BlobDeletionOutboxSupport blobDeletionOutboxSupport;
    private final Cryptopher cryptopher;
    private final ConfigService configService;
    private final PessimisticLockRetryer lockRetryer;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    /** A decrypted upload handed to the admin viewer. */
    public record Document(byte[] bytes, String contentType) {
    }

    /** The outcome of {@link #markInspected}: the record written and whether an upload was held (and deleted). */
    public record Inspection(CoachSafeguardingCheck check, boolean hadUpload) {
    }

    // ── Coach side ───────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public CoachSafeguardingStatusResponse getOwnStatus(Long userId) {
        CoachProfile profile = requireProfile(userId);
        return statusOf(profile.getId(), Instant.now());
    }

    /**
     * Files (or refreshes) the coach's request for staff to inspect their certificate, optionally with the
     * certificate as {@code file}. A request with no file is the "inspect it another way" route (e.g. a video
     * call). A pending request is updated in place: a new upload replaces (and deletes) the previous one.
     *
     * @param file the certificate bytes (PDF, JPEG or PNG, at most 5 MB), or null
     */
    @Transactional
    public CoachSafeguardingStatusResponse requestVerification(Long userId, byte[] file) {
        CoachProfile profile = requireProfile(userId);
        UUID coachId = profile.getId();
        String contentType = file == null ? null : sniffContentType(file);

        lockProfile(coachId);
        // The scalar tombstone read, after the lock (see the class Javadoc).
        if (coachProfileRepository.isRecapDataErased(coachId)) {
            throw new MarketplaceException("marketplace.profileNotFound", "Coach profile not found for userId=" + userId);
        }

        Instant now = Instant.now();
        CoachSafeguardingRequest request = requestRepository.findById(coachId).orElse(null);
        boolean alreadyPending = request != null && request.isPending();
        if (!alreadyPending && !canRequest(coachId, now)) {
            throw new MarketplaceException("marketplace.safeguardingStillValid",
                "A safeguarding check is already on record and not close to expiry");
        }
        if (alreadyPending && file == null) {
            return statusOf(coachId, now); // idempotent re-click
        }

        if (request == null) {
            request = new CoachSafeguardingRequest();
            request.setCoachId(coachId);
        }
        if (!alreadyPending) {
            request.setRequestedAt(now);
        }
        request.setStatus(CoachSafeguardingRequest.STATUS_PENDING);
        request.setCheckType(DEFAULT_CHECK_TYPE);
        request.setDecidedAt(null);

        String previousKey = request.getUploadKey();
        if (file != null) {
            String key = KEY_PREFIX + coachId + "/" + UUID.randomUUID() + ".enc";
            storeEncrypted(key, file);
            request.setUploadKey(key);
            request.setUploadStoredAt(now);
            request.setUploadContentType(contentType);
        } else {
            // A fresh request after a rejection: the rejected request's upload was already deleted at decision time.
            request.setUploadKey(null);
            request.setUploadStoredAt(null);
            request.setUploadContentType(null);
        }
        requestRepository.save(request);
        deleteBlobs(previousKey);
        return statusOf(coachId, now);
    }

    /** The coach takes their record and any request/upload off the platform. Idempotent. */
    @Transactional
    public void withdraw(Long userId) {
        CoachProfile profile = requireProfile(userId);
        UUID coachId = profile.getId();
        requestRepository.findById(coachId).ifPresent(r -> {
            requestRepository.delete(r);
            deleteBlobs(r.getUploadKey());
        });
        checkRepository.findById(coachId).ifPresent(checkRepository::delete);
        log.info("Coach withdrew safeguarding check: coachId={}", coachId);
    }

    // ── Staff side (called by AdminSafeguardingService, which audits) ────────────────────────────

    @Transactional(readOnly = true)
    public Page<CoachSafeguardingRequest> pendingRequests(Pageable pageable) {
        return requestRepository.findPending(pageable);
    }

    @Transactional(readOnly = true)
    public Page<CoachSafeguardingCheck> validRecords(Pageable pageable) {
        return checkRepository.findValid(Instant.now(), pageable);
    }

    @Transactional(readOnly = true)
    public boolean hasValidCheck(UUID coachId) {
        return checkRepository.findById(coachId).filter(c -> c.getExpiresAt().isAfter(Instant.now())).isPresent();
    }

    /** The pending request's decrypted upload; 404 when there is no pending request or no upload on it. */
    @Transactional(readOnly = true)
    public Document loadDocument(UUID coachId) {
        CoachSafeguardingRequest request = requestRepository.findById(coachId)
            .filter(CoachSafeguardingRequest::isPending)
            .filter(CoachSafeguardingRequest::hasUpload)
            .orElseThrow(() -> new ResourceNotFoundException("No safeguarding upload on file", "coach_safeguarding"));
        byte[] encrypted = fileStorageService.downloadBytes(request.getUploadKey());
        String base64 = cryptopher.decrypt(new String(encrypted, StandardCharsets.UTF_8));
        return new Document(Base64.getDecoder().decode(base64), request.getUploadContentType());
    }

    /**
     * Staff confirm they inspected the certificate. Writes (or renews) the record, closes the request and deletes
     * the upload, all in one transaction. 404 when the coach has no pending request: nobody gets a badge they did
     * not ask for. Two staff users deciding at once, or a withdraw racing the decision, collide on the request
     * row's version and the loser gets a 409.
     */
    @Transactional
    public Inspection markInspected(UUID coachId, Long adminId) {
        CoachSafeguardingRequest request = requirePending(coachId);
        Instant now = Instant.now();
        long years = configService.getBoundedLong(ConfigBounds.COACH_SAFEGUARDING_VALIDITY_YEARS);

        CoachSafeguardingCheck check = checkRepository.findById(coachId).orElseGet(() -> {
            CoachSafeguardingCheck created = new CoachSafeguardingCheck();
            created.setCoachId(coachId);
            return created;
        });
        check.setCheckType(request.getCheckType());
        check.setInspectedAt(now);
        check.setExpiresAt(now.atZone(ZoneOffset.UTC).plusYears(years).toInstant());
        check.setInspectedBy(adminId);
        check.setReminderSentAt(null);
        CoachSafeguardingCheck saved = checkRepository.save(check);

        requestRepository.delete(request);
        deleteBlobs(request.getUploadKey());
        return new Inspection(saved, request.hasUpload());
    }

    /** Staff could not confirm the certificate. The upload is deleted; the coach sees REJECTED and may ask again. */
    @Transactional
    public CoachSafeguardingRequest reject(UUID coachId) {
        CoachSafeguardingRequest request = requirePending(coachId);
        String key = request.getUploadKey();
        request.setStatus(CoachSafeguardingRequest.STATUS_REJECTED);
        request.setDecidedAt(Instant.now());
        request.setUploadKey(null);
        request.setUploadStoredAt(null);
        request.setUploadContentType(null);
        CoachSafeguardingRequest saved = requestRepository.save(request);
        deleteBlobs(key);
        return saved;
    }

    /** Staff remove a record (e.g. recorded in error). 404 when there is none. */
    @Transactional
    public CoachSafeguardingCheck revoke(UUID coachId) {
        CoachSafeguardingCheck check = checkRepository.findById(coachId)
            .orElseThrow(() -> new ResourceNotFoundException("No safeguarding check on record", "coach_safeguarding"));
        checkRepository.delete(check);
        return check;
    }

    // ── Public read model ────────────────────────────────────────────────────────────────────────

    /**
     * Valid-until date per coach for coaches in {@code coachIds} whose check is valid now; a coach with no, or a
     * lapsed, check is absent. One query for a whole search page. Expiry is judged here, at read time.
     */
    @Transactional(readOnly = true)
    public Map<UUID, LocalDate> validUntilByCoach(Collection<UUID> coachIds) {
        Map<UUID, LocalDate> result = new HashMap<>();
        if (coachIds == null || coachIds.isEmpty()) {
            return result;
        }
        for (Object[] row : checkRepository.findValidExpiryByCoachIdIn(coachIds, Instant.now())) {
            result.put((UUID) row[0], ((Instant) row[1]).atZone(ZoneOffset.UTC).toLocalDate());
        }
        return result;
    }

    // ── Erasure ──────────────────────────────────────────────────────────────────────────────────

    /**
     * GDPR Art. 17: deletes the coach's request and record and enqueues the upload for deletion. Called inside
     * {@code GdprErasureService}'s transaction (the blob outbox's contract); idempotent for the retry scheduler.
     */
    @Transactional
    public void eraseForCoach(UUID coachId) {
        List<String> keys = requestRepository.deleteByCoachIdReturningUploadKey(coachId);
        checkRepository.deleteByCoachIdNative(coachId);
        blobDeletionOutboxSupport.enqueue(keys);
        blobDeletionOutboxSupport.requestDrainAfterCommit();
    }

    // ── Jobs (CoachSafeguardingScheduler) ────────────────────────────────────────────────────────

    /**
     * Deletes uploads that sat unprocessed for longer than {@code coach.safeguarding.uploadRetentionDays}. The
     * request stays (without its file), so staff can still inspect another way. Each request in its own
     * transaction so one bad row does not abort the run.
     *
     * @return the number of uploads deleted
     */
    public int sweepStaleUploads() {
        long days = configService.getBoundedLong(ConfigBounds.COACH_SAFEGUARDING_UPLOAD_RETENTION_DAYS);
        Instant cutoff = Instant.now().minus(Duration.ofDays(days));
        int deleted = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            List<UUID> ids = transactionTemplate.execute(status ->
                requestRepository.findStaleUploads(cutoff, PageRequest.of(0, BATCH_SIZE)).stream()
                    .map(CoachSafeguardingRequest::getCoachId).toList());
            if (ids == null || ids.isEmpty()) {
                break;
            }
            int deletedThisBatch = 0;
            for (UUID coachId : ids) {
                try {
                    Boolean done = transactionTemplate.execute(status -> dropUpload(coachId, cutoff));
                    if (Boolean.TRUE.equals(done)) {
                        deletedThisBatch++;
                    }
                } catch (OptimisticLockingFailureException e) {
                    log.info("Safeguarding request changed concurrently during the upload sweep - skipped: coachId={}",
                        coachId);
                } catch (Exception e) {
                    log.error("Failed to delete stale safeguarding upload: coachId={}", coachId, e);
                }
            }
            deleted += deletedThisBatch;
            if (deletedThisBatch == 0) {
                break; // everything left is failing: do not spin on it
            }
        }
        if (deleted > 0) {
            log.info("Safeguarding upload sweep deleted {} stale upload(s)", deleted);
        }
        return deleted;
    }

    private boolean dropUpload(UUID coachId, Instant cutoff) {
        CoachSafeguardingRequest request = requestRepository.findById(coachId).orElse(null);
        if (request == null || !request.hasUpload() || !request.getUploadStoredAt().isBefore(cutoff)) {
            return false; // decided, withdrawn or re-uploaded since the batch was read
        }
        String key = request.getUploadKey();
        request.setUploadKey(null);
        request.setUploadStoredAt(null);
        request.setUploadContentType(null);
        requestRepository.save(request);
        deleteBlobs(key);
        return true;
    }

    /**
     * Emails each coach whose check lapses within {@code REMINDER_LEAD}, once ({@code reminder_sent_at}, reset by a
     * renewal). The stamp and the event commit together, so a delivered reminder is always a recorded one.
     *
     * @return the number of reminders queued
     */
    public int sendExpiryReminders() {
        Instant now = Instant.now();
        List<UUID> due = transactionTemplate.execute(status ->
            checkRepository.findDueForReminder(now, now.plus(REMINDER_LEAD), PageRequest.of(0, BATCH_SIZE * 2)).stream()
                .map(CoachSafeguardingCheck::getCoachId).toList());
        if (due == null) {
            return 0;
        }
        int sent = 0;
        for (UUID coachId : due) {
            try {
                Boolean queued = transactionTemplate.execute(status -> remind(coachId, now));
                if (Boolean.TRUE.equals(queued)) {
                    sent++;
                }
            } catch (OptimisticLockingFailureException e) {
                log.info("Safeguarding check changed concurrently during the reminder sweep - skipped: coachId={}",
                    coachId);
            } catch (Exception e) {
                log.error("Failed to queue safeguarding expiry reminder: coachId={}", coachId, e);
            }
        }
        return sent;
    }

    private boolean remind(UUID coachId, Instant now) {
        CoachSafeguardingCheck check = checkRepository.findById(coachId).orElse(null);
        if (check == null || check.getReminderSentAt() != null || !check.getExpiresAt().isAfter(now)) {
            return false; // withdrawn, renewed, already reminded or lapsed since the batch was read
        }
        CoachProfile profile = coachProfileRepository.findById(coachId).orElse(null);
        User user = profile == null ? null : userRepository.findById(profile.getUserId()).orElse(null);
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            // Left unstamped on purpose: the ERROR repeats daily until the row is repaired.
            log.error("Safeguarding expiry reminder skipped - no reachable coach: coachId={}", coachId);
            return false;
        }
        check.setReminderSentAt(now);
        checkRepository.save(check);
        eventPublisher.publishEvent(new CoachSafeguardingExpiringEvent(
            coachId, profile.getDisplayName(), user.getEmail(),
            user.getLangKey() == null || user.getLangKey().isBlank() ? "en" : user.getLangKey(),
            profile.getCanonicalTimezone(), check.getExpiresAt()));
        return true;
    }

    // ── Internals ────────────────────────────────────────────────────────────────────────────────

    private CoachSafeguardingStatusResponse statusOf(UUID coachId, Instant now) {
        CoachSafeguardingCheck check = checkRepository.findById(coachId).orElse(null);
        CoachSafeguardingRequest request = requestRepository.findById(coachId).orElse(null);
        boolean pending = request != null && request.isPending();
        boolean uploadOnFile = pending && request.hasUpload();
        long retentionDays = configService.getBoundedLong(ConfigBounds.COACH_SAFEGUARDING_UPLOAD_RETENTION_DAYS);
        Instant uploadDeleteAt = uploadOnFile ? request.getUploadStoredAt().plus(Duration.ofDays(retentionDays)) : null;
        Instant requestedAt = request == null ? null : request.getRequestedAt();

        boolean valid = check != null && check.getExpiresAt().isAfter(now);
        String status;
        if (valid) {
            status = CoachSafeguardingStatusResponse.VERIFIED;
        } else if (request != null) {
            status = pending ? CoachSafeguardingStatusResponse.PENDING : CoachSafeguardingStatusResponse.REJECTED;
        } else {
            status = check != null ? CoachSafeguardingStatusResponse.EXPIRED : CoachSafeguardingStatusResponse.NONE;
        }
        return new CoachSafeguardingStatusResponse(
            status,
            check != null ? check.getCheckType() : (request != null ? request.getCheckType() : DEFAULT_CHECK_TYPE),
            check == null ? null : check.getExpiresAt().atZone(ZoneOffset.UTC).toLocalDate(),
            valid && pending,
            requestedAt, uploadOnFile, uploadDeleteAt, (int) retentionDays,
            !pending && canRequest(check, now));
    }

    private boolean canRequest(UUID coachId, Instant now) {
        return canRequest(checkRepository.findById(coachId).orElse(null), now);
    }

    private static boolean canRequest(CoachSafeguardingCheck check, Instant now) {
        return check == null || !check.getExpiresAt().isAfter(now.plus(RENEWAL_WINDOW));
    }

    private CoachSafeguardingRequest requirePending(UUID coachId) {
        return requestRepository.findById(coachId)
            .filter(CoachSafeguardingRequest::isPending)
            .orElseThrow(() -> new ResourceNotFoundException("No pending safeguarding request", "coach_safeguarding"));
    }

    private CoachProfile requireProfile(Long userId) {
        return coachProfileRepository.findByUserId(userId)
            .orElseThrow(() -> new MarketplaceException("marketplace.profileNotFound",
                "Coach profile not found for userId=" + userId));
    }

    private void lockProfile(UUID coachId) {
        lockRetryer.withBoundedRetry("CoachSafeguardingService.lockProfile", () ->
            coachProfileRepository.findByIdForUpdate(coachId)
                .orElseThrow(() -> new MarketplaceException("marketplace.profileNotFound", "Coach profile not found")));
    }

    /**
     * Application-level encryption: the bytes go to the bucket as the {@code Cryptopher} ciphertext of their
     * Base64, so a bucket read (or a leaked backup of the volume) shows nothing. Registers a best-effort delete
     * if the surrounding transaction rolls back, so a failed save does not strand a certificate in the bucket.
     */
    private void storeEncrypted(String key, byte[] plaintext) {
        String ciphertext = cryptopher.encrypt(Base64.getEncoder().encodeToString(plaintext));
        fileStorageService.storeBytes(ciphertext.getBytes(StandardCharsets.UTF_8), key, "application/octet-stream");
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        try {
                            fileStorageService.deleteRawBytes(key);
                        } catch (RuntimeException e) {
                            log.error("Could not delete safeguarding upload after rollback: key={}", key, e);
                        }
                    }
                }
            });
        }
    }

    private void deleteBlobs(String key) {
        if (key == null) {
            return;
        }
        blobDeletionOutboxSupport.enqueue(List.of(key));
        blobDeletionOutboxSupport.requestDrainAfterCommit();
    }

    /**
     * The plaintext's type from its magic bytes - never the client's declared type. PDF, JPEG and PNG only;
     * anything else is refused so the viewer can never be handed active content.
     */
    static String sniffContentType(byte[] b) {
        if (b == null || b.length == 0) {
            throw new MarketplaceException("marketplace.safeguardingFileEmpty", "Empty file");
        }
        if (b.length > MAX_UPLOAD_BYTES) {
            throw new MarketplaceException("marketplace.safeguardingFileTooLarge", "File exceeds 5 MB");
        }
        if (b.length >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-') {
            return "application/pdf";
        }
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return "image/png";
        }
        throw new MarketplaceException("marketplace.safeguardingFileType", "Only PDF, JPEG or PNG is accepted");
    }
}
