package com.softropic.skillars.infrastructure.videointel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestTemplate;

// CRITICAL: VideoIntelClientImpl is a fail-open stub — it returns clean for every video.
// VIDEOINTEL_ENABLED feature flag defaults to false so it will be skipped in production.
// Real implementation (Story 6.x) requires com.google.cloud:google-cloud-video-intelligence.
// DANGER: enabling VIDEOINTEL_ENABLED=true with this stub means ALL videos pass Layer 2 silently.
@Slf4j
public class VideoIntelClientImpl implements VideoIntelClient {

    private final RestTemplate restTemplate;
    private final String projectId;
    private final double flagThreshold;

    public VideoIntelClientImpl(RestTemplate restTemplate, String projectId, double flagThreshold) {
        this.restTemplate = restTemplate;
        this.projectId = projectId;
        this.flagThreshold = flagThreshold;
    }

    @Override
    public VideoIntelScanResult detectExplicitContent(String videoUrl) {
        log.warn("VideoIntelClientImpl is a stub — returning clean result for videoUrl={}", videoUrl);
        return new VideoIntelScanResult(false, null, "stub-not-implemented");
    }
}
