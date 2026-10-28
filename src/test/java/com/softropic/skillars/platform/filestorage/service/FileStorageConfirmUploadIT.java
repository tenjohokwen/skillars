package com.softropic.skillars.platform.filestorage.service;

import com.softropic.skillars.infrastructure.blobstore.config.BlobstoreProperties;
import com.softropic.skillars.infrastructure.storage.BaseStorageIT;
import com.softropic.skillars.platform.filestorage.repo.FileStorageObjectRepository;
import com.softropic.skillars.platform.filestorage.repo.OutboxReplicationJob;
import com.softropic.skillars.platform.filestorage.repo.OutboxReplicationJobRepository;
import com.softropic.skillars.infrastructure.blobstore.contract.exception.StorageObjectNotFoundException;
import com.softropic.skillars.platform.filestorage.contract.ConfirmUploadRequest;
import com.softropic.skillars.platform.filestorage.contract.ConfirmUploadResponse;
import com.softropic.skillars.platform.filestorage.contract.SignUploadRequest;
import com.softropic.skillars.platform.filestorage.contract.SignUploadResponse;
import com.softropic.skillars.platform.filestorage.config.FileStorageProperties;
import com.softropic.skillars.platform.filestorage.contract.exception.QuotaExceededException;
import com.softropic.skillars.platform.filestorage.contract.exception.StorageValidationException;
import com.softropic.skillars.platform.filestorage.repo.FileStorageObject;
import com.softropic.skillars.platform.security.WithMockPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@WithMockPrincipal(username = "queb@yahoo.com", businessId = "675373350208068096")
class FileStorageConfirmUploadIT extends BaseStorageIT {

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private FileStorageObjectRepository fileStorageObjectRepository;

    @Autowired
    private OutboxReplicationJobRepository outboxReplicationJobRepository;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private BlobstoreProperties blobstoreProperties;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private jakarta.persistence.EntityManager em;

    @Autowired
    private FileStorageProperties fileStorageProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String OWNER = "675373350208068096";

    @BeforeEach
    void setUp() {
        outboxReplicationJobRepository.deleteAll();
        fileStorageObjectRepository.deleteAll();
    }

    @Test
    void confirmUpload_afterRealPut_savesRecordAndReturnsResponse() {
        byte[] content = new byte[]{1, 2, 3};
        SignUploadResponse signResponse = signAndPut("documents", "42", "application/pdf", "pdf", content);

        ConfirmUploadRequest confirmRequest = new ConfirmUploadRequest(
            "application/pdf", (long) content.length, null, null, null);

        ConfirmUploadResponse response = fileStorageService.confirmUpload(signResponse.key(), confirmRequest);

        assertThat(response.id()).isNotNull();
        assertThat(response.key()).isEqualTo(signResponse.key());
        assertThat(response.sizeBytes()).isEqualTo(content.length);
        assertThat(response.contentType()).isEqualTo("application/pdf");
        assertThat(response.uploadedAt()).isNotNull();

        assertThat(fileStorageObjectRepository.findByKey(signResponse.key())).isPresent();

        // B3 #5: replication is disabled (as in every profile), so nothing would ever drain a REPLICATE job.
        assertThat(outboxReplicationJobRepository.findAll()).isEmpty();
    }

