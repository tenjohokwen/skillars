package com.softropic.skillars.platform.admin.contract;

import com.softropic.skillars.infrastructure.exception.ErrorCode;

public enum DisputeError implements ErrorCode {
    NOT_ELIGIBLE, WINDOW_EXPIRED, INVALID_CREDIT_AMOUNT, NO_REFUND_TO_REVERSE, ALREADY_REFUNDED, BOOKING_NOT_SETTLED;

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
            // fix/booking-completion-integrity: a dispute raised on a live session is resolved only once
            // the session has completed (or been cancelled), so the coach payout it reconciles exists.
            case BOOKING_NOT_SETTLED   -> "disputes.bookingNotSettled";
        };
    }
}
