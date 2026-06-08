package com.softropic.skillars.infrastructure.storage.service;

import com.softropic.skillars.infrastructure.blobstore.config.BlobstoreProperties;
import com.softropic.skillars.infrastructure.blobstore.contract.StorageObject;
import com.softropic.skillars.infrastructure.blobstore.contract.StorageObjectMetadata;
import com.softropic.skillars.infrastructure.blobstore.contract.exception.StorageProviderException;
import com.softropic.skillars.infrastructure.blobstore.service.S3StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.model.CompletedUpload;
import software.amazon.awssdk.transfer.s3.model.Upload;
import software.amazon.awssdk.transfer.s3.model.UploadRequest;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class S3StorageServiceTest {

    @Mock
    private S3Client s3Client;

    @Mock
    private S3TransferManager transferManager;

    @Mock
    private ExecutorService storageUploadExecutor;

    private BlobstoreProperties properties;
    private S3StorageService service;

    @BeforeEach
    void setUp() {
        properties = new BlobstoreProperties();
        properties.setBucket("test-bucket");
        service = new S3StorageService(s3Client, transferManager, properties, storageUploadExecutor);
    }

    @Test
    @SuppressWarnings("unchecked")
    void put_usesTransferManager() {
        Upload upload = mock(Upload.class);
        CompletedUpload completedUpload = CompletedUpload.builder()
            .response(PutObjectResponse.builder().build()).build();
        when(upload.completionFuture())
            .thenReturn(CompletableFuture.completedFuture(completedUpload));
        when(transferManager.upload(any(UploadRequest.class))).thenReturn(upload);

        InputStream data = new ByteArrayInputStream("hello".getBytes());
        service.put("key/path.txt", data, 5L, "text/plain");

        verify(transferManager).upload(any(UploadRequest.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void get_returnsStorageObject() {
        GetObjectResponse sdkResponse = GetObjectResponse.builder()
            .contentType("text/plain")
            .contentLength(7L)
            .eTag("tag")
            .lastModified(Instant.now())
            .build();
        ResponseInputStream<GetObjectResponse> stream =
            new ResponseInputStream<>(sdkResponse,
                new ByteArrayInputStream("content".getBytes()));
        when(s3Client.getObject(any(Consumer.class))).thenReturn(stream);

        StorageObject result = service.get("key/path.txt");

        assertThat(result).isNotNull();
        assertThat(result.metadata().contentType()).isEqualTo("text/plain");
        assertThat(result.data()).isNotNull();
        verify(s3Client).getObject(any(Consumer.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void delete_callsDeleteObject() {
        when(s3Client.deleteObject(any(Consumer.class))).thenReturn(DeleteObjectResponse.builder().build());

        service.delete("key/path.txt");

        verify(s3Client).deleteObject(any(Consumer.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void exists_returnsTrueWhenObjectExists() {
        when(s3Client.headObject(any(Consumer.class)))
            .thenReturn(HeadObjectResponse.builder().build());

        assertThat(service.exists("key/path.txt")).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void exists_returnsFalseWhenObjectNotFound() {
        when(s3Client.headObject(any(Consumer.class)))
            .thenThrow(NoSuchKeyException.builder().message("not found").build());

        assertThat(service.exists("key/path.txt")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void stat_mapsHeadObjectResponseToMetadata() {
        Instant now = Instant.now();
        when(s3Client.headObject(any(Consumer.class))).thenReturn(
            HeadObjectResponse.builder()
                .contentType("image/jpeg")
                .contentLength(1024L)
                .eTag("abc123")
                .lastModified(now)
                .build());

        StorageObjectMetadata meta = service.stat("images/file.jpg");

        assertThat(meta.key()).isEqualTo("images/file.jpg");
        assertThat(meta.contentType()).isEqualTo("image/jpeg");
        assertThat(meta.contentLength()).isEqualTo(1024L);
        assertThat(meta.eTag()).isEqualTo("abc123");
        assertThat(meta.lastModified()).isEqualTo(now);
    }

    @Test
    @SuppressWarnings("unchecked")
    void copy_callsCopyObject() {
        when(s3Client.copyObject(any(Consumer.class)))
            .thenReturn(software.amazon.awssdk.services.s3.model.CopyObjectResponse.builder().build());

        service.copy("source/file.txt", "dest/file.txt");

        verify(s3Client).copyObject(any(Consumer.class));
    }

    @Test
    void recoverDelete_throwsStorageProviderException() {
        SdkException sdkEx = SdkException.builder().message("connection error").build();
        assertThatThrownBy(() -> service.recoverDelete(sdkEx, "key/path.txt"))
            .isInstanceOf(StorageProviderException.class);
    }

    @Test
    void recoverPut_throwsStorageProviderException() {
        SdkException sdkEx = SdkException.builder().message("put failed").build();
        assertThatThrownBy(() -> service.recoverPut(sdkEx, "key", null, 0, "text/plain"))
            .isInstanceOf(StorageProviderException.class);
    }

    @Test
    void recoverGet_throwsStorageProviderException() {
        SdkException sdkEx = SdkException.builder().message("get failed").build();
        assertThatThrownBy(() -> service.recoverGet(sdkEx, "key"))
            .isInstanceOf(StorageProviderException.class);
    }

    @Test
    void recoverExists_throwsStorageProviderException() {
        SdkException sdkEx = SdkException.builder().message("exists failed").build();
        assertThatThrownBy(() -> service.recoverExists(sdkEx, "key"))
            .isInstanceOf(StorageProviderException.class);
    }

    @Test
    void recoverStat_throwsStorageProviderException() {
        SdkException sdkEx = SdkException.builder().message("stat failed").build();
        assertThatThrownBy(() -> service.recoverStat(sdkEx, "key"))
            .isInstanceOf(StorageProviderException.class);
    }

    @Test
    void recoverCopy_throwsStorageProviderException() {
        SdkException sdkEx = SdkException.builder().message("copy failed").build();
        assertThatThrownBy(() -> service.recoverCopy(sdkEx, "src", "dst"))
            .isInstanceOf(StorageProviderException.class);
    }
}
