package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.video.contract.VideoType;
import com.softropic.skillars.platform.video.contract.exception.VideoValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class VideoTypeConstraints {

    private final ConfigService configService;

    // skillars-deferred-107 AC2: 0 would reject every upload of the type. Missing key still throws
    // (operator must seed it). 24h is an absurd per-clip ceiling. Mirrors ConfigBounds.video.*.
    private static final long MAX_SIZE_BYTES_MIN = 1L;
    private static final long MAX_SIZE_BYTES_MAX = Long.MAX_VALUE;
    private static final long MAX_DURATION_SECONDS_MIN = 1L;
    private static final long MAX_DURATION_SECONDS_MAX = 86400L;

    public long getMaxSizeBytes(VideoType type) {
        return configService.getBoundedLong(configKey(type, "maxSizeBytes"),
            MAX_SIZE_BYTES_MIN, MAX_SIZE_BYTES_MAX);
    }

    public int getMaxDurationSeconds(VideoType type) {
        return (int) configService.getBoundedLong(configKey(type, "maxDurationSeconds"),
            MAX_DURATION_SECONDS_MIN, MAX_DURATION_SECONDS_MAX);
    }

    public void validate(VideoType type, long fileSizeBytes, int durationSeconds) {
        if (fileSizeBytes <= 0) {
            throw new VideoValidationException(
                "fileSizeBytes must be positive for type " + type + " but was " + fileSizeBytes);
        }
        long maxBytes = getMaxSizeBytes(type);
        int maxDuration = getMaxDurationSeconds(type);
        if (fileSizeBytes > maxBytes) {
            throw new VideoValidationException(
                "File size " + fileSizeBytes + " exceeds limit " + maxBytes + " for type " + type);
        }
        if (durationSeconds < 0) {
            throw new VideoValidationException(
                "durationSeconds must not be negative for type " + type + " but was " + durationSeconds);
        }
        if (durationSeconds > 0 && durationSeconds > maxDuration) {
            throw new VideoValidationException(
                "Duration " + durationSeconds + "s exceeds limit " + maxDuration + "s for type " + type);
        }
    }

    private String configKey(VideoType type, String metric) {
        return switch (type) {
            case HOMEWORK     -> "video.homework." + metric;
            case DRILL_DEMO   -> "video.drillDemo." + metric;
            case COACH_REVIEW -> "video.coachReview." + metric;
        };
    }
}
