package com.softropic.skillars.platform.filestorage.contract.exception;

import com.softropic.skillars.infrastructure.exception.ApplicationException;
import com.softropic.skillars.platform.filestorage.contract.FileStorageErrorCode;

import java.util.Map;

public class QuotaExceededException extends ApplicationException {

    public QuotaExceededException(String tenantId, long currentBytes, long requestedBytes) {
        super("Upload would exceed storage quota",
              Map.of("tenantId", tenantId, "currentBytes", currentBytes, "requestedBytes", requestedBytes),
              FileStorageErrorCode.QUOTA_EXCEEDED);
    }
}
