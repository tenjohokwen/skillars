package com.softropic.skillars.infrastructure.storage.service;

import com.softropic.skillars.infrastructure.blobstore.config.BlobstoreProperties;
import com.softropic.skillars.infrastructure.blobstore.contract.exception.StorageProviderException;
import com.softropic.skillars.infrastructure.blobstore.service.S3StorageService;
import com.softropic.skillars.infrastructure.blobstore.service.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.S3Error;
import software.amazon.awssdk.transfer.s3.S3TransferManager;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * skillars-deferred-164 AC11 (code review). Proves {@link S3StorageService#deleteAll}'s
 * {@code @Retryable}/{@code @Recover} wiring through the real retry proxy — {@code S3StorageServiceTest}
 * instantiates the service directly and so structurally cannot. Without the
 * {@code StorageProviderException} pass-through recoverer, the per-key-failure case below surfaces as
 * {@code ExhaustedRetryException("Cannot locate recovery method")}.
 *
 * <p>Named {@code *Test}, not {@code *IT}, for the same reason as {@code SluRetrierProxyRetryTest}: it
 * starts no container, so it runs under Surefire and does not touch the failsafe context-count gate.
 */
@SpringJUnitConfig(S3StorageServiceProxyRetryTest.Config.class)
@TestPropertySource(properties = {
    "app.storage.retry.max-attempts=3",
    "app.storage.retry.backoff-initial-ms=1",
    "app.storage.retry.backoff-multiplier=1.0"
})
class S3StorageServiceProxyRetryTest {

    @Configuration
    @EnableRetry
    static class Config {

        @Bean
        S3Client s3Client() {
            return Mockito.mock(S3Client.class);
        }

        @Bean
        S3StorageService s3StorageService(S3Client s3Client) {
            BlobstoreProperties properties = new BlobstoreProperties();
            properties.setBucket("test-bucket");
            return new S3StorageService(s3Client, Mockito.mock(S3TransferManager.class), properties,
                Mockito.mock(ExecutorService.class));
        }
    }

    // Injected by interface: @EnableRetry here builds a JDK interface proxy, not a CGLIB subclass.
    @Autowired private StorageService service;
    @Autowired private S3Client s3Client;

    @BeforeEach
    void resetMocks() {
        Mockito.reset(s3Client);
    }

    @Test
    @SuppressWarnings("unchecked")
    void deleteAll_perKeyErrors_throughProxy_surfaceAsStorageProviderException_withoutRetry() {
        when(s3Client.deleteObjects(any(Consumer.class))).thenReturn(DeleteObjectsResponse.builder()
            .errors(S3Error.builder().key("locked.txt").code("AccessDenied").message("denied").build())
            .build());

        assertThatThrownBy(() -> service.deleteAll(List.of("locked.txt")))
            .isExactlyInstanceOf(StorageProviderException.class)
            .hasMessageContaining("locked.txt");
        // Not an SdkException, so not retried.
        verify(s3Client, times(1)).deleteObjects(any(Consumer.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void deleteAll_persistentSdkException_throughProxy_retriesThenRecoversToStorageProviderException() {
        when(s3Client.deleteObjects(any(Consumer.class)))
            .thenThrow(SdkException.builder().message("transient").build());

        assertThatThrownBy(() -> service.deleteAll(List.of("a.txt")))
            .isExactlyInstanceOf(StorageProviderException.class)
            .hasMessageContaining("a.txt");
        verify(s3Client, times(3)).deleteObjects(any(Consumer.class));
    }
}
