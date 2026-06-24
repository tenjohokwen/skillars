package com.softropic.skillars.platform.video.contract.exception;

import com.softropic.skillars.infrastructure.exception.ApplicationException;
import com.softropic.skillars.platform.video.contract.VideoErrorCode;

import java.util.Map;
import java.util.UUID;

public class VideoAlreadyResolvedException extends ApplicationException {

    public VideoAlreadyResolvedException(UUID approvalId, String currentStatus) {
        super("Approval request has already been actioned",
              Map.of("approvalId", approvalId.toString(), "currentStatus", currentStatus),
              VideoErrorCode.VIDEO_APPROVAL_ALREADY_RESOLVED);
    }
}
