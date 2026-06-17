package com.softropic.skillars.platform.session.contract;

import com.softropic.skillars.infrastructure.exception.ErrorCode;

public enum SessionErrorCode implements ErrorCode {

    CLONE_NOT_ALLOWED,
    SESSION_CANNOT_TAG_UNAUTHORIZED;

    @Override
    public String getErrorCode() {
        return this.name();
    }
}
