package com.softropic.skillars.platform.video.contract;

import com.softropic.skillars.infrastructure.exception.ErrorCode;

public enum VideoErrorCode implements ErrorCode {
    VIDEO_NOT_FOUND,
    VALIDATION_FAILED,
    QUOTA_EXCEEDED,
    UPLOAD_RATE_LIMITED,
    PLAYBACK_DENIED,
    PROVIDER_ERROR,
    SESSION_EXPIRED,
    TERMINAL_STATE_VIOLATION,
    DELETION_NOT_AUTHORISED,
    VIDEO_APPROVAL_NOT_FOUND,
    VIDEO_APPROVAL_ALREADY_RESOLVED,
    /** The video is not in the operational state this transition requires (client-side race). */
    VIDEO_STATE_CONFLICT;

    @Override
    public String getErrorCode() {
        return this.name();
    }
}
