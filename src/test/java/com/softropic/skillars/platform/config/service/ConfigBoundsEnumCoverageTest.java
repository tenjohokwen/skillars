package com.softropic.skillars.platform.config.service;

import com.softropic.skillars.platform.config.service.ConfigBounds.BoundedKey;
import com.softropic.skillars.platform.marketplace.contract.CoachSubscriptionTier;
import com.softropic.skillars.platform.video.contract.VideoType;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-107 AC3: the per-tier / per-type {@code platform_config} keys are templated over
 * the {@link CoachSubscriptionTier} and {@link VideoType} enums. {@link ConfigBounds} generates them
 * from a hand-listed set of segments (kept out of the {@code config} module's dependency graph on
 * purpose); this test fails if a new enum constant is added without a matching bound, so the two
 * cannot silently drift.
 */
class ConfigBoundsEnumCoverageTest {

    private Set<String> boundedKeys() {
        return ConfigBounds.ALL.stream().map(BoundedKey::key).collect(Collectors.toSet());
    }

    @Test
    void everyCoachSubscriptionTierHasStorageAndBandwidthBounds() {
        Set<String> keys = boundedKeys();
        for (CoachSubscriptionTier tier : CoachSubscriptionTier.values()) {
            String seg = tier.name().toLowerCase(Locale.ROOT);
            assertThat(keys)
                .as("storage bound for tier %s", tier)
                .contains("video.quota." + seg + ".storageBytes");
            assertThat(keys)
                .as("bandwidth bound for tier %s", tier)
                .contains("video.quota." + seg + ".bandwidthBytesMonthly");
        }
        // The player fallback tier (QuotaConfigService.resolveTierKey → "athlete") is not an enum
        // constant but must be covered too.
        assertThat(keys).contains(
            "video.quota.athlete.storageBytes", "video.quota.athlete.bandwidthBytesMonthly");
    }

    @Test
    void everyVideoTypeHasSizeAndDurationBounds() {
        Set<String> keys = boundedKeys();
        // VideoTypeConstraints.configKey maps the enum to camelCase segments.
        for (VideoType type : VideoType.values()) {
            String seg = switch (type) {
                case HOMEWORK -> "homework";
                case DRILL_DEMO -> "drillDemo";
                case COACH_REVIEW -> "coachReview";
            };
            assertThat(keys)
                .as("maxSizeBytes bound for %s", type)
                .contains("video." + seg + ".maxSizeBytes");
            assertThat(keys)
                .as("maxDurationSeconds bound for %s", type)
                .contains("video." + seg + ".maxDurationSeconds");
        }
    }

    @Test
    void boundsAreInternallyConsistent() {
        for (BoundedKey k : ConfigBounds.ALL) {
            assertThat(k.min()).as("min <= max for %s", k.key()).isLessThanOrEqualTo(k.max());
            assertThat(k.key()).as("key is non-blank").isNotBlank();
            assertThat(k.note()).as("note is non-blank for %s", k.key()).isNotBlank();
        }
    }

    @Test
    void noDuplicateKeys() {
        Set<String> seen = boundedKeys();
        assertThat(seen).hasSize(ConfigBounds.ALL.size());
    }

    @Test
    void hasCodeDefaultKeysAreAllRealBoundedKeys() {
        // skillars-deferred-107 code review: a typo in HAS_CODE_DEFAULT would silently make a 1-arg
        // key look like it has a fallback (or vice versa), skewing ConfigStartupAssertion.
        assertThat(boundedKeys()).containsAll(ConfigBounds.HAS_CODE_DEFAULT);
    }
}
