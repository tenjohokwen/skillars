package com.softropic.skillars.platform.security.infrastructure.listener;



import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.security.authorization.event.AuthorizationDeniedEvent;
import org.springframework.stereotype.Component;

import static net.logstash.logback.argument.StructuredArguments.kv;


/**
 * Meant to catch cases where an already/not authenticated user attempts to access resources for which he does not have rights.
 * JWTAuthorizationFilter and FilterSecurityInterceptor (to be precise its parent class AbstractSecurityInterceptor) will actually publish this event
 */
@Slf4j
@Component
public class AuthorizationFailureListener {

    @EventListener
    @SuppressWarnings("PMD")
    public void recordFailure(final AuthorizationDeniedEvent event) {
        //TODO test this
        log.warn("Authorization failure",
            kv("operation", "authorization"),
            kv("status", "FORBIDDEN"));
    }

}
