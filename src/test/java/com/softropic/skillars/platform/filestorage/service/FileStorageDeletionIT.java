package com.softropic.skillars.platform.filestorage.service;

import com.softropic.skillars.infrastructure.storage.BaseStorageIT;
import com.softropic.skillars.infrastructure.blobstore.config.BlobstoreProperties;
import com.softropic.skillars.infrastructure.blobstore.contract.exception.StorageObjectNotFoundException;
import com.softropic.skillars.platform.filestorage.repo.FileStorageObjectRepository;
import com.softropic.skillars.platform.filestorage.repo.OutboxReplicationJob;
import com.softropic.skillars.platform.filestorage.repo.OutboxReplicationJobRepository;
import com.softropic.skillars.platform.filestorage.repo.StorageAccessEventRepository;
import com.softropic.skillars.platform.filestorage.service.DeletionSchedulerService;
import com.softropic.skillars.infrastructure.blobstore.service.StorageService;
import com.softropic.skillars.platform.filestorage.contract.ConfirmUploadRequest;
import com.softropic.skillars.platform.filestorage.contract.ConfirmUploadResponse;
import com.softropic.skillars.platform.filestorage.contract.SignUploadRequest;
import com.softropic.skillars.platform.filestorage.contract.SignUploadResponse;
import jakarta.persistence.EntityManager;
import com.softropic.skillars.platform.security.WithMockPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@WithMockPrincipal(username = "queb@yahoo.com", businessId = "675373350208068096")
class FileStorageDeletionIT extends BaseStorageIT {

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private DeletionSchedulerService deletionSchedulerService;

    @Autowired
    private FileStorageObjectRepository fileStorageObjectRepository;

    @Autowired
    private OutboxReplicationJobRepository outboxReplicationJobRepository;

    @Autowired
    private StorageAccessEventRepository storageAccessEventRepository;

    @Autowired
    private StorageService storageService;

    @Autowired
    private EntityManager em;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private BlobstoreProperties storageProperties;

    @BeforeEach
    void setUp() {
        storageAccessEventRepository.deleteAll();
        outboxReplicationJobRepository.deleteAll();
        fileStorageObjectRepository.deleteAll();
    }

    @Test
    void softDelete_makesFileImmediatelyInaccessible() {
        byte[] content = "delete me".getBytes();
        SignUploadResponse signResponse = signAndPut("documents", "42", "application/pdf", "pdf", content);
        confirmUpload(signResponse, content, "application/pdf");

        transactionTemplate.execute(status -> {
            em.createQuery("UPDATE FileStorageObject f SET f.createdBy = :user WHERE f.key = :key")
                .setParameter("user", "test-user")
                .setParameter("key", signResponse.key())
                .executeUpdate();
            return null;
        });

        fileStorageService.softDelete(signResponse.key(), "test-user");

        assertThatThrownBy(() -> fileStorageService.signDownload(signResponse.key()))
            .isInstanceOf(StorageObjectNotFoundException.class);
    }

    @Test
    void scheduler_doesNotDeleteWithinRetentionWindow() {
        byte[] content = "within retention".getBytes();
        SignUploadResponse signResponse = signAndPut("documents", "42", "application/pdf", "pdf", content);
        confirmUpload(signResponse, content, "application/pdf");

        transactionTemplate.execute(status -> {
            em.createQuery("UPDATE FileStorageObject f SET f.createdBy = :user WHERE f.key = :key")
                .setParameter("user", "test-user")
                .setParameter("key", signResponse.key())
                .executeUpdate();
            return null;
        });

        fileStorageService.softDelete(signResponse.key(), "test-user");
        deletionSchedulerService.processDeletions();

        assertThat(fileStorageObjectRepository.findByKey(signResponse.key()))
            .isPresent()
            .get()
            .satisfies(fso -> assertThat(fso.getPhysicalDeletedAt()).isNull());
    }

    @Test
    void scheduler_physicallyDeletesPastRetentionWindow() {
        byte[] content = "past retention".getBytes();
        SignUploadResponse signResponse = signAndPut("documents", "42", "application/pdf", "pdf", content);
        confirmUpload(signResponse, content, "application/pdf");

        transactionTemplate.execute(status -> {
            em.createQuery("UPDATE FileStorageObject f SET f.createdBy = :user WHERE f.key = :key")
                .setParameter("user", "test-user")
                .setParameter("key", signResponse.key())
                .executeUpdate();
            return null;
        });

        fileStorageService.softDelete(signResponse.key(), "test-user");
        backdateDeletedAt(signResponse.key(), storageProperties.getDeletion().getRetentionDays() + 1);

        deletionSchedulerService.processDeletions();

        assertThat(fileStorageObjectRepository.findByKey(signResponse.key()))
            .isPresent()
            .get()
            .satisfies(fso -> assertThat(fso.getPhysicalDeletedAt()).isNotNull());

        List<OutboxReplicationJob> jobs = outboxReplicationJobRepository.findAll();
        assertThat(jobs).anyMatch(j -> j.getJobType() == OutboxReplicationJob.ReplicationJobType.DELETE);

        assertThat(storageService.exists(signResponse.key())).isFalse();
    }

