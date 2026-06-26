package com.softropic.skillars.platform.messaging.contract;

import com.softropic.skillars.infrastructure.exception.ErrorCode;

public enum MessagingErrorCode implements ErrorCode {
    NO_BOOKING_RELATIONSHIP("messaging.noBookingRelationship"),
    INVALID_CONTENT("messaging.invalidContent"),
    NOT_A_PARTY("messaging.notAParty"),
    CONVERSATION_NOT_FOUND("messaging.conversationNotFound");

    private final String code;

    MessagingErrorCode(String code) { this.code = code; }

    @Override
    public String getErrorCode() { return code; }
}
