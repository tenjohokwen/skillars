package com.softropic.skillars.platform.video.contract.exception;

import com.softropic.skillars.infrastructure.exception.ApplicationException;
import com.softropic.skillars.platform.video.contract.VideoErrorCode;

import java.util.Map;
import java.util.UUID;

public class VideoSessionExpiredException extends ApplicationException {

    public VideoSessionExpiredException(UUID sessionId) {
        super("Upload session has expired",
              Map.of("sessionId", sessionId.toString()),
              VideoErrorCode.SESSION_EXPIRED);
    }
}
