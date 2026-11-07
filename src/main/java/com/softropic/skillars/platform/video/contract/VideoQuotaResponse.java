package com.softropic.skillars.platform.video.contract;

import java.util.List;
import java.util.Map;

/**
 * @param maxUploadBytes   per video type, the largest file the server accepts (config
 *                         {@code video.<type>.maxSizeBytes}); the upload page checks it before
 *                         sending anything. Owner decision 2026-11-07 (Pitchside VID-05, option A).
 * @param allowedMimeTypes the upload MIME types the server accepts ({@code app.video.upload})
 */
public record VideoQuotaResponse(
        long storageUsedBytes,
        long storageLimitBytes,
        long bandwidthUsedBytes,
        long bandwidthLimitBytes,
        String tier,
        Map<VideoType, Long> maxUploadBytes,
        List<String> allowedMimeTypes) {}
