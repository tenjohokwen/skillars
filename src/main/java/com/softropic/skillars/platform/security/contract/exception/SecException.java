package com.softropic.skillars.platform.security.contract.exception;



import com.softropic.skillars.infrastructure.exception.ApplicationException;
import com.softropic.skillars.infrastructure.exception.ErrorCode;

import java.util.Map;

public class SecException extends ApplicationException {
    public SecException(String msg) {
        super(msg);
    }

    public SecException(String msg, Map<String, Object> logContext) {
        super(msg, logContext);
    }

    public SecException(String msg, Map<String, Object> logContext, ErrorCode errorCode) {
        super(msg, logContext, errorCode);
    }

    public SecException(String msg, ErrorCode errorCode) {
        super(msg, errorCode);
    }

    public SecException(String msg, Throwable cause, Map<String, Object> logContext, ErrorCode errorCode) {
        super(msg, cause, logContext, errorCode);
    }
}
