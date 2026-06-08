package com.softropic.skillars.platform.filestorage.contract;

import com.softropic.skillars.infrastructure.exception.ErrorCode;

public enum FileStorageErrorCode implements ErrorCode {
    QUOTA_EXCEEDED,
    VALIDATION_FAILED;

    @Override
    public String getErrorCode() {
        return this.name();
    }
}
