package com.softropic.skillars.platform.video.contract;

import com.softropic.skillars.infrastructure.exception.ErrorCode;

public enum VideoErrorCode implements ErrorCode {
    VIDEO_NOT_FOUND,
    VALIDATION_FAILED,
    QUOTA_EXCEEDED,
    PLAYBACK_DENIED,
    PROVIDER_ERROR,
    SESSION_EXPIRED,
    TERMINAL_STATE_VIOLATION,
    DELETION_NOT_AUTHORISED;

    @Override
    public String getErrorCode() {
        return this.name();
    }
}
