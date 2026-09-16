package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.contract.CoachSubscriptionTier;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.payment.contract.PlayerSubscriptionTierBilling;
import com.softropic.skillars.platform.payment.repo.PaymentPlayerSubscription;
import com.softropic.skillars.platform.payment.repo.PaymentPlayerSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class QuotaConfigService {

    private final ConfigService configService;
    private final CoachProfileService coachProfileService;
    // skillars-deferred-113 AC3: read-only lookup only — NOT SubscriptionService.getPlayerSubscription
    // (requires a parentUserId this method's only input, ownerId, doesn't carry) and NOT its private
    // findOrCreatePlayerSubscription (writes a new subscription row on a miss, a side effect a quota
    // *check* must never have).
    private final PaymentPlayerSubscriptionRepository paymentPlayerSubscriptionRepository;
    // skillars-deferred-114 AC3: distinguishes the no-subscription-row fallback from the other two
    // fallback shapes below (each already has its own log statement) in both logs and a metric.
    private final VideoMetrics videoMetrics;

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
            return resolvePlayerTierKey(ownerId);
        }
    }

    /**
     * skillars-deferred-113 AC3: before this fix every non-UUID {@code ownerId} fell straight
     * through to a bare {@code "athlete"}, so {@code video.quota.semiPro.*}/{@code .pro.*} — seeded
     * live by {@code V139__baseline_seed_data.sql} since {@code skillars-deferred-109} AC10 —
     * were unreachable for every player regardless of their actual subscription tier.
     *
     * <p>Fails open to {@code "athlete"} for every unrecognised shape, matching this class's
     * existing posture for every other unmapped case: an {@code ownerId} that isn't a {@code Long}
     * either, no subscription row for that player, or a stored {@code tier} string that isn't a
     * known {@link PlayerSubscriptionTierBilling} constant.
     */
    private String resolvePlayerTierKey(String ownerId) {
        long playerId;
        try {
            playerId = Long.parseLong(ownerId);
        } catch (NumberFormatException e) {
            log.debug("Non-UUID, non-Long ownerId '{}' — defaulting to athlete tier for quota lookup", ownerId);
            return "athlete";
        }
        // skillars-deferred-114 AC4: tier lookup and the caller's actual quota enforcement are two
        // separate, non-transactional reads — a player's subscription tier can change in the window
        // between them. Accepted, low-impact race (a brief quota-mismatch window, not a monetary-
        // consistency or security concern), re-confirmed this story; do not add a lock/re-check here
        // without revisiting that decision. See deferred-work.md's matching [DECIDED] annotation.
        Optional<PaymentPlayerSubscription> subscription = paymentPlayerSubscriptionRepository.findByPlayerId(playerId);
        if (subscription.isEmpty()) {
            // skillars-deferred-114 AC3: distinct from the two other fallback shapes above/below (a
            // non-Long ownerId; an unrecognised stored tier string) — this one means no subscription
            // row exists at all for a syntactically-valid player id, a genuine data-integrity signal
            // (every player should have one, seeded or created at registration).
            //
            // skillars-deferred-114 code review (MEDIUM): confirmed caller inventory (grepped every
            // caller of getStorageQuotaBytes/getBandwidthQuotaBytesMonthly — the only two callers of
            // this method — as of this story) so this fallback's fan-out is a documented fact, not an
            // estimate:
            //   - QuotaService.check(ownerId, requestedBytes)   -> getStorageQuotaBytes only  (1 fire)
            //   - QuotaService.reserve(ownerId, bytes[, type])  -> getStorageQuotaBytes only  (1 fire)
            //   - VideoResource.getMyQuota() [GET /quotas/me]   -> BOTH quota methods         (2 fires)
            // Neither QuotaService method calls getBandwidthQuotaBytesMonthly. A single upload
            // (check then reserve) therefore fires this metric twice, not once — the raw counter value
            // is not "distinct players" or "distinct requests." Re-grep both call sites before relying
            // on this breakdown if either method's callers change.
            log.warn("Player id '{}' has no subscription row at all — defaulting to athlete tier for "
                    + "quota lookup. This is a data-integrity signal, not an expected shape.", playerId);
            videoMetrics.recordQuotaTierFallback("no_subscription_row");
            return "athlete";
        }
        return subscription
            .map(PaymentPlayerSubscription::getTier)
            .map(this::playerTierToQuotaSegment)
            .orElse("athlete");
    }

    private String playerTierToQuotaSegment(String storedTier) {
        try {
            return switch (PlayerSubscriptionTierBilling.valueOf(storedTier)) {
                case SEMI_PRO -> "semiPro";
                case PRO      -> "pro";
                case ATHLETE  -> "athlete";
            };
        } catch (IllegalArgumentException e) {
            log.warn("Unrecognised player subscription tier '{}' — defaulting to athlete tier for quota lookup",
                storedTier);
            return "athlete";
        }
    }
}
