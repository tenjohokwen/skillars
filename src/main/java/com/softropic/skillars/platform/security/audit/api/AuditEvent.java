package com.softropic.skillars.platform.security.audit.api;

public enum AuditEvent {
    BAD_CRED,
    CRED_EXPIRED,
    ACCOUNT_EXPIRED,
    ACCOUNT_DISABLED,
    ACCOUNT_LOCKED,
    SIGN_IN,
    SIGN_OUT,
    MISSING_PROVIDER

}