    /**
     * skillars-deferred-119 AC1 — recommended (not required) concurrency coverage, mirroring
     * {@code ReliabilityStrikeConcurrencyIT}'s latch/executor shape. Two threads race
     * {@code deletionSchedulerService.processDeletions()} against the same retention-eligible row.
     * With {@code @SchedulerLock} in place, one tick acquires the lock and the loser's call returns
     * without executing the method body at all (per {@code SchedulingConfig}'s own Javadoc: ShedLock
     * stays enabled in tests, and tests invoking a scheduled method directly still go through the
     * lock advisor) — confirmed empirically while authoring this test: the losing thread's debug log
     * reads {@code Not executing 'DeletionSchedulerService_processDeletions'. It's locked.} and it
     * never touches storage or the DB. This pins the end-to-end guarantee that two overlapping ticks
     * never enqueue a duplicate {@code DELETE}-type {@code OutboxReplicationJob} for the same object.
     * It complements, not replaces, {@code DeletionSchedulerServiceTest}'s isolated unit coverage of
     * the conditional-write backstop itself (the {@code markPhysicallyDeleted} returning 0 branch),
     * which requires bypassing the proxy/lock entirely to force — not reachable through this real,
     * proxied bean, since the lock alone already prevents the second tick's body from running at all.
     */
    @Test
    @Timeout(60)
    void scheduler_concurrentTicks_doNotDuplicateOutboxJobForSameRow() throws Exception {
        byte[] content = "concurrent race".getBytes();
        SignUploadResponse signResponse = signAndPut("documents", "42", "application/pdf", "pdf", content);
        // confirmUpload itself enqueues a REPLICATE-type job (replication is enabled by
        // MinioTestConfig) — that job is not this test's concern, only the DELETE-type job(s) the
        // racing processDeletions() calls below may or may not duplicate.
        confirmUpload(signResponse, content, "application/pdf");

        transactionTemplate.execute(status -> {
            em.createQuery("UPDATE FileStorageObject f SET f.createdBy = :user WHERE f.key = :key")
                .setParameter("user", "test-user")
                .setParameter("key", signResponse.key())
                .executeUpdate();
            return null;
        });

        fileStorageService.softDelete(signResponse.key(), "test-user");
        backdateDeletedAt(signResponse.key(), storageProperties.getDeletion().getRetentionDays() + 1);

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch bothReady = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = pool.submit(() -> raceProcessDeletions(startGate, bothReady));
            Future<?> b = pool.submit(() -> raceProcessDeletions(startGate, bothReady));

            assertThat(bothReady.await(10, TimeUnit.SECONDS))
                .as("both worker threads must reach the gate").isTrue();
            startGate.countDown();

            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(fileStorageObjectRepository.findByKey(signResponse.key()))
            .isPresent()
            .get()
            .satisfies(fso -> assertThat(fso.getPhysicalDeletedAt()).isNotNull());

        List<OutboxReplicationJob> deleteJobs = outboxReplicationJobRepository.findAll().stream()
            .filter(j -> j.getJobType() == OutboxReplicationJob.ReplicationJobType.DELETE)
            .toList();
        assertThat(deleteJobs)
            .as("two racing ticks must not enqueue duplicate DELETE replication jobs for the same object")
            .hasSize(1);
    }

    private void raceProcessDeletions(CountDownLatch startGate, CountDownLatch bothReady) {
        bothReady.countDown();
        try {
            startGate.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        deletionSchedulerService.processDeletions();
    }

    @Test
    void softDelete_nonExistentKey_throwsNotFoundException() {
        assertThatThrownBy(() -> fileStorageService.softDelete("nonexistent/key/file.pdf", "test-user"))
            .isInstanceOf(StorageObjectNotFoundException.class);
    }

    private void backdateDeletedAt(String key, int daysAgo) {
        transactionTemplate.execute(status -> {
            em.createQuery("UPDATE FileStorageObject f SET f.deletedAt = :past WHERE f.key = :key")
                .setParameter("past", Instant.now().minus(daysAgo, ChronoUnit.DAYS))
                .setParameter("key", key)
                .executeUpdate();
            return null;
        });
    }

    private SignUploadResponse signAndPut(String entity, String entityId, String contentType, String extension, byte[] content) {
        SignUploadRequest signRequest = new SignUploadRequest(
            entity, entityId, contentType, extension, (long) content.length, null, null);
        SignUploadResponse signResponse = fileStorageService.signUpload(signRequest);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));
        restTemplate.exchange(
            URI.create(signResponse.uploadUrl()), HttpMethod.PUT,
            new HttpEntity<>(content, headers), Void.class);
        return signResponse;
    }

    private ConfirmUploadResponse confirmUpload(SignUploadResponse signResponse, byte[] content, String contentType) {
        ConfirmUploadRequest confirmRequest = new ConfirmUploadRequest(
            contentType, (long) content.length, null, null, null);
        return fileStorageService.confirmUpload(signResponse.key(), confirmRequest);
    }
}
