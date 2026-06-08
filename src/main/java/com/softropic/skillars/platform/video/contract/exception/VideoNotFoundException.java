package com.softropic.skillars.platform.video.contract.exception;

import com.softropic.skillars.infrastructure.exception.ApplicationException;
import com.softropic.skillars.platform.video.contract.VideoErrorCode;

import java.util.Map;
import java.util.UUID;

public class VideoNotFoundException extends ApplicationException {

    public VideoNotFoundException(UUID videoId) {
        super("Video not found",
              Map.of("videoId", videoId.toString()),
              VideoErrorCode.VIDEO_NOT_FOUND);
    }
}
