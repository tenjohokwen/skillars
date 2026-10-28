package com.softropic.skillars.platform.session.contract;

import com.softropic.skillars.infrastructure.exception.ErrorCode;

public enum SessionErrorCode implements ErrorCode {

    CLONE_NOT_ALLOWED,
    SESSION_CANNOT_TAG_UNAUTHORIZED,
    DRILL_NOT_OWNED,
    DRILL_VIDEO_ALREADY_LINKED,
    // feat/coach-workflow-gaps D1: delete refused while a demo video is still linked
    DRILL_HAS_VIDEO,
    SESSION_ALREADY_EXISTS,
    SESSION_BOOKING_NOT_OWNED,
    SESSION_PLAN_LOCKED,
    TEMPLATE_NOT_OWNED;

    @Override
    public String getErrorCode() {
        return this.name();
    }
}
