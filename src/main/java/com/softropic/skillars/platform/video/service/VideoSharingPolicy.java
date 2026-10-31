package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.video.config.VideoProperties;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoModerationScanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * S-18 (decision B): a video may be played by anyone besides its uploader (and an admin) only once a REAL video
 * scanner has passed it. The scanner is a stub today ({@code VideoIntelClientImpl} refuses to give a verdict), so
 * the {@code VIDEOINTEL} layer never records {@code PASSED} and every video stays uploader-only, whatever else the
 * pipeline did with it (it still transcodes to READY so the uploader can watch it).
 *
 * <p>Per video rather than per instance, on purpose: when a real scanner arrives, videos uploaded in the meantime
 * carry no {@code PASSED} record and stay private until they are scanned, instead of becoming public wholesale.
 * {@code app.video.moderation.requires-real-scanner=false} lifts the gate (default true).
 */
@Component
@RequiredArgsConstructor
public class VideoSharingPolicy {

    static final String SCANNER_LAYER = "VIDEOINTEL";
    static final String PASSED = "PASSED";

    private final VideoProperties properties;
    private final VideoModerationScanRepository scanRepository;

    public boolean maySharePastOwner(Video video) {
        if (!properties.getModeration().isRequiresRealScanner()) {
            return true;
        }
        return scanRepository.findByVideoIdAndLayer(video.getId(), SCANNER_LAYER)
            .map(scan -> PASSED.equals(scan.getOutcome()))
            .orElse(false);
    }
}
