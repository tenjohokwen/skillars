package com.softropic.skillars.platform.video.contract.exception;

import com.softropic.skillars.infrastructure.exception.ApplicationException;
import com.softropic.skillars.platform.video.contract.VideoErrorCode;

import java.util.Map;

public class QuotaExceededException extends ApplicationException {

    public QuotaExceededException(String ownerId, long currentBytes, long requestedBytes) {
        super("Video upload would exceed storage quota",
              Map.of("ownerId", ownerId, "currentBytes", currentBytes, "requestedBytes", requestedBytes),
              VideoErrorCode.QUOTA_EXCEEDED);
    }

    public QuotaExceededException(String ownerId, String reason) {
        super("Upload quota or rate limit exceeded",
              Map.of("ownerId", ownerId, "reason", reason),
              VideoErrorCode.QUOTA_EXCEEDED);
    }
}
