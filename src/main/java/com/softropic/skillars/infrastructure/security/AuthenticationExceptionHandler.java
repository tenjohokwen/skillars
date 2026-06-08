package com.softropic.skillars.infrastructure.security;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.servlet.HandlerExceptionResolver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;


/**
 * Actually, JWTAuthenticationFilter (the 'unsuccessfulAuthentication' method) should handle all authentication issues.
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
