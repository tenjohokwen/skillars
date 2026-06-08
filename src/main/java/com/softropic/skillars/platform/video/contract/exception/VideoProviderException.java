package com.softropic.skillars.platform.video.contract.exception;

import com.softropic.skillars.infrastructure.exception.ApplicationException;
import com.softropic.skillars.platform.video.contract.VideoErrorCode;

import java.util.Map;

public class VideoProviderException extends ApplicationException {

    public VideoProviderException(String operation, Throwable cause) {
        super("Video provider error during: " + operation,
              cause,
              Map.of("operation", operation),
              VideoErrorCode.PROVIDER_ERROR);
    }
}
