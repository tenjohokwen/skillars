package com.softropic.skillars.infrastructure.security.event;

import org.springframework.security.core.Authentication;

/**
 * Used to expose the Authentication object that will be used to authenticate.
 * This exposure is of course prior to authentication.
 */
public class PreAuthEvent extends AuthEvent {


    /**
     *
     */
    public PreAuthEvent(final Authentication source, AuthenticationAction action) {
        super(source, action);
    }

}
