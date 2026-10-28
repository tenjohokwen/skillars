package com.softropic.skillars.platform.admin.contract;

public enum AdminAlertReferenceType {
    MESSAGE, CONVERSATION, REVIEW, COACH, BOOKING,
    // skillars-deferred-128 AC2: referenceId is the GdprRequest's own requestId — see
    // V152__admin_alerts_gdpr_erasure_deadline_type.sql.
    GDPR_REQUEST,
    // feat/coach-payouts-pack-and-credit: referenceId is the cash-out id
    // (payment.cash_out_reconciliations.cash_out_id) — see V186.
    CASH_OUT
}
