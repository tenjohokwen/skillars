package com.softropic.skillars.platform.filestorage.contract.exception;

import com.softropic.skillars.infrastructure.exception.ApplicationException;
import com.softropic.skillars.platform.filestorage.contract.FileStorageErrorCode;

import java.util.Map;

public class StorageValidationException extends ApplicationException {

    public StorageValidationException(String reason) {
        super("Storage validation failed: " + reason,
              Map.of("reason", reason),
              FileStorageErrorCode.VALIDATION_FAILED);
    }
}
