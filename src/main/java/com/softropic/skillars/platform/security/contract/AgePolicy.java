package com.softropic.skillars.platform.security.contract;

public record AgePolicy(int u10MaxAge, int youngTeenMaxAge, int teenMaxAge) {

    public static AgePolicy defaults() {
        return new AgePolicy(9, 12, 17);
    }
}
