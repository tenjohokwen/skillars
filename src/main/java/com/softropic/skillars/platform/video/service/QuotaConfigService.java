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

    public long getStorageQuotaBytes(String ownerId) {
        String tier = resolveTierKey(ownerId);
        return configService.getLong("video.quota." + tier + ".storageBytes");
    }

    public long getBandwidthQuotaBytesMonthly(String ownerId) {
        String tier = resolveTierKey(ownerId);
        return configService.getLong("video.quota." + tier + ".bandwidthBytesMonthly");
    }

    public long getReservationTimeoutMinutes() {
        return configService.getLong("platform.video_reservation_timeout_minutes");
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
