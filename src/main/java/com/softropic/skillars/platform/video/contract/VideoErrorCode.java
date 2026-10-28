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
    /**
     * A player upload refused at initiation because the owning profile is a minor (or a parent-managed
     * profile, or has no date of birth): minors never upload (ADR "Minors never upload", 2026-10-28).
     */
    VIDEO_MINOR_UPLOAD_REFUSED,
    /** The booking named as a coach video's subject is not this coach's, or not a held session. */
    VIDEO_SUBJECT_INVALID,
    /** The video is not in the operational state this transition requires (client-side race). */
    VIDEO_STATE_CONFLICT;

    @Override
    public String getErrorCode() {
        return this.name();
    }
}
