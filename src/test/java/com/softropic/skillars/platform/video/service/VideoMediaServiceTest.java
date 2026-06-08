package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.video.contract.AccessState;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.exception.VideoNotFoundException;
import com.softropic.skillars.platform.video.contract.exception.VideoProviderException;
import com.softropic.skillars.platform.video.contract.exception.VideoValidationException;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VideoMediaServiceTest {

    @Mock VideoRepository videoRepository;
    @Mock VideoProviderAdapter videoProviderAdapter;
    @InjectMocks VideoMediaService videoMediaService;

    @Test
    void getThumbnailUrl_returnsAdapterUrl() {
        UUID videoId = UUID.randomUUID();
        Video video = activeReadyVideo(videoId, "asset-001");
        when(videoRepository.findById(videoId)).thenReturn(Optional.of(video));
        when(videoProviderAdapter.getThumbnailUrl("asset-001")).thenReturn("https://cdn/asset-001/thumbnail.jpg");

        String result = videoMediaService.getThumbnailUrl(videoId);

        assertThat(result).isEqualTo("https://cdn/asset-001/thumbnail.jpg");
    }

    @Test
    void getThumbnailUrl_videoNotFound_throwsVideoNotFoundException() {
        UUID videoId = UUID.randomUUID();
        when(videoRepository.findById(videoId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> videoMediaService.getThumbnailUrl(videoId))
            .isInstanceOf(VideoNotFoundException.class);
    }

    @Test
    void getThumbnailUrl_deletedVideo_throwsVideoValidationException() {
        UUID videoId = UUID.randomUUID();
        Video video = deletedVideo(videoId, "asset-del");
        when(videoRepository.findById(videoId)).thenReturn(Optional.of(video));

        assertThatThrownBy(() -> videoMediaService.getThumbnailUrl(videoId))
            .isInstanceOf(VideoValidationException.class);
    }

    @Test
    void getThumbnailUrl_unsupportedProvider_wrapsAsVideoProviderException() {
        UUID videoId = UUID.randomUUID();
        Video video = activeReadyVideo(videoId, "asset-001");
        when(videoRepository.findById(videoId)).thenReturn(Optional.of(video));
        when(videoProviderAdapter.getThumbnailUrl(any())).thenThrow(new UnsupportedOperationException("thumbnails not supported"));

        assertThatThrownBy(() -> videoMediaService.getThumbnailUrl(videoId))
            .isInstanceOf(VideoProviderException.class);
    }

    @Test
    void addCaptionTrack_deletedVideo_throwsVideoValidationException() {
        UUID videoId = UUID.randomUUID();
        Video video = deletedVideo(videoId, "asset-del");
        when(videoRepository.findById(videoId)).thenReturn(Optional.of(video));

        assertThatThrownBy(() -> videoMediaService.addCaptionTrack(videoId, "en", "https://example.com/cap.srt"))
            .isInstanceOf(VideoValidationException.class);
    }

    @Test
    void addCaptionTrack_unsupportedProvider_wrapsAsVideoProviderException() {
        UUID videoId = UUID.randomUUID();
        Video video = activeReadyVideo(videoId, "asset-001");
        when(videoRepository.findById(videoId)).thenReturn(Optional.of(video));
        doThrow(new UnsupportedOperationException("captions not supported"))
            .when(videoProviderAdapter).addCaptionTrack(any(), any(), any());

        assertThatThrownBy(() -> videoMediaService.addCaptionTrack(videoId, "en", "https://example.com/cap.srt"))
            .isInstanceOf(VideoProviderException.class);
    }

    // --- helpers ---

    private Video activeReadyVideo(UUID id, String providerAssetId) {
        Video v = new Video();
        v.setId(id);
        v.setProviderAssetId(providerAssetId);
        v.setOperationalState(OperationalState.READY);
        v.setAccessState(AccessState.ACTIVE);
        return v;
    }

    private Video deletedVideo(UUID id, String providerAssetId) {
        Video v = new Video();
        v.setId(id);
        v.setProviderAssetId(providerAssetId);
        v.setOperationalState(OperationalState.DELETED);
        v.setAccessState(AccessState.ACTIVE);
        return v;
    }
}