    @Test
    void confirmUpload_replicationEnabled_enqueuesPendingReplicateJob() {
        byte[] content = new byte[]{1, 2, 3};
        SignUploadResponse signResponse = signAndPut("documents", "42", "application/pdf", "pdf", content);

        blobstoreProperties.getReplication().setEnabled(true);
        try {
            fileStorageService.confirmUpload(signResponse.key(),
                new ConfirmUploadRequest("application/pdf", (long) content.length, null, null, null));
        } finally {
            blobstoreProperties.getReplication().setEnabled(false);
        }

        List<OutboxReplicationJob> jobs = outboxReplicationJobRepository.findAll();
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).getJobType()).isEqualTo(OutboxReplicationJob.ReplicationJobType.REPLICATE);
        assertThat(jobs.get(0).getStatus()).isEqualTo(OutboxReplicationJob.ReplicationJobStatus.PENDING);
    }

    // B3 #2: the idempotent short-circuit used to hand any caller the existing record for a key.
    @Test
    void confirmUpload_idempotentPathOfAnotherOwnersRecord_isDenied() {
        byte[] content = new byte[]{1, 2, 3};
        SignUploadResponse signResponse = signAndPut("documents", "42", "application/pdf", "pdf", content);
        ConfirmUploadRequest confirmRequest = new ConfirmUploadRequest(
            "application/pdf", (long) content.length, null, null, null);
        fileStorageService.confirmUpload(signResponse.key(), confirmRequest);
        reassignOwner(signResponse.key(), "someone-else");

        // 404, not 403: the endpoint must not reveal that another user's key exists.
        assertThatThrownBy(() -> fileStorageService.confirmUpload(signResponse.key(), confirmRequest))
            .isInstanceOf(StorageObjectNotFoundException.class);
    }

    // B3 #3 (hardening): a server-written object (storeBytes, no FSO row) must not be confirmable — confirming it
    // would make the caller its recorded owner and so pass assertOwnership / sign-download for it.
    @Test
    void confirmUpload_serverWrittenKey_isNotFoundAndCreatesNoRecord() {
        byte[] pdf = new byte[]{1, 2, 3};
        String reportKey = "reports/" + java.util.UUID.randomUUID() + "/report.pdf";
        fileStorageService.storeBytes(pdf, reportKey, "application/pdf");

        assertThatThrownBy(() -> fileStorageService.confirmUpload(reportKey,
            new ConfirmUploadRequest("application/pdf", (long) pdf.length, null, null, null)))
            .isInstanceOf(StorageObjectNotFoundException.class);
        assertThat(fileStorageObjectRepository.findByKey(reportKey)).isEmpty();
    }

    /**
     * C5: parallel confirms by one owner used to each read the pre-insert usage outside any lock and all
     * pass the quota check. Quota 7 bytes, four 3-byte uploads confirmed at once: exactly two fit.
     */
    @Test
    void confirmUpload_parallelConfirmsBySameOwner_neverOvershootTheQuota() throws Exception {
        byte[] content = new byte[]{1, 2, 3};
        List<SignUploadResponse> uploads = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            uploads.add(signAndPut("documents", "42", "application/pdf", "pdf", content));
        }
        ConfirmUploadRequest confirmRequest = new ConfirmUploadRequest(
            "application/pdf", (long) content.length, null, null, null);

        long originalQuota = fileStorageProperties.getQuota().getDefaultBytes();
        fileStorageProperties.getQuota().setDefaultBytes(7L);
        SecurityContext principal = SecurityContextHolder.getContext();
        ExecutorService executor = Executors.newFixedThreadPool(uploads.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Throwable>> results = new ArrayList<>();
            for (SignUploadResponse upload : uploads) {
                results.add(executor.submit(asOwner(principal, () -> {
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    fileStorageService.confirmUpload(upload.key(), confirmRequest);
                    return null;
                })));
            }
            start.countDown();

            List<Throwable> outcomes = new ArrayList<>();
            for (Future<Throwable> f : results) {
                outcomes.add(f.get(60, TimeUnit.SECONDS));
            }
            assertThat(outcomes.stream().filter(java.util.Objects::isNull).count())
                .as("exactly two 3-byte files fit a 7-byte quota, however the confirms interleave").isEqualTo(2);
            assertThat(outcomes.stream().filter(java.util.Objects::nonNull))
                .allMatch(QuotaExceededException.class::isInstance);
            assertThat(fileStorageObjectRepository.sumSizeBytesByOwnerId(OWNER)).isEqualTo(6L);
        } finally {
            executor.shutdownNow();
            fileStorageProperties.getQuota().setDefaultBytes(originalQuota);
        }
    }

    /**
     * C5, deterministic half: another transaction holds the owner's quota lock with an uncommitted 3-byte
     * row. The confirm must WAIT for it (visible as an ungranted advisory lock) and then count that row —
     * an unlocked usage read would see 0 and let a second 3-byte file into a 5-byte quota.
     */
    @Test
    void confirmUpload_waitsForAConcurrentQuotaHolderAndCountsItsCommittedUsage() throws Exception {
        byte[] content = new byte[]{1, 2, 3};
        SignUploadResponse upload = signAndPut("documents", "42", "application/pdf", "pdf", content);
        ConfirmUploadRequest confirmRequest = new ConfirmUploadRequest(
            "application/pdf", (long) content.length, null, null, null);

        long originalQuota = fileStorageProperties.getQuota().getDefaultBytes();
        fileStorageProperties.getQuota().setDefaultBytes(5L);
        SecurityContext principal = SecurityContextHolder.getContext();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch holderLocked = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        try {
            Future<Throwable> holder = executor.submit(() -> {
                transactionTemplate.executeWithoutResult(status -> {
                    fileStorageObjectRepository.acquireOwnerQuotaLock(OWNER);
                    fileStorageObjectRepository.saveAndFlush(FileStorageObject.builder()
                        .key("documents/42/held-" + System.nanoTime() + ".pdf").ownerId(OWNER)
                        .originalFilename("held.pdf").contentType("application/pdf").sizeBytes(3L)
                        .provider(blobstoreProperties.getProvider()).bucket(blobstoreProperties.getBucket())
                        .uploadConfirmedAt(Instant.now()).build());
                    holderLocked.countDown();
                    try {
                        assertThat(releaseHolder.await(30, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                });
                return null;
            });
            assertThat(holderLocked.await(30, TimeUnit.SECONDS)).isTrue();

            Future<Throwable> confirm = executor.submit(asOwner(principal, () -> {
                fileStorageService.confirmUpload(upload.key(), confirmRequest);
                return null;
            }));

            awaitUngrantedAdvisoryLock();
            assertThat(confirm.isDone()).as("the confirm must block on the owner's quota lock").isFalse();
            releaseHolder.countDown();

            assertThat(holder.get(30, TimeUnit.SECONDS)).isNull();
            assertThat(confirm.get(30, TimeUnit.SECONDS)).isInstanceOf(QuotaExceededException.class);
            assertThat(fileStorageObjectRepository.findByKey(upload.key())).isEmpty();
        } finally {
            releaseHolder.countDown();
            executor.shutdownNow();
            fileStorageProperties.getQuota().setDefaultBytes(originalQuota);
        }
    }

    private static Callable<Throwable> asOwner(SecurityContext principal, Callable<Void> body) {
        return () -> {
            SecurityContextHolder.setContext(principal);
            try {
                body.call();
                return null;
            } catch (Throwable t) {
                return t;
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }

    private void awaitUngrantedAdvisoryLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted AND objsubid = 2 AND classid::bigint = ?",
                Integer.class, (long) FileStorageObjectRepository.QUOTA_LOCK_NAMESPACE);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("no confirm ever waited on the owner's quota advisory lock");
    }

    private void reassignOwner(String key, String ownerId) {
        transactionTemplate.execute(status -> {
            em.createQuery("UPDATE FileStorageObject f SET f.ownerId = :owner WHERE f.key = :key")
                .setParameter("owner", ownerId)
                .setParameter("key", key)
                .executeUpdate();
            return null;
        });
    }

    @Test
    void confirmUpload_missingKey_throws404() {
        ConfirmUploadRequest confirmRequest = new ConfirmUploadRequest(
            "application/pdf", 3L, null, null, null);

        assertThatThrownBy(() -> fileStorageService.confirmUpload("nonexistent/key/file.pdf", confirmRequest))
            .isInstanceOf(StorageObjectNotFoundException.class);
    }

    @Test
    void confirmUpload_contentTypeMismatch_throws422() {
        byte[] content = new byte[]{1, 2, 3};
        SignUploadResponse signResponse = signAndPut("documents", "42", "application/pdf", "pdf", content);

        ConfirmUploadRequest confirmRequest = new ConfirmUploadRequest(
            "image/png", (long) content.length, null, null, null);

        assertThatThrownBy(() -> fileStorageService.confirmUpload(signResponse.key(), confirmRequest))
            .isInstanceOf(StorageValidationException.class)
            .hasMessageContaining("contentType mismatch");
    }

    @Test
    void confirmUpload_fileSizeMismatch_throws422() {
        byte[] content = new byte[]{1, 2, 3};
        SignUploadResponse signResponse = signAndPut("documents", "42", "application/pdf", "pdf", content);

        ConfirmUploadRequest confirmRequest = new ConfirmUploadRequest(
            "application/pdf", 99L, null, null, null);

        assertThatThrownBy(() -> fileStorageService.confirmUpload(signResponse.key(), confirmRequest))
            .isInstanceOf(StorageValidationException.class)
            .hasMessageContaining("fileSizeBytes mismatch");
    }

    @Test
    void confirmUpload_idempotent_returnsExistingRecord() {
        byte[] content = new byte[]{1, 2, 3};
        SignUploadResponse signResponse = signAndPut("documents", "42", "application/pdf", "pdf", content);

        ConfirmUploadRequest confirmRequest = new ConfirmUploadRequest(
            "application/pdf", (long) content.length, null, null, null);

        ConfirmUploadResponse first = fileStorageService.confirmUpload(signResponse.key(), confirmRequest);
        ConfirmUploadResponse second = fileStorageService.confirmUpload(signResponse.key(), confirmRequest);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.key()).isEqualTo(first.key());
        assertThat(fileStorageObjectRepository.findAll()).hasSize(1);
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
}
