package com.softropic.skillars.platform.security.contract.exception;

import com.softropic.skillars.infrastructure.security.AuthorizationException;
import com.softropic.skillars.infrastructure.security.SecurityError;

public class MissingClientIdException extends AuthorizationException {

    public MissingClientIdException(final String msg) {
        super(msg, SecurityError.MISSING_CLIENT_ID);
    }
}
