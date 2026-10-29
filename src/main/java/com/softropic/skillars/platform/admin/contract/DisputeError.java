package com.softropic.skillars.platform.admin.contract;

import com.softropic.skillars.infrastructure.exception.ErrorCode;

public enum DisputeError implements ErrorCode {
    NOT_ELIGIBLE, WINDOW_EXPIRED, INVALID_CREDIT_AMOUNT, NO_REFUND_TO_REVERSE, ALREADY_REFUNDED;

    @Override
    public String getErrorCode() {
        return switch (this) {
            case NOT_ELIGIBLE          -> "disputes.notEligible";
            case WINDOW_EXPIRED        -> "disputes.windowExpired";
            case INVALID_CREDIT_AMOUNT -> "disputes.invalidCreditAmount";
            // feat/payment-forfeit-payouts-and-clawback: UPHOLD_COACH on a booking the payer was never refunded for
            case NO_REFUND_TO_REVERSE  -> "disputes.noRefundToReverse";
            // fix/payment-wallet-dispute-integrity: FULL_CREDIT on a booking already refunded in full
            case ALREADY_REFUNDED      -> "disputes.alreadyRefunded";
        };
    }
}
