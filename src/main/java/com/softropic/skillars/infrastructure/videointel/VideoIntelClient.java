package com.softropic.skillars.infrastructure.videointel;

public interface VideoIntelClient {
    /**
     * Submits a video to Google Cloud VideoIntelligence for explicit content detection.
     * @param videoUrl publicly accessible or signed URL to the video
     * @return scan result — flagged=true means explicit content detected above threshold
     * @throws VideoIntelException if the service is unavailable or times out
     */
    VideoIntelScanResult detectExplicitContent(String videoUrl);

    /**
     * S-18: whether this client really screens video. The stub does not, so it must never be treated as a
     * gate: nothing may be shared on the strength of its answer, and a deployed instance refuses to boot with
     * it enabled (DeployedMediaScanningValidator).
     */
    default boolean isRealScanner() {
        return false;
    }
}
