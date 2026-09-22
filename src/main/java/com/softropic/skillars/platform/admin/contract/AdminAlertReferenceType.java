package com.softropic.skillars.platform.admin.contract;

public enum AdminAlertReferenceType {
    MESSAGE, CONVERSATION, REVIEW, COACH, BOOKING,
    // skillars-deferred-128 AC2: referenceId is the GdprRequest's own requestId — see
    // V152__admin_alerts_gdpr_erasure_deadline_type.sql.
    GDPR_REQUEST
}
