package com.softropic.skillars.platform.payment.service;

/**
 * Shared config keys and defaults for coach reliability strike thresholds, read by both
 * {@link ReliabilityStrikeService} (issuing a strike) and
 * {@code AdminCoachEnforcementService} (deleting a strike) so the two call sites cannot
 * silently drift apart.
 */
public final class ReliabilityStrikeConfig {

    public static final String SUSPENSION_THRESHOLD_KEY = "reliability.strike.suspensionThreshold";
    public static final String VISIBILITY_THRESHOLD_KEY = "reliability.strike.visibilityThreshold";

    public static final long DEFAULT_SUSPENSION_THRESHOLD = 5L;
    public static final long DEFAULT_VISIBILITY_THRESHOLD = 3L;

    private ReliabilityStrikeConfig() {
    }
}
