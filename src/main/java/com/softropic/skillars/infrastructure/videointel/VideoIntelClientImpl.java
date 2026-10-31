package com.softropic.skillars.infrastructure.videointel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestTemplate;

// CRITICAL: VideoIntelClientImpl is a STUB, not a scanner (S-18). It used to return "clean" for every video, so
// enabling VIDEOINTEL_ENABLED silently waved everything through Layer 2. It now FAILS CLOSED: it throws, the
// orchestrator records the layer FAILED, alerts an admin and leaves the video in SCANNING. It can never produce
// an approving result, and isRealScanner() is false, so videos stay owner-only (VideoSharingPolicy).
// Real implementation (decision C, still open) requires a real video scanner behind this interface.
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
        log.warn("VideoIntelClientImpl is a stub — refusing to produce a verdict (fail closed)");
        throw new VideoIntelException("Video scanner is not implemented; no verdict can be given", null);
    }

    @Override
    public boolean isRealScanner() {
        return false;
    }
}
