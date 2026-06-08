package com.softropic.skillars.platform.security.repo;

public enum UniqueKeyConstraints {
    UK_USER_LOGIN("uk_user_login"),
    UK_USER_EMAIL("uk_user_email"),
    UK_AUTHORITY_NAME("uk_authority_name");

    private final String value;

    UniqueKeyConstraints(final String value) {
        this.value = value;
    }

    public boolean isSameValue(final String otherValue) {
        return this.value.equals(otherValue);
    }

    public String value() {
        return value;
    }
}
