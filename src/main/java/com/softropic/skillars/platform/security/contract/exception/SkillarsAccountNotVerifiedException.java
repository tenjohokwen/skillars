package com.softropic.skillars.platform.security.contract.exception;

import com.softropic.skillars.infrastructure.security.SecurityError;

public class SkillarsAccountNotVerifiedException extends SecException {
    public SkillarsAccountNotVerifiedException() {
        super("Account is not verified for Skillars platform access", SecurityError.ACCOUNT_NOT_LOGIN_ABLE);
    }
}
