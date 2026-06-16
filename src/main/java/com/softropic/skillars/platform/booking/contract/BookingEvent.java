package com.softropic.skillars.platform.booking.contract;

public enum BookingEvent {
    ACCEPT,
    DECLINE,
    SCHEDULE_UPCOMING,
    INITIATE_PAYMENT,
    PAYMENT_CAPTURED,
    PAYMENT_FAILED,
    CANCEL_PARENT,
    CANCEL_COACH,
    START,
    PAUSE,
    RESUME,
    NO_SHOW_PLAYER,
    NO_SHOW_COACH,
    COMPLETE_PENDING,
    COMPLETE,
    QUICK_COMPLETE,
    DISPUTE,
    SETTLE_REFUND,
    SETTLE_COMPLETE,
    REFUND_PROCESSED
}
