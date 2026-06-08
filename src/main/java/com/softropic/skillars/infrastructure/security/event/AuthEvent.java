package com.softropic.skillars.infrastructure.security.event;

import org.springframework.security.core.Authentication;

import java.util.UUID;

public class AuthEvent {
    private final Authentication authentication;
    private final String         EVENT_ID = UUID.randomUUID().toString();
    private final AuthenticationAction action;

    public AuthEvent(Authentication authentication, AuthenticationAction action){
        this.authentication = authentication;
        this.action = action;
    }

    public Authentication getAuthentication() {
        return authentication;
    }

    public AuthenticationAction getAction() {
        return action;
    }

    public String getEventId() {
        return EVENT_ID;
    }
}
