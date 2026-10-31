package com.softropic.skillars.platform.filestorage.service;

import com.softropic.skillars.infrastructure.arachnid.ArachnidClient;
import com.softropic.skillars.infrastructure.arachnid.ArachnidException;
import com.softropic.skillars.infrastructure.arachnid.ArachnidProperties;
import com.softropic.skillars.infrastructure.arachnid.ArachnidScanResult;
import com.softropic.skillars.platform.filestorage.event.StorageObjectConfirmedEvent;
import com.softropic.skillars.platform.filestorage.repo.FileStorageObject;
import com.softropic.skillars.platform.filestorage.repo.FileStorageObjectRepository;
import com.softropic.skillars.platform.filestorage.repo.ScanStatus;
import com.softropic.skillars.platform.security.contract.event.UserSuspensionRequestedEvent;
import com.softropic.skillars.platform.video.contract.event.VideoModerationAdminAlertEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaScanServiceTest {

    @Mock FileStorageObjectRepository repository;
    @Mock FileStorageService fileStorageService;
    @Mock ArachnidClient arachnidClient;
    @Mock MediaScanPolicy mediaScanPolicy;
    @Mock ApplicationEventPublisher publisher;
    @Mock TransactionTemplate transactionTemplate;

    private final ArachnidProperties properties = new ArachnidProperties();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private MediaScanService service;

    @BeforeEach
    void setUp() {
        service = new MediaScanService(repository, fileStorageService, arachnidClient, properties, mediaScanPolicy,
            publisher, transactionTemplate, meters);
        lenient().when(mediaScanPolicy.scanningEnabled()).thenReturn(true);
        lenient().when(fileStorageService.signedScanUrl("k")).thenReturn("https://s3/k?sig");
        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });
        lenient().when(repository.findById(1L)).thenReturn(Optional.of(image(ScanStatus.PENDING, 0)));
    }

    private static FileStorageObject image(ScanStatus status, int attempts) {
        FileStorageObject fso = FileStorageObject.builder().key("k").ownerId("42").provider("s3").bucket("b")
            .sizeBytes(3).contentType("image/png").uploadConfirmedAt(Instant.now())
            .scanStatus(status).scanAttempts(attempts).build();
        org.springframework.test.util.ReflectionTestUtils.setField(fso, "id", 1L);
        return fso;
    }

    private double count(String result) {
        var counter = meters.find("skillars.media.scan").tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void clean_marksTheObjectClean() {
        when(arachnidClient.scan("https://s3/k?sig")).thenReturn(new ArachnidScanResult(false, null));
        when(repository.markScanClean(eq(1L), any())).thenReturn(1);

        service.scan(1L);

        verify(repository).markScanClean(eq(1L), any());
        assertThat(count("clean")).isEqualTo(1);
    }

    @Test
    void match_flags_quarantinesTheOwnersOtherObjects_alertsUrgently_andSuspends() {
        when(arachnidClient.scan(any())).thenReturn(new ArachnidScanResult(true, "CSAM"));
        when(repository.quarantineOtherObjectsOfOwner("42", 1L)).thenReturn(3);

        service.scan(1L);

        verify(repository).markScanFlagged(eq(1L), any());
        verify(repository, never()).markScanClean(anyLong(), any());
        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(publisher, org.mockito.Mockito.atLeastOnce()).publishEvent(events.capture());
        VideoModerationAdminAlertEvent alert = events.getAllValues().stream()
            .filter(VideoModerationAdminAlertEvent.class::isInstance).map(VideoModerationAdminAlertEvent.class::cast)
            .findFirst().orElseThrow();
        assertThat(alert.urgent()).isTrue();
        assertThat(alert.body()).contains("quarantinedOtherObjects=3");
        assertThat(events.getAllValues()).contains(new UserSuspensionRequestedEvent(42L, "MEDIA_SCAN_MATCH"));
        assertThat(count("flagged")).isEqualTo(1);
    }

    @Test
    void match_suspensionFails_raisesASecondUrgentAlert() {
        when(arachnidClient.scan(any())).thenReturn(new ArachnidScanResult(true, "CSAM"));
        org.mockito.Mockito.doThrow(new IllegalStateException("gone")).when(publisher)
            .publishEvent(org.mockito.ArgumentMatchers.isA(UserSuspensionRequestedEvent.class));

        service.scan(1L);

        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(publisher, org.mockito.Mockito.atLeastOnce()).publishEvent(events.capture());
        assertThat(events.getAllValues().stream().filter(VideoModerationAdminAlertEvent.class::isInstance)
            .map(e -> ((VideoModerationAdminAlertEvent) e).subject()))
            .anyMatch(s -> s.contains("suspension FAILED"));
    }

    @Test
    void scannerDown_failsClosed_objectStaysPending_attemptCounted_noVerdictWritten() {
        when(arachnidClient.scan(any())).thenThrow(new ArachnidException("timeout", null));

        service.scan(1L);

        verify(repository).recordScanAttempt(eq(1L), any());
        verify(repository, never()).markScanClean(anyLong(), any());
        verify(repository, never()).markScanFlagged(anyLong(), any());
        assertThat(count("unavailable")).isEqualTo(1);
    }

    @Test
    void unexpectedRuntimeExceptionFromTheScanner_isAlsoNoVerdict_neverClean() {
        when(arachnidClient.scan(any())).thenThrow(new IllegalArgumentException("bad payload"));

        service.scan(1L);

        verify(repository, never()).markScanClean(anyLong(), any());
        verify(repository).recordScanAttempt(eq(1L), any());
    }

    @Test
    void emptyScanResult_isNoVerdict() {
        when(arachnidClient.scan(any())).thenReturn(null);

        service.scan(1L);

        verify(repository, never()).markScanClean(anyLong(), any());
    }

    @Test
    void fifthFailedAttempt_alertsAnAdminOnce() {
        when(repository.findById(1L)).thenReturn(Optional.of(image(ScanStatus.PENDING, 4)));
        when(arachnidClient.scan(any())).thenThrow(new ArachnidException("down", null));

        service.scan(1L);

        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(events.capture());
        assertThat(((VideoModerationAdminAlertEvent) events.getValue()).urgent()).isFalse();
    }

    @Test
    void scanningDisabled_objectStaysWithheld_scannerNotCalled() {
        when(mediaScanPolicy.scanningEnabled()).thenReturn(false);

        service.scan(1L);

        verify(arachnidClient, never()).scan(any());
        verify(repository, never()).markScanClean(anyLong(), any());
    }

    @Test
    void alreadyDecidedOrDeletedObject_isLeftAlone() {
        when(repository.findById(1L)).thenReturn(Optional.of(image(ScanStatus.QUARANTINED, 0)));

        service.scan(1L);

        verify(arachnidClient, never()).scan(any());
    }

    @Test
    void confirmedEvent_scansOnlyPendingObjects() {
        FileStorageObject notScannable = image(ScanStatus.NOT_REQUIRED, 0);

        service.onConfirmed(new StorageObjectConfirmedEvent(this, notScannable));

        verify(arachnidClient, never()).scan(any());
    }

    @Test
    void retryPending_scansEveryDueObject_andIsANoOpWhileScanningIsOff() {
        when(repository.findPendingScanDue(any(Instant.class), any(Pageable.class))).thenReturn(List.of(image(ScanStatus.PENDING, 1)));
        when(arachnidClient.scan(any())).thenReturn(new ArachnidScanResult(false, null));

        service.retryPending();
        verify(arachnidClient).scan(any());

        when(mediaScanPolicy.scanningEnabled()).thenReturn(false);
        service.retryPending();
        verify(arachnidClient, org.mockito.Mockito.times(1)).scan(any());
    }
}
