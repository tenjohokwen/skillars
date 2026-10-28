package com.softropic.skillars.platform.video.contract;

public final class LifecycleTrigger {

    public static final String SYSTEM = "SYSTEM";
    public static final String SUBSCRIPTION_EXPIRY = "SUBSCRIPTION_EXPIRY";
    public static final String YEARLY_EXPIRY_CLOCK_RESET = "YEARLY_EXPIRY_CLOCK_RESET";
    public static final String ACCOUNT_DELETION = "ACCOUNT_DELETION";
    public static final String USER_DELETION = "USER_DELETION";
    /** AdminVideoService.deleteVideo — the admin console and session's drill-video replacement (VideoPhysicalDeletionListener) — V182 admits it in both triggered_by CHECKs. */
    public static final String ADMIN_DELETION = "ADMIN_DELETION";

    private LifecycleTrigger() {}
}
