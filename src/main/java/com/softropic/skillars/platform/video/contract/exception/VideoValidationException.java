package com.softropic.skillars.platform.video.contract.exception;

import com.softropic.skillars.infrastructure.exception.ApplicationException;
import com.softropic.skillars.platform.video.contract.VideoErrorCode;

import java.util.Map;

public class VideoValidationException extends ApplicationException {

    public VideoValidationException(String reason) {
        super("Video validation failed",
              Map.of("reason", reason),
              VideoErrorCode.VALIDATION_FAILED);
    }
}
