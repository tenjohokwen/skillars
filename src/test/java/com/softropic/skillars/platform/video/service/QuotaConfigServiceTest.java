package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.contract.CoachSubscriptionTier;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * skillars-deferred-107 AC2 + code review: every {@code video.quota.*} / reservation-timeout read
 * goes through the range-bounded accessor. Revert any call site to {@code getLong(key)} and the
 * matching {@code verify(...)} here fails.
 */
@ExtendWith(MockitoExtension.class)
class QuotaConfigServiceTest {

    @Mock ConfigService configService;
    @Mock CoachProfileService coachProfileService;

    @InjectMocks QuotaConfigService quotaConfigService;

    @Test
    void storageQuota_nonUuidOwner_readsAthleteKeyThroughBoundedAccessor_floorZero() {
        // Non-UUID ownerId → "athlete" tier. Floor is 0 (scout is seeded storageBytes = 0).
        when(configService.getBoundedLong("video.quota.athlete.storageBytes", 0L, Long.MAX_VALUE))
            .thenReturn(2_147_483_648L);

        assertThat(quotaConfigService.getStorageQuotaBytes("player-123")).isEqualTo(2_147_483_648L);
        verify(configService).getBoundedLong("video.quota.athlete.storageBytes", 0L, Long.MAX_VALUE);
    }

    @Test
    void bandwidthQuota_coachOwner_readsTierKeyThroughBoundedAccessor() {
        UUID coachId = UUID.randomUUID();
        when(coachProfileService.getCoachSubscriptionTier(coachId)).thenReturn(CoachSubscriptionTier.INSTRUCTOR);
        when(configService.getBoundedLong("video.quota.instructor.bandwidthBytesMonthly", 0L, Long.MAX_VALUE))
            .thenReturn(53_687_091_200L);

        assertThat(quotaConfigService.getBandwidthQuotaBytesMonthly(coachId.toString()))
            .isEqualTo(53_687_091_200L);
        verify(configService).getBoundedLong("video.quota.instructor.bandwidthBytesMonthly", 0L, Long.MAX_VALUE);
    }

    @Test
    void reservationTimeout_readsThroughBoundedAccessor_withOneMinuteFloor() {
        when(configService.getBoundedLong("platform.video_reservation_timeout_minutes", 1L, 1440L))
            .thenReturn(60L);

        assertThat(quotaConfigService.getReservationTimeoutMinutes()).isEqualTo(60L);
        verify(configService).getBoundedLong("platform.video_reservation_timeout_minutes", 1L, 1440L);
    }
}
