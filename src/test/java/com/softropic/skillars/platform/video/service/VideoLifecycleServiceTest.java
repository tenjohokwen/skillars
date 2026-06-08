package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.video.contract.AccessState;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.exception.TerminalStateViolationException;
import com.softropic.skillars.platform.video.contract.exception.VideoNotFoundException;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoLifecycleServiceTest {

    @Mock
    VideoRepository videoRepository;

    @InjectMocks
    VideoLifecycleService service;

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
            new Case(OperationalState.PROCESSING, OperationalState.READY),
            new Case(OperationalState.PROCESSING, OperationalState.FAILED),
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
            new Case(OperationalState.PROCESSING, OperationalState.UPLOADING)
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
            OperationalState.UPLOADING, OperationalState.PROCESSING, OperationalState.FAILED
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
