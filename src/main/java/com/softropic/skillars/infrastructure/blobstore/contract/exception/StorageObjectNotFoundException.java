package com.softropic.skillars.infrastructure.blobstore.contract.exception;

import com.softropic.skillars.infrastructure.exception.ApplicationException;
import com.softropic.skillars.infrastructure.blobstore.contract.BlobstoreErrorCode;

import java.util.Map;

public class StorageObjectNotFoundException extends ApplicationException {

    public StorageObjectNotFoundException(String key) {
        super("Storage object not found: " + key,
              Map.of("storageKey", key),
              BlobstoreErrorCode.STORAGE_OBJECT_NOT_FOUND);
    }

    public StorageObjectNotFoundException(String key, Throwable cause) {
        super("Storage object not found: " + key,
              cause,
              Map.of("storageKey", key),
              BlobstoreErrorCode.STORAGE_OBJECT_NOT_FOUND);
    }
}
