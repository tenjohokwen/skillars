package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.contract.CoachSubscriptionTier;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class QuotaConfigService {

    private final ConfigService configService;
    private final CoachProfileService coachProfileService;

    // skillars-deferred-107 AC2: floor 0, not 1 — scout is seeded storageBytes/bandwidth = 0
    // deliberately ("no upload", V53). A negative quota is the only genuinely broken state; the
    // upper bound is irrelevant here so it stays Long.MAX_VALUE. Missing key still throws (operator
    // must seed it). Mirrors ConfigBounds.video.quota.*.
    private static final long QUOTA_BYTES_MIN = 0L;
    private static final long QUOTA_BYTES_MAX = Long.MAX_VALUE;
    // 0 would expire every upload reservation instantly; a day is already an absurd ceiling.
    private static final long RESERVATION_TIMEOUT_MIN_MINUTES = 1L;
    private static final long RESERVATION_TIMEOUT_MAX_MINUTES = 1440L;

    public long getStorageQuotaBytes(String ownerId) {
        String tier = resolveTierKey(ownerId);
        return configService.getBoundedLong("video.quota." + tier + ".storageBytes",
            QUOTA_BYTES_MIN, QUOTA_BYTES_MAX);
    }

    public long getBandwidthQuotaBytesMonthly(String ownerId) {
        String tier = resolveTierKey(ownerId);
        return configService.getBoundedLong("video.quota." + tier + ".bandwidthBytesMonthly",
            QUOTA_BYTES_MIN, QUOTA_BYTES_MAX);
    }

    public long getReservationTimeoutMinutes() {
        return configService.getBoundedLong("platform.video_reservation_timeout_minutes",
            RESERVATION_TIMEOUT_MIN_MINUTES, RESERVATION_TIMEOUT_MAX_MINUTES);
    }

    private String resolveTierKey(String ownerId) {
        // Attempt coach UUID lookup first; if not found, default to player flow (Story 6.6+)
        try {
            UUID coachId = UUID.fromString(ownerId);
            CoachSubscriptionTier tier = coachProfileService.getCoachSubscriptionTier(coachId);
            return switch (tier) {
                case SCOUT      -> "scout";
                case INSTRUCTOR -> "instructor";
                case ACADEMY    -> "academy";
            };
        } catch (IllegalArgumentException e) {
            // ownerId is not a UUID — normal path for player Long IDs (Story 6.6)
            log.debug("Non-UUID ownerId '{}' — defaulting to athlete tier for quota lookup", ownerId);
            return "athlete";
        }
    }
}
