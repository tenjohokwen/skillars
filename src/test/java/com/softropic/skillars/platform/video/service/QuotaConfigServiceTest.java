package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.contract.CoachSubscriptionTier;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.payment.repo.PaymentPlayerSubscription;
import com.softropic.skillars.platform.payment.repo.PaymentPlayerSubscriptionRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    @Mock PaymentPlayerSubscriptionRepository paymentPlayerSubscriptionRepository;
    @Mock VideoMetrics videoMetrics;

    @InjectMocks QuotaConfigService quotaConfigService;

    @Test
    void storageQuota_nonUuidNonNumericOwner_readsAthleteKeyThroughBoundedAccessor_floorZero() {
        // ownerId is neither a UUID nor a Long → "athlete" tier without ever touching the
        // subscription repository (skillars-deferred-113 AC3's fail-open path). Floor is 0 (scout
        // is seeded storageBytes = 0).
        when(configService.getBoundedLong("video.quota.athlete.storageBytes", 0L, Long.MAX_VALUE))
            .thenReturn(2_147_483_648L);

        assertThat(quotaConfigService.getStorageQuotaBytes("player-123")).isEqualTo(2_147_483_648L);
        verify(configService).getBoundedLong("video.quota.athlete.storageBytes", 0L, Long.MAX_VALUE);
        verifyNoInteractions(paymentPlayerSubscriptionRepository);
    }

    // skillars-deferred-113 AC3: a Long ownerId with no PaymentPlayerSubscription row falls open to
    // athlete, same fail-open posture as every other unmapped case.
    // skillars-deferred-114 AC3: this specific shape (a syntactically-valid player id with zero
    // subscription rows) must also fire a distinct audit-trail counter — a data-integrity signal,
    // not just a routine fallback.
    @Test
    void storageQuota_longOwnerWithNoSubscriptionRow_readsAthleteKey_andRecordsFallbackMetric() {
        when(paymentPlayerSubscriptionRepository.findByPlayerId(42L)).thenReturn(Optional.empty());
        when(configService.getBoundedLong("video.quota.athlete.storageBytes", 0L, Long.MAX_VALUE))
            .thenReturn(2_147_483_648L);

        assertThat(quotaConfigService.getStorageQuotaBytes("42")).isEqualTo(2_147_483_648L);
        verify(videoMetrics).recordQuotaTierFallback("no_subscription_row");
    }

    // skillars-deferred-114 AC3: documents from day one that this fallback fires once per logical
    // caller's own multi-call shape (e.g. QuotaService.check(...) immediately followed by
    // reserve(...) for the same ownerId, or VideoResource's storage-then-bandwidth pair) — so an
    // operator reading the raw metric count never mistakes it for "distinct players"/"distinct
    // requests". Mirrors QuotaService's own check-then-reserve sequence: two independent calls into
    // this resolver for the same no-subscription-row player.
    @Test
    void noSubscriptionRow_calledTwiceForSameLogicalRequest_recordsFallbackMetricTwiceNotOnce() {
        when(paymentPlayerSubscriptionRepository.findByPlayerId(42L)).thenReturn(Optional.empty());
        when(configService.getBoundedLong("video.quota.athlete.storageBytes", 0L, Long.MAX_VALUE))
            .thenReturn(2_147_483_648L);
        when(configService.getBoundedLong("video.quota.athlete.bandwidthBytesMonthly", 0L, Long.MAX_VALUE))
            .thenReturn(10_737_418_240L);

        // Mirrors QuotaService.check(ownerId, ...) immediately followed by reserve(ownerId, ...) —
        // two distinct resolver calls for the same logical upload-initiation request.
        quotaConfigService.getStorageQuotaBytes("42");
        quotaConfigService.getBandwidthQuotaBytesMonthly("42");

        verify(videoMetrics, times(2)).recordQuotaTierFallback("no_subscription_row");
    }

    // skillars-deferred-114 AC3: the two already-logged fallback shapes (non-UUID/non-Long ownerId;
    // unrecognised stored tier string) must NOT fire this new counter — it is scoped to the
    // genuinely-silent no-subscription-row shape only.
    @Test
    void nonUuidNonNumericOwner_doesNotRecordNoSubscriptionRowFallbackMetric() {
        when(configService.getBoundedLong("video.quota.athlete.storageBytes", 0L, Long.MAX_VALUE))
            .thenReturn(2_147_483_648L);

        quotaConfigService.getStorageQuotaBytes("player-123");

        verify(videoMetrics, never()).recordQuotaTierFallback("no_subscription_row");
    }

    @Test
    void storageQuota_semiProPlayer_readsSemiProKey() {
        PaymentPlayerSubscription subscription = new PaymentPlayerSubscription();
        subscription.setPlayerId(7L);
        subscription.setTier("SEMI_PRO");
        when(paymentPlayerSubscriptionRepository.findByPlayerId(7L)).thenReturn(Optional.of(subscription));
        when(configService.getBoundedLong("video.quota.semiPro.storageBytes", 0L, Long.MAX_VALUE))
            .thenReturn(4L * 1024 * 1024 * 1024);

        assertThat(quotaConfigService.getStorageQuotaBytes("7")).isEqualTo(4L * 1024 * 1024 * 1024);
        verify(configService).getBoundedLong("video.quota.semiPro.storageBytes", 0L, Long.MAX_VALUE);
    }

    @Test
    void bandwidthQuota_proPlayer_readsProKey() {
        PaymentPlayerSubscription subscription = new PaymentPlayerSubscription();
        subscription.setPlayerId(9L);
        subscription.setTier("PRO");
        when(paymentPlayerSubscriptionRepository.findByPlayerId(9L)).thenReturn(Optional.of(subscription));
        when(configService.getBoundedLong("video.quota.pro.bandwidthBytesMonthly", 0L, Long.MAX_VALUE))
            .thenReturn(30L * 1024 * 1024 * 1024);

        assertThat(quotaConfigService.getBandwidthQuotaBytesMonthly("9"))
            .isEqualTo(30L * 1024 * 1024 * 1024);
    }

    // Mutation: remove playerTierToQuotaSegment's SEMI_PRO/PRO branches (fall through to "athlete"
    // for every tier) → this test fails because "video.quota.athlete.storageBytes" is stubbed to a
    // different value than "video.quota.semiPro.storageBytes" and the verify() above never matches.
    @Test
    void storageQuota_athletePlayer_readsAthleteKey() {
        PaymentPlayerSubscription subscription = new PaymentPlayerSubscription();
        subscription.setPlayerId(11L);
        subscription.setTier("ATHLETE");
        when(paymentPlayerSubscriptionRepository.findByPlayerId(11L)).thenReturn(Optional.of(subscription));
        when(configService.getBoundedLong("video.quota.athlete.storageBytes", 0L, Long.MAX_VALUE))
            .thenReturn(2_147_483_648L);

        assertThat(quotaConfigService.getStorageQuotaBytes("11")).isEqualTo(2_147_483_648L);
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
