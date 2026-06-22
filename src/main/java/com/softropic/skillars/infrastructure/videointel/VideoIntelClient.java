package com.softropic.skillars.infrastructure.videointel;

public interface VideoIntelClient {
    /**
     * Submits a video to Google Cloud VideoIntelligence for explicit content detection.
     * @param videoUrl publicly accessible or signed URL to the video
     * @return scan result — flagged=true means explicit content detected above threshold
     * @throws VideoIntelException if the service is unavailable or times out
     */
    VideoIntelScanResult detectExplicitContent(String videoUrl);
}
