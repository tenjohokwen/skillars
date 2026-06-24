package com.softropic.skillars.platform.payment.contract.exception;

public class PaymentGatewayException extends RuntimeException {

    private final String errorCode;

    public PaymentGatewayException(String errorCode) {
        super(errorCode);
        this.errorCode = errorCode;
    }

    public PaymentGatewayException(String errorCode, Throwable cause) {
        super(errorCode, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
