package com.softropic.skillars.platform.marketplace.contract;

// Declaration order is load-bearing for DrillUploadService.resolveMinUploadTier, which iterates
// values() in ascending-rank order to find the lowest tier with drill-video-upload enabled. Keep
// tiers declared from lowest to highest rank (Story Deferred-75 AC5).
public enum CoachSubscriptionTier {
    SCOUT, INSTRUCTOR, ACADEMY;

    static {
        // Validate enum order at class load time
        if (SCOUT.ordinal() != 0 || INSTRUCTOR.ordinal() != 1 || ACADEMY.ordinal() != 2) {
            throw new ExceptionInInitializerError(
                "CoachSubscriptionTier enum order is incorrect. Expected: SCOUT(0), INSTRUCTOR(1), ACADEMY(2). " +
                "This order is load-bearing for DrillUploadService.resolveMinUploadTier().");
        }
    }
}
