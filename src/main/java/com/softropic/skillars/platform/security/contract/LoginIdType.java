package com.softropic.skillars.platform.security.contract;

import java.util.Arrays;
import java.util.Optional;

public enum LoginIdType {
    EMAIL(1),
    PHONE(2),
    USERNAME(3);

    private final int code;

    LoginIdType(Integer code) {this.code = code;}

    public static Optional<LoginIdType>  fromCode(Integer code) {
        return Arrays.stream(LoginIdType.values()).filter(type -> type.code == code).findFirst();
    }

    public Integer getCode() {
        return EMAIL.code;
    }
}
