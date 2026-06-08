package com.softropic.skillars.platform.security.contract.exception;



import com.softropic.skillars.infrastructure.exception.ErrorCode;
import com.softropic.skillars.infrastructure.security.AuthorizationException;

import java.util.Map;

public class OperationNotAllowedException extends AuthorizationException {
    public OperationNotAllowedException(String message, ErrorCode errorCode) {
        super(message, errorCode);
    }

    public OperationNotAllowedException(String msg, Map<String, Object> logContext, ErrorCode errorCode) {
        super(msg, logContext, errorCode);
    }
}
