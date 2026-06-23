package com.softropic.skillars.platform.video.contract.exception;

import com.softropic.skillars.infrastructure.exception.ApplicationException;
import com.softropic.skillars.platform.video.contract.VideoErrorCode;

import java.util.Map;
import java.util.UUID;

public class VideoDeletionNotAuthorisedException extends ApplicationException {

    public VideoDeletionNotAuthorisedException(UUID videoId, String requesterId) {
        super("Video deletion not authorised",
              Map.of("videoId", videoId.toString(), "requesterId", requesterId),
              VideoErrorCode.DELETION_NOT_AUTHORISED);
    }
}
