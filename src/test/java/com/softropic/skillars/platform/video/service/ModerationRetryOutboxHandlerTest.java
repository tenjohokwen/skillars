package com.softropic.skillars.platform.video.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.event.VideoModerationRetryEvent;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * skillars-deferred-92 AC5.2/AC5.3 — {@link ModerationRetryOutboxHandler} is idempotent: a repeat
 * retry-request for a video that has already left {@code SCANNING} is a documented no-op, not an
 * error.
 *
 * <p>The distinction matters operationally. Throwing would keep the row with {@code attempts++} and
 * eventually trip the {@code [OUTBOX_STUCK]} alert, summoning a human to look at a video whose
 * moderation simply finished normally between the SLA cycle and the drain — an immortal poison row
 * consuming a claim slot for no reason.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("A moderation retry re-drive is idempotent")
class ModerationRetryOutboxHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock private VideoRepository videoRepository;
    @Mock private ApplicationEventPublisher publisher;

    private ModerationRetryOutboxHandler handler() {
        return new ModerationRetryOutboxHandler(videoRepository, publisher, MAPPER);
    }

    private String payload(UUID videoId) throws Exception {
        return MAPPER.writeValueAsString(
            new ModerationOutboxSupport.ModerationRetryPayload(videoId, "owner@example.com"));
    }

    private static Video videoIn(OperationalState state) {
        Video v = new Video();
        v.setOwnerId("owner@example.com");
        v.setOperationalState(state);
        return v;
    }

    @Test
    @DisplayName("a video still in SCANNING is re-dispatched to the moderation pipeline")
    void stillScanning_republishesTheRetryEvent() throws Exception {
        UUID videoId = UUID.randomUUID();
        when(videoRepository.findById(videoId)).thenReturn(Optional.of(videoIn(OperationalState.SCANNING)));

        handler().handle(payload(videoId));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue())
            .isInstanceOfSatisfying(VideoModerationRetryEvent.class,
                e -> assertThat(e.videoId()).isEqualTo(videoId));
    }

    @ParameterizedTest
    @EnumSource(value = OperationalState.class, names = "SCANNING", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("a video that has left SCANNING is a silent no-op, never an error")
    void noLongerScanning_isANoOp(OperationalState state) throws Exception {
        UUID videoId = UUID.randomUUID();
        when(videoRepository.findById(videoId)).thenReturn(Optional.of(videoIn(state)));

        handler().handle(payload(videoId));

        verify(publisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("a video deleted since the SLA cycle is a no-op, not a failure")
    void videoDeleted_isANoOp() throws Exception {
        UUID videoId = UUID.randomUUID();
        when(videoRepository.findById(videoId)).thenReturn(Optional.empty());

        handler().handle(payload(videoId));

        verify(publisher, never()).publishEvent(any());
    }

    /**
     * The one case that must NOT be a no-op. A malformed payload can never succeed on a re-drive, but
     * silently completing the row would drop an intent the outbox promised to keep; throwing puts it
     * behind {@code attempts++} / {@code last_error} where {@code [OUTBOX_STUCK]} can surface it.
     */
    @Test
    @DisplayName("a malformed payload throws so the row is retained rather than dropped")
    void malformedPayload_throws() {
        assertThatThrownBy(() -> handler().handle("{ not json"))
            .isInstanceOf(UncheckedIOException.class);

        verify(publisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("the handler claims the VIDEO_MODERATION_RETRY aggregate type")
    void aggregateType_matchesTheSupport() {
        assertThat(handler().aggregateType())
            .isEqualTo(ModerationOutboxSupport.RETRY_AGGREGATE_TYPE)
            .isEqualTo("VIDEO_MODERATION_RETRY");
    }
}
