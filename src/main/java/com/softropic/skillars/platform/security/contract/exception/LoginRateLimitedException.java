package com.softropic.skillars.platform.security.contract.exception;

import com.softropic.skillars.infrastructure.security.SecurityError;

import java.util.Map;

public class LoginRateLimitedException extends SecException {

    private final long retryAfterSeconds;

    public LoginRateLimitedException(String msg, Map<String, Object> ctx, long retryAfterSeconds) {
        super(msg, ctx, SecurityError.TOO_MANY_REQUESTS);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
