package com.softropic.skillars.platform.security.contract.event;

public record PlayerOtpEmailEvent(String toAddress, String otp, String langKey, String firstName) {

    @Override
    public String toString() {
        return "PlayerOtpEmailEvent[toAddress=" + toAddress + ", otp=[REDACTED], langKey=" + langKey + ", firstName=" + firstName + "]";
    }
}
