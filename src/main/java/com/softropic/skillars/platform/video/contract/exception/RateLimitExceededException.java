package com.softropic.skillars.platform.video.contract.exception;

import com.softropic.skillars.infrastructure.exception.ApplicationException;
import com.softropic.skillars.platform.video.contract.VideoErrorCode;

import java.util.Map;

public class RateLimitExceededException extends ApplicationException {

    public RateLimitExceededException(String ownerId, String reason) {
        super("Upload rate limit exceeded",
              Map.of("ownerId", ownerId, "reason", reason),
              VideoErrorCode.UPLOAD_RATE_LIMITED);
    }
}
