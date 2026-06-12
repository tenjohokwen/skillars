package com.softropic.skillars.platform.marketplace.contract;

public class MarketplaceException extends RuntimeException {

    private final String errorCode;

    public MarketplaceException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
