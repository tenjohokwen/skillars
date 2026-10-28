package com.softropic.skillars.platform.video.contract.exception;

import com.softropic.skillars.infrastructure.exception.ApplicationException;
import com.softropic.skillars.platform.video.contract.VideoErrorCode;

import java.util.Map;

/**
 * The booking a coach named as a video's subject is missing, not the coach's own, not a session that
 * took place, or its player's data has been erased. Also thrown when a subject is required
 * (COACH_REVIEW) but absent, or offered where none is allowed (a player HOMEWORK upload).
 */
public class VideoSubjectInvalidException extends ApplicationException {

    public VideoSubjectInvalidException(String reason) {
        super("Video subject booking is not valid for this upload",
              Map.of("reason", reason),
              VideoErrorCode.VIDEO_SUBJECT_INVALID);
    }
}
