package com.softropic.skillars.platform.payment.contract;

/** skillars-deferred-174 AC5: the status a pack purchase attempt was closed to by the admin close action. */
public record PackAttemptCloseResponse(Long attemptId, String status) {
}
