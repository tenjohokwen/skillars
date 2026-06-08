package com.softropic.skillars.platform.filestorage.service;

import com.softropic.skillars.infrastructure.blobstore.contract.StorageObject;
import com.softropic.skillars.infrastructure.blobstore.contract.StorageObjectMetadata;
import com.softropic.skillars.infrastructure.blobstore.contract.exception.StorageProviderException;
import com.softropic.skillars.infrastructure.blobstore.service.StorageService;
import com.softropic.skillars.platform.filestorage.repo.FileStorageObject;
import com.softropic.skillars.platform.filestorage.repo.FileStorageObjectRepository;
import com.softropic.skillars.platform.filestorage.repo.OutboxReplicationJob;
import com.softropic.skillars.platform.filestorage.repo.OutboxReplicationJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReplicatedStorageServiceTest {

    @Mock
    private StorageService primaryStorageService;

    @Mock
    private FileStorageObjectRepository fileStorageObjectRepository;

    @Mock
    private OutboxReplicationJobRepository outboxReplicationJobRepository;

    @Mock
    private TransactionTemplate transactionTemplate;

    private ReplicatedStorageService service;

    @BeforeEach
    void setUp() {
        service = new ReplicatedStorageService(
            primaryStorageService,
            fileStorageObjectRepository,
            outboxReplicationJobRepository,
            transactionTemplate
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void put_primarySuccess_fsoPresent_savesOutboxEntry() {
        FileStorageObject fso = mock(FileStorageObject.class);
        when(fileStorageObjectRepository.findByKey("test/file.jpg")).thenReturn(Optional.of(fso));
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> cb = invocation.getArgument(0);
            cb.doInTransaction(mock(TransactionStatus.class));
            return null;
        });

        InputStream data = new ByteArrayInputStream("bytes".getBytes());
        service.put("test/file.jpg", data, 5L, "image/jpeg");

        verify(primaryStorageService).put("test/file.jpg", data, 5L, "image/jpeg");

        ArgumentCaptor<OutboxReplicationJob> captor = ArgumentCaptor.forClass(OutboxReplicationJob.class);
        verify(outboxReplicationJobRepository).save(captor.capture());
        OutboxReplicationJob saved = captor.getValue();
        assertThat(saved.getJobType()).isEqualTo(OutboxReplicationJob.ReplicationJobType.REPLICATE);
        assertThat(saved.getStatus()).isEqualTo(OutboxReplicationJob.ReplicationJobStatus.PENDING);
        assertThat(saved.getAttemptCount()).isEqualTo(0);
        assertThat(saved.getStorageObject()).isSameAs(fso);
    }

    @Test
    void put_primaryFailure_noOutboxEntry() {
        InputStream data = new ByteArrayInputStream("bytes".getBytes());
        doThrow(new StorageProviderException("put", new RuntimeException("s3 down"), "test/file.jpg"))
            .when(primaryStorageService).put(any(), any(), anyLong(), any());

        assertThatThrownBy(() -> service.put("test/file.jpg", data, 5L, "image/jpeg"))
            .isInstanceOf(StorageProviderException.class);

        verifyNoInteractions(outboxReplicationJobRepository);
        verifyNoInteractions(transactionTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void put_primarySuccess_fsoAbsent_skipsOutbox() {
        when(fileStorageObjectRepository.findByKey("test/file.jpg")).thenReturn(Optional.empty());
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> cb = invocation.getArgument(0);
            cb.doInTransaction(mock(TransactionStatus.class));
            return null;
        });

        InputStream data = new ByteArrayInputStream("bytes".getBytes());
        service.put("test/file.jpg", data, 5L, "image/jpeg");

        verify(primaryStorageService).put("test/file.jpg", data, 5L, "image/jpeg");
        verifyNoInteractions(outboxReplicationJobRepository);
    }

    @Test
    void get_delegatesToPrimary() {
        StorageObject expected = mock(StorageObject.class);
        when(primaryStorageService.get("test/file.jpg")).thenReturn(expected);

        StorageObject result = service.get("test/file.jpg");

        assertThat(result).isSameAs(expected);
        verify(primaryStorageService).get("test/file.jpg");
        verifyNoInteractions(outboxReplicationJobRepository);
    }

    @Test
    void delete_delegatesToPrimary() {
        service.delete("test/file.jpg");

        verify(primaryStorageService).delete("test/file.jpg");
        verifyNoInteractions(outboxReplicationJobRepository);
    }

    @Test
    void exists_delegatesToPrimary() {
        when(primaryStorageService.exists("test/file.jpg")).thenReturn(true);

        boolean result = service.exists("test/file.jpg");

        assertThat(result).isTrue();
        verify(primaryStorageService).exists("test/file.jpg");
        verifyNoInteractions(outboxReplicationJobRepository);
    }

    @Test
    void stat_delegatesToPrimary() {
        StorageObjectMetadata expected = mock(StorageObjectMetadata.class);
        when(primaryStorageService.stat("test/file.jpg")).thenReturn(expected);

        StorageObjectMetadata result = service.stat("test/file.jpg");

        assertThat(result).isSameAs(expected);
        verify(primaryStorageService).stat("test/file.jpg");
        verifyNoInteractions(outboxReplicationJobRepository);
    }

    @Test
    void copy_delegatesToPrimary() {
        service.copy("source/file.jpg", "dest/file.jpg");

        verify(primaryStorageService).copy("source/file.jpg", "dest/file.jpg");
        verifyNoInteractions(outboxReplicationJobRepository);
    }
}
