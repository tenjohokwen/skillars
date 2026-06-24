package com.softropic.skillars.platform.video.contract.exception;

import com.softropic.skillars.infrastructure.exception.ApplicationException;
import com.softropic.skillars.platform.video.contract.VideoErrorCode;

import java.util.Map;
import java.util.UUID;

public class VideoApprovalNotFoundException extends ApplicationException {

    public VideoApprovalNotFoundException(UUID approvalId) {
        super("Approval request not found or the video was deleted",
              Map.of("approvalId", approvalId.toString()),
              VideoErrorCode.VIDEO_APPROVAL_NOT_FOUND);
    }
}
