package com.softropic.skillars.infrastructure.blobstore.contract.exception;

import com.softropic.skillars.infrastructure.exception.ApplicationException;
import com.softropic.skillars.infrastructure.blobstore.contract.BlobstoreErrorCode;

import java.util.Map;

public class StorageProviderException extends ApplicationException {

    public StorageProviderException(String operation, Throwable cause, String context) {
        super("Storage provider error during: " + operation + " for: " + context,
              cause,
              Map.of("operation", operation, "context", context),
              BlobstoreErrorCode.PROVIDER_ERROR);
    }
}
