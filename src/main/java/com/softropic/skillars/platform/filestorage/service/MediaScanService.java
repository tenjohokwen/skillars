package com.softropic.skillars.platform.filestorage.service;

import com.softropic.skillars.infrastructure.arachnid.ArachnidClient;
import com.softropic.skillars.infrastructure.arachnid.ArachnidProperties;
import com.softropic.skillars.infrastructure.arachnid.ArachnidScanResult;
import com.softropic.skillars.platform.filestorage.event.StorageObjectConfirmedEvent;
import com.softropic.skillars.platform.filestorage.repo.FileStorageObject;
import com.softropic.skillars.platform.filestorage.repo.FileStorageObjectRepository;
import com.softropic.skillars.platform.filestorage.repo.ScanStatus;
import com.softropic.skillars.platform.security.contract.event.UserSuspensionRequestedEvent;
import com.softropic.skillars.platform.video.contract.event.VideoModerationAdminAlertEvent;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * S-18: the upload to scan to visibility pipeline for images. A confirmed image starts {@link ScanStatus#PENDING}
 * ({@link MediaScanPolicy}); nobody but the uploader is handed a URL for it ({@link FileStorageService#signedDownloadUrl})
 * until Project Arachnid says it is clean. FAIL CLOSED throughout:
 * <ul>
 *   <li>scanner down / timeout / error / unexpected exception: the object stays PENDING, the attempt is counted, the
 *       sweeper ({@link MediaScanRetryScheduler}) tries again, and after {@code alertAfterAttempts} an admin is told;</li>
 *   <li>scanning switched off after the upload: PENDING objects stay withheld until it is switched back on;</li>
 *   <li>hash match: FLAGGED (bytes kept for the admin / lawful-reporting process, not deleted), every other object of
 *       the uploader QUARANTINED, an urgent admin alert, the account suspended.</li>
 * </ul>
 * Logs and metrics carry the object id and match type only: no key, filename or uploader details.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaScanService {

    private final FileStorageObjectRepository repository;
    private final FileStorageService fileStorageService;
    private final ArachnidClient arachnidClient;
    private final ArachnidProperties arachnidProperties;
    private final MediaScanPolicy mediaScanPolicy;
    private final ApplicationEventPublisher publisher;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;

    @Async("moderationTaskExecutor")
    @EventListener
    public void onConfirmed(StorageObjectConfirmedEvent event) {
        FileStorageObject fso = event.getStorageObject();
        if (fso.getScanStatus() == ScanStatus.PENDING) {
            scan(fso.getId());
        }
    }

    /** Retries what the scanner could not judge yet. Called by the sweeper; a no-op while scanning is off. */
    public void retryPending() {
        if (!mediaScanPolicy.scanningEnabled()) {
            return;
        }
        Instant cutoff = Instant.now().minus(Duration.ofMillis(arachnidProperties.getRetryDelayMs()));
        List<FileStorageObject> due = repository.findPendingScanDue(cutoff, PageRequest.of(0, arachnidProperties.getSweepBatchSize()));
        due.forEach(fso -> scan(fso.getId()));
    }

    /** Scans one object. Never throws: any failure leaves it PENDING (withheld) for the next sweep. */
    public void scan(Long id) {
        try {
            doScan(id);
        } catch (RuntimeException e) {
            // Includes an exception from the verdict handling itself; the object keeps whatever state it reached,
            // and PENDING is the withheld one. Message only: the exception text can carry a URL with a signature.
            count("error");
            log.error("[MEDIA_SCAN_ERROR objectId={}] {}", id, e.getClass().getSimpleName());
        }
    }

    private void doScan(Long id) {
        FileStorageObject fso = repository.findById(id).orElse(null);
        if (fso == null || fso.getDeletedAt() != null || fso.getScanStatus() != ScanStatus.PENDING) {
            return;
        }
        if (!mediaScanPolicy.scanningEnabled()) {
            log.warn("[MEDIA_SCAN_SKIPPED objectId={}] scanning is disabled; the object stays withheld", id);
            return;
        }
        ArachnidScanResult result;
        try {
            result = arachnidClient.scan(fileStorageService.signedScanUrl(fso.getKey()));
        } catch (RuntimeException e) {
            onNoVerdict(fso, e);
            return;
        }
        if (result == null) {
            onNoVerdict(fso, new IllegalStateException("empty scan result"));
            return;
        }
        if (result.matched()) {
            onMatch(fso, Objects.toString(result.matchType(), "UNKNOWN"));
        } else if (repository.markScanClean(id, Instant.now()) > 0) {
            count("clean");
        }
    }

    private void onNoVerdict(FileStorageObject fso, RuntimeException cause) {
        repository.recordScanAttempt(fso.getId(), Instant.now());
        int attempts = fso.getScanAttempts() + 1;
        count("unavailable");
        log.error("[MEDIA_SCAN_UNAVAILABLE objectId={} attempts={}] {}", fso.getId(), attempts,
            cause.getClass().getSimpleName());
        if (attempts == arachnidProperties.getAlertAfterAttempts()) {
            alertAdmin(fso.getOwnerId(), "Image scanning is not producing verdicts",
                "objectId=" + fso.getId() + " ownerId=" + fso.getOwnerId() + " attempts=" + attempts
                    + " — the image stays hidden from everyone but the uploader and is retried. Check the Arachnid "
                    + "credential and C3P availability.", false);
        }
    }

    private void onMatch(FileStorageObject fso, String matchType) {
        count("flagged");
        log.error("[MEDIA_SCAN_MATCH objectId={} matchType={}] abuse-media hash match", fso.getId(), matchType);
        Instant now = Instant.now();
        int quarantined = transactionTemplate.execute(status -> {
            repository.markScanFlagged(fso.getId(), now);
            return repository.quarantineOtherObjectsOfOwner(fso.getOwnerId(), fso.getId());
        });
        // Alert BEFORE suspension: a match nobody hears about is worse than a failed suspension.
        alertAdmin(fso.getOwnerId(), "Abuse-media match on an uploaded image",
            "objectId=" + fso.getId() + " ownerId=" + fso.getOwnerId() + " matchType=" + matchType
                + " quarantinedOtherObjects=" + quarantined + " — the image is withheld but its bytes are KEPT (do not "
                + "purge before legal advice on the reporting duty). The account is being suspended; the coach "
                + "safety-hold side effects (payouts, bookings) are NOT triggered automatically.", true);
        try {
            publisher.publishEvent(new UserSuspensionRequestedEvent(Long.parseLong(fso.getOwnerId()), "MEDIA_SCAN_MATCH"));
        } catch (RuntimeException e) {
            log.error("[MEDIA_SCAN_SUSPEND_FAILED objectId={}] {}", fso.getId(), e.getClass().getSimpleName());
            alertAdmin(fso.getOwnerId(), "Abuse-media match — account suspension FAILED",
                "objectId=" + fso.getId() + " ownerId=" + fso.getOwnerId() + " — suspend the account by hand.", true);
        }
    }

    private void alertAdmin(String ownerId, String subject, String body, boolean urgent) {
        // Same envelope as the video pipeline, sent AFTER_COMMIT: it is dropped if no transaction is active, and
        // this runs on an @Async thread outside one.
        VideoModerationAdminAlertEvent alert = new VideoModerationAdminAlertEvent(null, ownerId, subject, body, urgent);
        transactionTemplate.execute(status -> {
            publisher.publishEvent(alert);
            return null;
        });
    }

    private void count(String result) {
        meterRegistry.counter("skillars.media.scan", "result", result).increment();
    }
}
