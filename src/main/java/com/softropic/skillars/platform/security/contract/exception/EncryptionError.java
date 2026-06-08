package com.softropic.skillars.platform.security.contract.exception;


import com.softropic.skillars.infrastructure.exception.ErrorCode;

public enum EncryptionError implements ErrorCode {
    MISSING_SECRET,
    MISSING_TEXT,
    ENCRYPTION_ERROR,
    DECRYPTION_ERROR;

    @Override
    public String getErrorCode() {
        return this.name();
    }
}
