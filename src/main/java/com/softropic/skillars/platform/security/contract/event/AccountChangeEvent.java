package com.softropic.skillars.platform.security.contract.event;

import lombok.Getter;

@Getter
public class AccountChangeEvent {
    private final Action                action;
    private final String                oldValue;
    private final String                newValue;
    private final AccountChangeUserInfo userInfo;

    public AccountChangeEvent(Action action, String oldValue, String newValue, AccountChangeUserInfo userInfo) {
        this.action = action;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.userInfo = userInfo;
    }

    public enum Action {
        PASSWORD_CHANGED,
        EMAIL_CHANGED,
        // skillars-deferred-172: a confirmed email change reverted from the undo link mailed to the old address.
        EMAIL_CHANGE_UNDONE,
        ADDRESS_CHANGED,
        PHONE_CHANGED,
        PAYOUT_ACCOUNT_CHANGED,
        TWO_FACTOR_AUTH_ENABLED,
        TWO_FACTOR_AUTH_DISABLED,
        OTHERS;
    }
}
