package com.softropic.skillars.platform.filestorage.service;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.filestorage.repo.FileStorageObject;
import com.softropic.skillars.platform.filestorage.repo.FileStorageObjectRepository;
import com.softropic.skillars.platform.filestorage.repo.ScanStatus;
import com.softropic.skillars.platform.security.WithMockPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// S-18: the scan_status columns and the targeted UPDATEs the scan pipeline relies on, against real Postgres.
@WithMockPrincipal(username = "scan-it@example.com", businessId = "1")
class FileStorageScanStatusIT extends AbstractIntegrationTest {

    @Autowired
    private FileStorageObjectRepository repository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private String owner;

    @BeforeEach
    void setUp() {
        owner = "scan-it-" + UUID.randomUUID();
    }

    private FileStorageObject saved(ScanStatus status, Instant confirmedAt) {
        return transactionTemplate.execute(s -> repository.saveAndFlush(FileStorageObject.builder()
            .key("coach_gallery/" + owner + "/" + UUID.randomUUID() + ".png").ownerId(owner).provider("s3").bucket("b")
            .sizeBytes(3).contentType("image/png").uploadConfirmedAt(confirmedAt).scanStatus(status).build()));
    }

    private ScanStatus statusOf(FileStorageObject fso) {
        return repository.findById(fso.getId()).orElseThrow().getScanStatus();
    }

    @Test
    void newRowDefaultsToNotRequired() {
        assertThat(statusOf(saved(ScanStatus.NOT_REQUIRED, Instant.now()))).isEqualTo(ScanStatus.NOT_REQUIRED);
    }

    @Test
    void markScanClean_onlyFromPending_aFlaggedObjectIsNeverUnflagged() {
        FileStorageObject pending = saved(ScanStatus.PENDING, Instant.now());
        FileStorageObject flagged = saved(ScanStatus.FLAGGED, Instant.now());

        assertThat(repository.markScanClean(pending.getId(), Instant.now())).isEqualTo(1);
        assertThat(repository.markScanClean(flagged.getId(), Instant.now())).isZero();

        assertThat(statusOf(pending)).isEqualTo(ScanStatus.CLEAN);
        assertThat(statusOf(flagged)).isEqualTo(ScanStatus.FLAGGED);
    }

    @Test
    void flaggedMatch_quarantinesTheOwnersOtherLiveObjects_andNobodyElses() {
        FileStorageObject match = saved(ScanStatus.PENDING, Instant.now());
        FileStorageObject other = saved(ScanStatus.CLEAN, Instant.now());
        FileStorageObject strangers = transactionTemplate.execute(s -> repository.saveAndFlush(FileStorageObject.builder()
            .key("coach_gallery/x/" + UUID.randomUUID() + ".png").ownerId(owner + "-other").provider("s3").bucket("b")
            .sizeBytes(3).contentType("image/png").uploadConfirmedAt(Instant.now()).scanStatus(ScanStatus.CLEAN).build()));

        repository.markScanFlagged(match.getId(), Instant.now());
        int quarantined = repository.quarantineOtherObjectsOfOwner(owner, match.getId());

        assertThat(quarantined).isEqualTo(1);
        assertThat(statusOf(match)).isEqualTo(ScanStatus.FLAGGED);
        assertThat(statusOf(other)).isEqualTo(ScanStatus.QUARANTINED);
        assertThat(statusOf(strangers)).isEqualTo(ScanStatus.CLEAN);
    }

    @Test
    void pendingScanDue_findsOnlyOldPendingObjects_andARecordedAttemptPushesItBack() {
        FileStorageObject old = saved(ScanStatus.PENDING, Instant.now().minus(1, ChronoUnit.HOURS));
        FileStorageObject fresh = saved(ScanStatus.PENDING, Instant.now());
        FileStorageObject clean = saved(ScanStatus.CLEAN, Instant.now().minus(1, ChronoUnit.HOURS));
        Instant cutoff = Instant.now().minus(5, ChronoUnit.MINUTES);

        var due = repository.findPendingScanDue(cutoff, PageRequest.of(0, 1000));
        assertThat(due).extracting(FileStorageObject::getId).contains(old.getId())
            .doesNotContain(fresh.getId(), clean.getId());

        repository.recordScanAttempt(old.getId(), Instant.now());

        assertThat(repository.findPendingScanDue(cutoff, PageRequest.of(0, 1000)))
            .extracting(FileStorageObject::getId).doesNotContain(old.getId());
        assertThat(repository.findById(old.getId()).orElseThrow().getScanAttempts()).isEqualTo(1);
    }
}
