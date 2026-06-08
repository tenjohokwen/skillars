package com.softropic.skillars.platform.notification.contract;

/**
 * Email delivery status enumeration.
 * Represents the status of email delivery attempts.
 */
public enum EmailDeliveryStatus {
    SENT,
    FAILED,
    SENDING,
    DELETE,
    DEADLINE_EXPIRED,
    ATTEMPTS_EXHAUSTED
}
