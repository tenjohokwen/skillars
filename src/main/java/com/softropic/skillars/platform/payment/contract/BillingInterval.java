package com.softropic.skillars.platform.payment.contract;

// YEARLY (not ANNUAL) — must match VideoSubscriptionLifecycleListener Path A routing
// and PlayerSubscriptionQueryPort.hasActiveYearlySubscription() contract.
public enum BillingInterval {
    MONTHLY, QUARTERLY, YEARLY
}
