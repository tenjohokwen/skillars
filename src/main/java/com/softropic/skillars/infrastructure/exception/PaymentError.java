package com.softropic.skillars.infrastructure.exception;

public enum PaymentError implements ErrorCode {
    MOBILE_PAY_DATA_NOT_FOUND,
    MATCHING_PAYMENT_NOT_FOUND,
    PAY_CART_TOTAL_UPDATE_EVENT_HANDLER_FAILED,
    PAY_DELIVERY_UPDATE_EVENT_HANDLER_FAILED,
    PAY_PAYMENT_PROCESSING_EVENT_HANDLER_FAILED,
    PAY_PAYMENT_PROCESSED_EVENT_HANDLER_FAILED;

    @Override
    public String getErrorCode() {
        return this.name();
    }
}
