package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.video.contract.AccessState;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.event.VideoStatusChangedEvent;
import com.softropic.skillars.platform.video.contract.exception.TerminalStateViolationException;
import com.softropic.skillars.platform.video.contract.exception.VideoNotFoundException;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VideoLifecycleServiceTest {

    @Mock VideoRepository videoRepository;
    @Mock ApplicationEventPublisher publisher;
    @Mock MeterRegistry meterRegistry;
    @Mock Counter counter;

    @InjectMocks
    VideoLifecycleService service;

    @BeforeEach
    void setUp() {
        lenient().when(meterRegistry.counter(anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(counter);
        lenient().doNothing().when(counter).increment();
    }

    private Video videoWith(UUID id, OperationalState op, AccessState ac) {
        Video v = new Video();
        v.setOperationalState(op);
        v.setAccessState(ac);
        return v;
    }

    // --- transitionOperationalState ---

    @Test
    void transitionOperationalState_validTransitions_succeed() {
        UUID id = UUID.randomUUID();

        record Case(OperationalState from, OperationalState to) {}
        var cases = new Case[]{
            new Case(OperationalState.UPLOADING, OperationalState.PROCESSING),
            new Case(OperationalState.UPLOADING, OperationalState.FAILED),
            new Case(OperationalState.PROCESSING, OperationalState.SCANNING),
            new Case(OperationalState.PROCESSING, OperationalState.FAILED),
            new Case(OperationalState.SCANNING, OperationalState.TRANSCODING),
            new Case(OperationalState.SCANNING, OperationalState.LOCKED),
            new Case(OperationalState.SCANNING, OperationalState.HIDDEN),
            new Case(OperationalState.SCANNING, OperationalState.FAILED),
            new Case(OperationalState.TRANSCODING, OperationalState.READY),
            new Case(OperationalState.TRANSCODING, OperationalState.FAILED),
            new Case(OperationalState.FAILED, OperationalState.UPLOADING)
        };

        for (Case c : cases) {
            Video v = videoWith(id, c.from(), AccessState.ACTIVE);
            when(videoRepository.findById(id)).thenReturn(Optional.of(v));
            when(videoRepository.save(v)).thenReturn(v);

            Video result = service.transitionOperationalState(id, c.to());

            assertThat(result.getOperationalState()).isEqualTo(c.to());
        }
    }

    @Test
    void transitionOperationalState_publishesVideoStatusChangedEvent() {
        UUID id = UUID.randomUUID();
        Video v = videoWith(id, OperationalState.PROCESSING, AccessState.ACTIVE);
        when(videoRepository.findById(id)).thenReturn(Optional.of(v));
        when(videoRepository.save(v)).thenReturn(v);

        service.transitionOperationalState(id, OperationalState.SCANNING);

        ArgumentCaptor<VideoStatusChangedEvent> captor = ArgumentCaptor.forClass(VideoStatusChangedEvent.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue().videoId()).isEqualTo(id);
        assertThat(captor.getValue().newState()).isEqualTo(OperationalState.SCANNING);
    }

    @Test
    void transitionToScanning_setsScanningStartedAt() {
        UUID id = UUID.randomUUID();
        Video v = videoWith(id, OperationalState.PROCESSING, AccessState.ACTIVE);
        when(videoRepository.findById(id)).thenReturn(Optional.of(v));
        when(videoRepository.save(v)).thenReturn(v);

        service.transitionOperationalState(id, OperationalState.SCANNING);

        assertThat(v.getScanningStartedAt()).isNotNull();
    }

    @Test
    void processingToReady_bypass_logsAndIncrementsCounter() {
        UUID id = UUID.randomUUID();
        Video v = videoWith(id, OperationalState.PROCESSING, AccessState.ACTIVE);
        when(videoRepository.findById(id)).thenReturn(Optional.of(v));
        when(videoRepository.save(v)).thenReturn(v);

        // PROCESSING→READY is a bypass path (not in VALID_TRANSITIONS for PROCESSING after Story 6.3)
        // but it is allowed via the explicit compat check in the service
        service.transitionOperationalState(id, OperationalState.READY);

        verify(meterRegistry).counter("video.moderation.bypass", "from", "PROCESSING", "to", "READY");
        verify(counter).increment();
    }

    @Test
    void lockedState_isTerminal_throwsOnAnyTransition() {
        UUID id = UUID.randomUUID();
        Video v = videoWith(id, OperationalState.LOCKED, AccessState.ACTIVE);
        when(videoRepository.findById(id)).thenReturn(Optional.of(v));

        assertThatThrownBy(() -> service.transitionOperationalState(id, OperationalState.READY))
            .isInstanceOf(TerminalStateViolationException.class);
        verify(videoRepository, never()).save(any());
    }

    @Test
    void hiddenState_story66_allowsTranscodingAndRejected() {
        // Story 6.6: HIDDEN is no longer terminal — parental approval drives HIDDEN→TRANSCODING or HIDDEN→REJECTED
        UUID id = UUID.randomUUID();
        Video v = videoWith(id, OperationalState.HIDDEN, AccessState.ACTIVE);
        when(videoRepository.findById(id)).thenReturn(Optional.of(v));
        when(videoRepository.save(v)).thenReturn(v);

        Video result = service.transitionOperationalState(id, OperationalState.TRANSCODING);
        assertThat(result.getOperationalState()).isEqualTo(OperationalState.TRANSCODING);
    }

    @Test
    void transitionOperationalState_sameState_isIdempotent() {
        UUID id = UUID.randomUUID();
        Video v = videoWith(id, OperationalState.UPLOADING, AccessState.ACTIVE);
        when(videoRepository.findById(id)).thenReturn(Optional.of(v));

        Video result = service.transitionOperationalState(id, OperationalState.UPLOADING);

        assertThat(result).isSameAs(v);
        verify(videoRepository, never()).save(any());
    }

    @Test
    void transitionOperationalState_deleted_throwsTerminalViolation() {
        UUID id = UUID.randomUUID();
        Video v = videoWith(id, OperationalState.DELETED, AccessState.ACTIVE);
        when(videoRepository.findById(id)).thenReturn(Optional.of(v));

        assertThatThrownBy(() -> service.transitionOperationalState(id, OperationalState.UPLOADING))
            .isInstanceOf(TerminalStateViolationException.class);
        verify(videoRepository, never()).save(any());
    }

    @Test
    void transitionOperationalState_invalidTransition_throws() {
        UUID id = UUID.randomUUID();

        record Case(OperationalState from, OperationalState to) {}
        var cases = new Case[]{
            new Case(OperationalState.READY, OperationalState.UPLOADING),
            new Case(OperationalState.PROCESSING, OperationalState.UPLOADING),
            new Case(OperationalState.SCANNING, OperationalState.PROCESSING)
        };

        for (Case c : cases) {
            Video v = videoWith(id, c.from(), AccessState.ACTIVE);
            when(videoRepository.findById(id)).thenReturn(Optional.of(v));

            assertThatThrownBy(() -> service.transitionOperationalState(id, c.to()))
                .isInstanceOf(TerminalStateViolationException.class);
        }
        verify(videoRepository, never()).save(any());
    }

    // --- setAccessState ---

    @Test
    void setAccessState_onDeletedVideo_throws() {
        UUID id = UUID.randomUUID();
        Video v = videoWith(id, OperationalState.DELETED, AccessState.ACTIVE);
        when(videoRepository.findById(id)).thenReturn(Optional.of(v));

        assertThatThrownBy(() -> service.setAccessState(id, AccessState.BLOCKED))
            .isInstanceOf(TerminalStateViolationException.class);
        verify(videoRepository, never()).save(any());
    }

    @Test
    void setAccessState_onNonDeletedVideo_succeeds() {
        UUID id = UUID.randomUUID();
        Video v = videoWith(id, OperationalState.UPLOADING, AccessState.ACTIVE);
        when(videoRepository.findById(id)).thenReturn(Optional.of(v));
        when(videoRepository.save(v)).thenReturn(v);

        Video result = service.setAccessState(id, AccessState.BLOCKED);

        assertThat(result.getAccessState()).isEqualTo(AccessState.BLOCKED);
        verify(videoRepository).save(v);
    }

    // --- isPlaybackEligible ---

    @Test
    void isPlaybackEligible_readyAndActive_returnsTrue() {
        UUID id = UUID.randomUUID();
        Video v = videoWith(id, OperationalState.READY, AccessState.ACTIVE);
        when(videoRepository.findById(id)).thenReturn(Optional.of(v));

        assertThat(service.isPlaybackEligible(id)).isTrue();
    }

    @Test
    void isPlaybackEligible_notReady_returnsFalse() {
        UUID id = UUID.randomUUID();

        for (OperationalState op : new OperationalState[]{
            OperationalState.UPLOADING, OperationalState.PROCESSING,
            OperationalState.SCANNING, OperationalState.TRANSCODING,
            OperationalState.LOCKED, OperationalState.HIDDEN, OperationalState.FAILED
        }) {
            Video v = videoWith(id, op, AccessState.ACTIVE);
            when(videoRepository.findById(id)).thenReturn(Optional.of(v));

            assertThat(service.isPlaybackEligible(id)).isFalse();
        }
    }

    @Test
    void isPlaybackEligible_notActive_returnsFalse() {
        UUID id = UUID.randomUUID();

        for (AccessState ac : new AccessState[]{AccessState.BLOCKED, AccessState.ARCHIVED}) {
            Video v = videoWith(id, OperationalState.READY, ac);
            when(videoRepository.findById(id)).thenReturn(Optional.of(v));

            assertThat(service.isPlaybackEligible(id)).isFalse();
        }
    }

    @Test
    void isPlaybackEligible_videoNotFound_throws() {
        UUID id = UUID.randomUUID();
        when(videoRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.isPlaybackEligible(id))
            .isInstanceOf(VideoNotFoundException.class);
    }
}
