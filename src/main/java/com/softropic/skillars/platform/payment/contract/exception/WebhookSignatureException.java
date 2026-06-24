package com.softropic.skillars.platform.payment.contract.exception;

public class WebhookSignatureException extends RuntimeException {

    private final String errorCode;

    public WebhookSignatureException(String errorCode) {
        super(errorCode);
        this.errorCode = errorCode;
    }

    public WebhookSignatureException(String errorCode, Throwable cause) {
        super(errorCode, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
