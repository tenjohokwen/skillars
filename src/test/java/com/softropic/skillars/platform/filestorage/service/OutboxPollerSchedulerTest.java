package com.softropic.skillars.platform.filestorage.service;

import com.softropic.skillars.infrastructure.blobstore.config.BlobstoreProperties;
import com.softropic.skillars.infrastructure.blobstore.contract.StorageObject;
import com.softropic.skillars.infrastructure.blobstore.contract.StorageObjectMetadata;
import com.softropic.skillars.infrastructure.blobstore.service.StorageMetrics;
import com.softropic.skillars.infrastructure.blobstore.service.StorageService;
import com.softropic.skillars.platform.filestorage.repo.FileStorageObject;
import com.softropic.skillars.platform.filestorage.repo.OutboxReplicationJob;
import com.softropic.skillars.platform.filestorage.repo.OutboxReplicationJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxPollerSchedulerTest {

    @Mock
    private StorageService primaryStorageService;

    @Mock
    private StorageService backupStorageService;

    @Mock
    private OutboxReplicationJobRepository outboxReplicationJobRepository;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private StorageMetrics storageMetrics;

    // Use real instance — defaults: batchSize=10, maxAttempts=5
    private final BlobstoreProperties properties = new BlobstoreProperties();

    private OutboxPollerScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new OutboxPollerScheduler(
            primaryStorageService, backupStorageService,
            outboxReplicationJobRepository, properties, transactionTemplate,
            storageMetrics);
    }

    @Test
    @SuppressWarnings("unchecked")
    void pollAndProcess_replicateJob_streamsFromPrimaryToBackup() throws Exception {
        FileStorageObject fso = mock(FileStorageObject.class);
        when(fso.getKey()).thenReturn("documents/42/file.pdf");

        OutboxReplicationJob job = mock(OutboxReplicationJob.class);
        when(job.getId()).thenReturn(1L);
        when(job.getJobType()).thenReturn(OutboxReplicationJob.ReplicationJobType.REPLICATE);
        when(job.getStorageObject()).thenReturn(fso);

        StorageObjectMetadata metadata = new StorageObjectMetadata(
            "documents/42/file.pdf", "application/pdf", 1024L, "etag", Instant.now());
        StorageObject storageObj = new StorageObject(new ByteArrayInputStream("data".getBytes()), metadata);
        when(primaryStorageService.get("documents/42/file.pdf")).thenReturn(storageObj);
        when(outboxReplicationJobRepository.pollPending(10)).thenReturn(List.of(job));

        when(transactionTemplate.execute(any()))
            .thenAnswer(invocation -> {
                TransactionCallback<?> cb = invocation.getArgument(0);
                cb.doInTransaction(mock(TransactionStatus.class));
                return List.of(job);
            })
            .thenAnswer(invocation -> {
                TransactionCallback<?> cb = invocation.getArgument(0);
                cb.doInTransaction(mock(TransactionStatus.class));
                return null;
            });

        scheduler.pollAndProcess();

        verify(primaryStorageService).get("documents/42/file.pdf");
        verify(backupStorageService).put(eq("documents/42/file.pdf"), any(), eq(1024L), eq("application/pdf"));
        verify(outboxReplicationJobRepository).markAsCompleted(1L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void pollAndProcess_deleteJob_callsBackupDelete() {
        FileStorageObject fso = mock(FileStorageObject.class);
        when(fso.getKey()).thenReturn("documents/42/file.pdf");

        OutboxReplicationJob job = mock(OutboxReplicationJob.class);
        when(job.getId()).thenReturn(2L);
        when(job.getJobType()).thenReturn(OutboxReplicationJob.ReplicationJobType.DELETE);
        when(job.getStorageObject()).thenReturn(fso);

        when(outboxReplicationJobRepository.pollPending(10)).thenReturn(List.of(job));

        when(transactionTemplate.execute(any()))
            .thenAnswer(invocation -> {
                TransactionCallback<?> cb = invocation.getArgument(0);
                cb.doInTransaction(mock(TransactionStatus.class));
                return List.of(job);
            })
            .thenAnswer(invocation -> {
                TransactionCallback<?> cb = invocation.getArgument(0);
                cb.doInTransaction(mock(TransactionStatus.class));
                return null;
            });

        scheduler.pollAndProcess();

        verify(backupStorageService).delete("documents/42/file.pdf");
        verify(outboxReplicationJobRepository).markAsCompleted(2L);
        verify(primaryStorageService, never()).get(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void pollAndProcess_failure_belowMaxAttempts_resetsToPending() {
        FileStorageObject fso = mock(FileStorageObject.class);
        when(fso.getKey()).thenReturn("documents/42/file.pdf");

        OutboxReplicationJob job = mock(OutboxReplicationJob.class);
        when(job.getId()).thenReturn(3L);
        when(job.getJobType()).thenReturn(OutboxReplicationJob.ReplicationJobType.REPLICATE);
        when(job.getAttemptCount()).thenReturn(0);
        when(job.getStorageObject()).thenReturn(fso);

        StorageObjectMetadata metadata = new StorageObjectMetadata(
            "documents/42/file.pdf", "application/pdf", 1024L, "etag", Instant.now());
        StorageObject storageObj = new StorageObject(new ByteArrayInputStream("data".getBytes()), metadata);
        when(primaryStorageService.get("documents/42/file.pdf")).thenReturn(storageObj);
        doThrow(new RuntimeException("backup unavailable"))
            .when(backupStorageService).put(any(), any(), anyLong(), any());
        when(outboxReplicationJobRepository.pollPending(10)).thenReturn(List.of(job));

        when(transactionTemplate.execute(any()))
            .thenAnswer(invocation -> {
                TransactionCallback<?> cb = invocation.getArgument(0);
                cb.doInTransaction(mock(TransactionStatus.class));
                return List.of(job);
            })
            .thenAnswer(invocation -> {
                TransactionCallback<?> cb = invocation.getArgument(0);
                cb.doInTransaction(mock(TransactionStatus.class));
                return null;
            });

        scheduler.pollAndProcess();

        verify(outboxReplicationJobRepository).markAsPendingForRetry(eq(3L), eq(1), any(Instant.class), anyString());
        verify(outboxReplicationJobRepository, never()).markAsFailed(any(), anyInt(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void pollAndProcess_failure_atMaxAttempts_marksAsFailed() {
        FileStorageObject fso = mock(FileStorageObject.class);
        when(fso.getKey()).thenReturn("documents/42/file.pdf");

        OutboxReplicationJob job = mock(OutboxReplicationJob.class);
        when(job.getId()).thenReturn(4L);
        when(job.getJobType()).thenReturn(OutboxReplicationJob.ReplicationJobType.REPLICATE);
        when(job.getAttemptCount()).thenReturn(4);  // newCount=5 >= maxAttempts(5)
        when(job.getStorageObject()).thenReturn(fso);

        StorageObjectMetadata metadata = new StorageObjectMetadata(
            "documents/42/file.pdf", "application/pdf", 1024L, "etag", Instant.now());
        StorageObject storageObj = new StorageObject(new ByteArrayInputStream("data".getBytes()), metadata);
        when(primaryStorageService.get("documents/42/file.pdf")).thenReturn(storageObj);
        doThrow(new RuntimeException("backup down"))
            .when(backupStorageService).put(any(), any(), anyLong(), any());
        when(outboxReplicationJobRepository.pollPending(10)).thenReturn(List.of(job));

        when(transactionTemplate.execute(any()))
            .thenAnswer(invocation -> {
                TransactionCallback<?> cb = invocation.getArgument(0);
                cb.doInTransaction(mock(TransactionStatus.class));
                return List.of(job);
            })
            .thenAnswer(invocation -> {
                TransactionCallback<?> cb = invocation.getArgument(0);
                cb.doInTransaction(mock(TransactionStatus.class));
                return null;
            });

        scheduler.pollAndProcess();

        verify(outboxReplicationJobRepository).markAsFailed(eq(4L), eq(5), any(Instant.class), anyString());
        verify(outboxReplicationJobRepository, never()).markAsPendingForRetry(any(), anyInt(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void pollAndProcess_emptyPoll_noop() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> cb = invocation.getArgument(0);
            cb.doInTransaction(mock(TransactionStatus.class));
            return List.of();
        });

        scheduler.pollAndProcess();

        verify(primaryStorageService, never()).get(any());
        verify(primaryStorageService, never()).put(any(), any(), anyLong(), any());
        verify(backupStorageService, never()).get(any());
        verify(backupStorageService, never()).put(any(), any(), anyLong(), any());
        verify(backupStorageService, never()).delete(any());
        verify(outboxReplicationJobRepository, never()).markAsCompleted(any());
        verify(outboxReplicationJobRepository, never()).markAsFailed(any(), anyInt(), any(), any());
        verify(outboxReplicationJobRepository, never()).markAsPendingForRetry(any(), anyInt(), any(), any());
    }
}
