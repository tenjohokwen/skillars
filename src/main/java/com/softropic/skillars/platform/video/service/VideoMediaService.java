package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.exception.VideoNotFoundException;
import com.softropic.skillars.platform.video.contract.exception.VideoProviderException;
import com.softropic.skillars.platform.video.contract.exception.VideoValidationException;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoMediaService {

    private final VideoRepository videoRepository;
    private final VideoProviderAdapter videoProviderAdapter;

    @Observed(name = "video.media.getThumbnailUrl")
    public String getThumbnailUrl(UUID videoId) {
        MDC.put("operation", "media.getThumbnailUrl");
        MDC.put("videoId", videoId.toString());
        try {
            Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new VideoNotFoundException(videoId));
            if (video.getOperationalState() == OperationalState.DELETED) {
                throw new VideoValidationException("Cannot get thumbnail for a deleted video");
            }
            try {
                return videoProviderAdapter.getThumbnailUrl(video.getProviderAssetId());
            } catch (UnsupportedOperationException e) {
                throw new VideoProviderException("getThumbnailUrl: not supported by active provider", e);
            }
        } finally {
            MDC.remove("operation");
            MDC.remove("videoId");
        }
    }

    @Observed(name = "video.media.addCaptionTrack")
    public void addCaptionTrack(UUID videoId, String language, String captionFileUrl) {
        MDC.put("operation", "media.addCaptionTrack");
        MDC.put("videoId", videoId.toString());
        try {
            Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new VideoNotFoundException(videoId));
            if (video.getOperationalState() == OperationalState.DELETED) {
                throw new VideoValidationException("Cannot add caption track to a deleted video");
            }
            try {
                videoProviderAdapter.addCaptionTrack(video.getProviderAssetId(), language, captionFileUrl);
            } catch (UnsupportedOperationException e) {
                throw new VideoProviderException("addCaptionTrack: not supported by active provider", e);
            }
        } finally {
            MDC.remove("operation");
            MDC.remove("videoId");
        }
    }
}
