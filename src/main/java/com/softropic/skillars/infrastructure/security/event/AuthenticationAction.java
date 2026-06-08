package com.softropic.skillars.infrastructure.security.event;

/**
 * Enum representing different authentication and authorization actions in the security system.
 * Used to track and categorize security-related events.
 */
public enum AuthenticationAction {
    SUCCESSFUL_AUTHENTICATION,
    SUCCESSFUL_PRE_2FA,
    SUCCESSFUL_2FA,
    PRE_AUTHENTICATION,
    PRE_AUTHORIZATION
}
