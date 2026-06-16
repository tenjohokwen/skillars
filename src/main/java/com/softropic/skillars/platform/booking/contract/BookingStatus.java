package com.softropic.skillars.platform.booking.contract;

public enum BookingStatus {
    REQUESTED,
    ACCEPTED,
    PAYMENT_PENDING,
    CONFIRMED,
    UPCOMING,
    IN_PROGRESS,
    PAUSED,
    COMPLETED_PENDING_CONFIRMATION,
    COMPLETED,
    DECLINED,
    CANCELLED_PARENT,
    CANCELLED_COACH,
    NO_SHOW_PLAYER,
    NO_SHOW_COACH,
    DISPUTED,
    REFUND_PENDING,
    REFUNDED
}
