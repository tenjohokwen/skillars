package com.softropic.skillars.infrastructure.security;

import static com.softropic.skillars.infrastructure.security.SecurityError.TOO_MANY_REQUESTS;

/**
 * skillars-deferred-180 (review round 2): thrown by {@link RateLimitingAspect} when a {@link RateLimited} bucket is empty.
 *
 * <p>A subclass of {@link AuthorizationException} so every existing catch and assertion on that type still holds, but with
 * its own {@code ApiAdvice} handler answering {@code 429}. It used to fall into the generic {@code AuthorizationException}
 * handler and answer {@code 401 security.unauthorized}: on a logged-in page the SPA's interceptor read a throttle as an
 * ended session, a page's own 429 handling (the email-change dialog's "too many attempts") never fired, and on
 * {@code POST /api/auth/refresh} an API client would sign its user out instead of backing off.
 */
public class RequestRateLimitedException extends AuthorizationException {

    public RequestRateLimitedException(String msg) {
        super(msg, TOO_MANY_REQUESTS);
    }
}
