package com.softropic.skillars.platform.security.contract.exception;


import com.softropic.skillars.infrastructure.exception.ErrorCode;

public enum SecError implements ErrorCode {
    BLANK_KEY,
    BLANK_SEQUENCE,
    ILLEGAL_KEY_LENGTH,
    ILLEGAL_SEQUENCE_LENGTH,
    BLANK_BUS_ID,
    BLANK_VERSION,
    BLANK_BUS_ID_OR_VERSION,
    BLANK_SECRET,
    KEY_NOT_FOUND,
    KEY_NOT_UNPERMUTABLE,
    ENCR_ERROR,
    DECR_ERROR;

    @Override
    public String getErrorCode() {
        return this.name();
    }
}
