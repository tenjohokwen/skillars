package com.softropic.skillars.platform.payment.contract.exception;

public class PaymentGatewayException extends RuntimeException {

    /**
     * The payer's card was refused, or needs authentication an off-session charge cannot do. Separate from
     * payment.lifecycleFailure so the UI can say "update your card" rather than "something went wrong".
     */
    public static final String CARD_DECLINED = "payment.cardDeclined";

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
