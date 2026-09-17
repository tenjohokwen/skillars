package com.softropic.skillars.platform.filestorage.service;

import com.softropic.skillars.infrastructure.blobstore.config.BlobstoreProperties;
import com.softropic.skillars.infrastructure.blobstore.service.StorageService;
import com.softropic.skillars.platform.filestorage.repo.FileStorageObject;
import com.softropic.skillars.platform.filestorage.repo.FileStorageObjectRepository;
import com.softropic.skillars.platform.filestorage.repo.OutboxReplicationJob;
import com.softropic.skillars.platform.filestorage.repo.OutboxReplicationJobRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * skillars-deferred-119 AC1 — unit coverage for {@link DeletionSchedulerService}, complementing (not
 * replacing) {@link FileStorageDeletionIT}'s existing real-DB/real-storage coverage. Mirrors
 * {@link OutboxPollerSchedulerTest}'s Mockito + {@code TransactionTemplate}-stubbing shape: the
 * {@code transactionTemplate.execute(any())} stub actually invokes the real
 * {@code TransactionCallback} so the callback's real ordering logic (conditional-update-then-save)
 * executes under test, rather than being skipped by a bare no-op stub.
 */
@ExtendWith(MockitoExtension.class)
class DeletionSchedulerServiceTest {

    @Mock
    private FileStorageObjectRepository fileStorageObjectRepository;

    @Mock
    private OutboxReplicationJobRepository outboxReplicationJobRepository;

    @Mock
    private StorageService storageService;

    @Mock
    private TransactionTemplate transactionTemplate;

    // Use a real instance — defaults: batchSize=10, retentionDays=30
    private final BlobstoreProperties properties = new BlobstoreProperties();

    private DeletionSchedulerService service;

    @BeforeEach
    void setUp() {
        service = new DeletionSchedulerService(
            fileStorageObjectRepository, outboxReplicationJobRepository, storageService,
            properties, transactionTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void processDeletions_happyPath_deletesReplicatesAndMarksPhysicallyDeleted() {
        FileStorageObject fso = mock(FileStorageObject.class);
        when(fso.getId()).thenReturn(1L);
        when(fso.getKey()).thenReturn("documents/42/file.pdf");
        when(fileStorageObjectRepository.findEligibleForPhysicalDeletion(any(Instant.class), eq(10)))
            .thenReturn(List.of(fso));
        when(fileStorageObjectRepository.markPhysicallyDeleted(eq(1L), any(Instant.class))).thenReturn(1);

        stubTransactionTemplateToRunCallback();

        service.processDeletions();

        verify(storageService).delete("documents/42/file.pdf");
        verify(fileStorageObjectRepository).markPhysicallyDeleted(eq(1L), any(Instant.class));
        verify(outboxReplicationJobRepository).save(any(OutboxReplicationJob.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void processDeletions_losingRace_skipsOutboxSaveWhenNoRowsAffected() {
        // skillars-deferred-119 AC1: markPhysicallyDeleted returning 0 affected rows simulates a
        // losing race against a concurrent tick that already claimed this row — save() must never be
        // invoked in that case. FileStorageDeletionIT cannot easily force this branch (it needs a real
        // second writer), which is why this unit test exists.
        FileStorageObject fso = mock(FileStorageObject.class);
        when(fso.getId()).thenReturn(2L);
        when(fso.getKey()).thenReturn("documents/42/other.pdf");
        when(fileStorageObjectRepository.findEligibleForPhysicalDeletion(any(Instant.class), eq(10)))
            .thenReturn(List.of(fso));
        when(fileStorageObjectRepository.markPhysicallyDeleted(eq(2L), any(Instant.class))).thenReturn(0);

        stubTransactionTemplateToRunCallback();

        service.processDeletions();

        verify(storageService).delete("documents/42/other.pdf");
        verify(fileStorageObjectRepository).markPhysicallyDeleted(eq(2L), any(Instant.class));
        verify(outboxReplicationJobRepository, never()).save(any(OutboxReplicationJob.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void processDeletions_deleteThrowsForOneRow_continuesToNextRow() {
        FileStorageObject failing = mock(FileStorageObject.class);
        when(failing.getKey()).thenReturn("documents/42/broken.pdf");

        FileStorageObject succeeding = mock(FileStorageObject.class);
        when(succeeding.getId()).thenReturn(3L);
        when(succeeding.getKey()).thenReturn("documents/42/ok.pdf");

        when(fileStorageObjectRepository.findEligibleForPhysicalDeletion(any(Instant.class), eq(10)))
            .thenReturn(List.of(failing, succeeding));
        doThrow(new RuntimeException("S3 unavailable"))
            .when(storageService).delete("documents/42/broken.pdf");
        when(fileStorageObjectRepository.markPhysicallyDeleted(eq(3L), any(Instant.class))).thenReturn(1);

        stubTransactionTemplateToRunCallback();

        service.processDeletions();

        verify(storageService).delete("documents/42/broken.pdf");
        verify(storageService).delete("documents/42/ok.pdf");
        // the failing row's transaction body never runs (caught by the existing catch-and-continue) —
        // markPhysicallyDeleted is only ever invoked once, for the row whose storage delete succeeded
        verify(fileStorageObjectRepository, times(1))
            .markPhysicallyDeleted(any(Long.class), any(Instant.class));
        verify(fileStorageObjectRepository).markPhysicallyDeleted(eq(3L), any(Instant.class));
        verify(outboxReplicationJobRepository).save(any(OutboxReplicationJob.class));
    }

    @Test
    void processDeletions_carriesSchedulerLock() throws NoSuchMethodException {
        // skillars-deferred-119 AC1: findEligibleForPhysicalDeletion's FOR UPDATE SKIP LOCKED claim
        // commits and releases its row locks before this loop starts, so processDeletions() needs its
        // own @SchedulerLock to close the concurrent-invocation window.
        Method method = DeletionSchedulerService.class.getMethod("processDeletions");
        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);

        assertThat(lock).as("processDeletions() must carry @SchedulerLock").isNotNull();
        assertThat(lock.name()).isNotBlank();
        assertThat(Duration.parse(lock.lockAtMostFor())).isPositive();
        assertThat(Duration.parse(lock.lockAtLeastFor())).isPositive();
    }

    @SuppressWarnings("unchecked")
    private void stubTransactionTemplateToRunCallback() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> cb = invocation.getArgument(0);
            return cb.doInTransaction(mock(TransactionStatus.class));
        });
    }
}
