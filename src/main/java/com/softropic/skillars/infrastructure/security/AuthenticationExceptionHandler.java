package com.softropic.skillars.infrastructure.security;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.servlet.HandlerExceptionResolver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;


/**
 * Entry point for an AuthenticationException that reaches the filter chain's exception translation
 * (e.g. a secured endpoint hit without a valid JWT); delegates to the MVC exception resolver so the
 * response is the usual ErrorDto. Password login errors come from POST /api/auth/login itself.
 */
public class AuthenticationExceptionHandler implements AuthenticationEntryPoint {

    private final HandlerExceptionResolver handlerExceptionResolver;

    public AuthenticationExceptionHandler(final HandlerExceptionResolver handlerExceptionResolver) {
        this.handlerExceptionResolver = handlerExceptionResolver;
    }


    @Override
    public void commence(final HttpServletRequest request,
                         final HttpServletResponse response,
                         final AuthenticationException authEx) {
        handlerExceptionResolver.resolveException(request, response, null, authEx);
    }
}
