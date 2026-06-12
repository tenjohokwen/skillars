package com.softropic.skillars.platform.security.contract;

public enum PlayerPosition {
    GOALKEEPER,
    DEFENDER,
    MIDFIELDER,
    FORWARD;

    public String displayLabel() {
        return switch (this) {
            case GOALKEEPER -> "Goalkeeper";
            case DEFENDER -> "Defender";
            case MIDFIELDER -> "Midfielder";
            case FORWARD -> "Forward";
        };
    }
}
