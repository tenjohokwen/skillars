package com.softropic.skillars.platform.security.contract.exception;


import com.softropic.skillars.infrastructure.security.AuthorizationException;

import static com.softropic.skillars.infrastructure.security.SecurityError.TOKEN_THEFT;

public class JWTTheftException extends AuthorizationException {
    public JWTTheftException(final String msg) {
        super(msg,TOKEN_THEFT);
    }
}
