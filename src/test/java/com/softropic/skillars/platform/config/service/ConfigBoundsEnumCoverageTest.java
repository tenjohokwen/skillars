package com.softropic.skillars.platform.config.service;

import com.softropic.skillars.platform.config.service.ConfigBounds.BoundedKey;
import com.softropic.skillars.platform.marketplace.contract.CoachSubscriptionTier;
import com.softropic.skillars.platform.video.contract.VideoType;

import org.junit.jupiter.api.Test;

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

    /**
     * skillars-deferred-109 code review (owner decision, option b): mirror the runtime derivation
     * EXACTLY rather than deriving the segment mechanically.
     *
     * <p>AC10.2 replaced {@code toLowerCase()} with a SCREAMING_SNAKE → camelCase helper, on the
     * premise that the seeded key for a future {@code PRO_ACADEMY} would be
     * {@code video.quota.proAcademy.*}. That premise is not enforced by anything: the runtime
     * segment comes from a hand-written exhaustive {@code switch} with string literals in
     * {@code QuotaConfigService.resolveTierKey}, whose only visible convention is plain lower-case
     * ({@code "scout"}, {@code "instructor"}, {@code "academy"}). A mechanical helper therefore just
     * relocated the drift it claimed to close — an author adding {@code case PRO_ACADEMY ->
     * "pro_academy"} would satisfy the switch, add {@code proAcademy} here to satisfy the build, and
     * ship a bound nothing reads next to a key nothing bounds.
     *
     * <p>An exhaustive switch over the enum is the stronger guard: adding a constant breaks
     * COMPILATION here (no {@code default} arm), which forces whoever adds it to open
     * {@code resolveTierKey} and copy the literal it actually uses. {@code VideoType} below has
     * always worked this way, and {@code VideoTypeConstraints.configKey} is likewise a hand-written
     * switch — neither enum has a mechanical derivation to mirror.
     */
    private static String tierSegment(CoachSubscriptionTier tier) {
        // Must match QuotaConfigService.resolveTierKey literal-for-literal.
        return switch (tier) {
            case SCOUT      -> "scout";
            case INSTRUCTOR -> "instructor";
            case ACADEMY    -> "academy";
        };
    }

    @Test
    void everyCoachSubscriptionTierHasStorageAndBandwidthBounds() {
        Set<String> keys = boundedKeys();
        for (CoachSubscriptionTier tier : CoachSubscriptionTier.values()) {
            String seg = tierSegment(tier);
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
        // skillars-deferred-109 AC10.3: video.quota.semiPro.* / .pro.* are seeded live by V53 and
        // correspond to PlayerSubscriptionTierBilling.SEMI_PRO / PRO. They are asserted here by hand
        // (NOT via PlayerSubscriptionTierBilling.values() — that enum is otherwise unused, and making
        // this its only reader would just move the drift risk). Note: QuotaConfigService.resolveTierKey
        // does NOT map any player to these segments yet — it falls through to "athlete" — so these
        // bounds are deliberately one release ahead of the runtime (skillars-deferred-109 AC10.6).
        assertThat(keys).contains(
            "video.quota.semiPro.storageBytes", "video.quota.semiPro.bandwidthBytesMonthly",
            "video.quota.pro.storageBytes", "video.quota.pro.bandwidthBytesMonthly");
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
