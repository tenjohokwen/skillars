package com.softropic.skillars.infrastructure.blobstore.contract;

import com.softropic.skillars.infrastructure.exception.ErrorCode;

public enum BlobstoreErrorCode implements ErrorCode {
    STORAGE_OBJECT_NOT_FOUND,
    PROVIDER_ERROR;

    @Override
    public String getErrorCode() {
        return this.name();
    }
}
