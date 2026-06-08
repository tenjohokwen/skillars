package com.softropic.skillars.platform.filestorage.repo;

import com.softropic.skillars.infrastructure.storage.BaseStorageIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxReplicationJobRepositoryIT extends BaseStorageIT {

    @Autowired
    private OutboxReplicationJobRepository outboxReplicationJobRepository;

    @Autowired
    private FileStorageObjectRepository fileStorageObjectRepository;

    @Autowired
    private StorageAccessEventRepository storageAccessEventRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        storageAccessEventRepository.deleteAll();
        outboxReplicationJobRepository.deleteAll();
        fileStorageObjectRepository.deleteAll();
    }

    @Test
    void pollPending_skipLocked_returnDisjointSets() throws Exception {
        FileStorageObject fso = createFso();
        createPendingJob(fso, OutboxReplicationJob.ReplicationJobType.REPLICATE);
        createPendingJob(fso, OutboxReplicationJob.ReplicationJobType.REPLICATE);
        createPendingJob(fso, OutboxReplicationJob.ReplicationJobType.REPLICATE);
        createPendingJob(fso, OutboxReplicationJob.ReplicationJobType.REPLICATE);

        CountDownLatch tx1Fetched = new CountDownLatch(1);
        CountDownLatch tx2Done = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<List<Long>> tx1Future = pool.submit(() ->
                transactionTemplate.execute(status -> {
                    List<OutboxReplicationJob> jobs = outboxReplicationJobRepository.pollPending(2);
                    tx1Fetched.countDown();
                    try {
                        tx2Done.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return jobs.stream().map(OutboxReplicationJob::getId).collect(Collectors.toList());
                })
            );

            tx1Fetched.await(5, TimeUnit.SECONDS);

            List<Long> tx2Ids = transactionTemplate.execute(status ->
                outboxReplicationJobRepository.pollPending(2)
                    .stream().map(OutboxReplicationJob::getId).collect(Collectors.toList())
            );

            tx2Done.countDown();
            List<Long> tx1Ids = tx1Future.get(5, TimeUnit.SECONDS);

            assertThat(tx1Ids).doesNotContainAnyElementsOf(tx2Ids);
            assertThat(tx1Ids.size() + tx2Ids.size()).isEqualTo(4);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void pollPending_excludesFailedAndCompletedJobs() {
        FileStorageObject fso = createFso();

        OutboxReplicationJob failedJob = OutboxReplicationJob.builder()
            .storageObject(fso)
            .jobType(OutboxReplicationJob.ReplicationJobType.REPLICATE)
            .status(OutboxReplicationJob.ReplicationJobStatus.FAILED)
            .attemptCount(5)
            .build();
        outboxReplicationJobRepository.save(failedJob);

        OutboxReplicationJob completedJob = OutboxReplicationJob.builder()
            .storageObject(fso)
            .jobType(OutboxReplicationJob.ReplicationJobType.REPLICATE)
            .status(OutboxReplicationJob.ReplicationJobStatus.COMPLETED)
            .attemptCount(1)
            .build();
        outboxReplicationJobRepository.save(completedJob);

        List<OutboxReplicationJob> result = transactionTemplate.execute(status ->
            outboxReplicationJobRepository.pollPending(10)
        );

        assertThat(result).isEmpty();
    }

    @Test
    void markAsFailed_setsStatusAndErrorMessage() {
        FileStorageObject fso = createFso();
        OutboxReplicationJob job = createPendingJob(fso, OutboxReplicationJob.ReplicationJobType.REPLICATE);

        Instant now = Instant.now();
        outboxReplicationJobRepository.markAsFailed(job.getId(), 5, now, "test error");

        OutboxReplicationJob reloaded = outboxReplicationJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OutboxReplicationJob.ReplicationJobStatus.FAILED);
        assertThat(reloaded.getErrorMessage()).isEqualTo("test error");
        assertThat(reloaded.getAttemptCount()).isEqualTo(5);
    }

    private FileStorageObject createFso() {
        FileStorageObject fso = FileStorageObject.builder()
            .key("documents/42/2026/05/" + UUID.randomUUID() + ".pdf")
            .ownerId("tenant-test")
            .sizeBytes(1024L)
            .provider("s3")
            .bucket("test-bucket")
            .build();
        return fileStorageObjectRepository.save(fso);
    }

    private OutboxReplicationJob createPendingJob(FileStorageObject fso,
                                                   OutboxReplicationJob.ReplicationJobType type) {
        OutboxReplicationJob job = OutboxReplicationJob.builder()
            .storageObject(fso)
            .jobType(type)
            .status(OutboxReplicationJob.ReplicationJobStatus.PENDING)
            .attemptCount(0)
            .build();
        return outboxReplicationJobRepository.save(job);
    }
}
