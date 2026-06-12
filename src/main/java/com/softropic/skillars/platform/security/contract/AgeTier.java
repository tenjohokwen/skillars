package com.softropic.skillars.platform.security.contract;

public enum AgeTier {
    U10,
    AGE_10_12,
    AGE_13_17,
    ADULT;

    public String displayLabel() {
        return switch (this) {
            case U10 -> "U10";
            case AGE_10_12 -> "10–12";
            case AGE_13_17 -> "13–17";
            case ADULT -> "18+";
        };
    }
}
