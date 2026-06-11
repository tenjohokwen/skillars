package com.softropic.skillars.platform.security.contract.event;

public record CoachOtpEmailEvent(String toAddress, String otp, String langKey, String firstName) {

    @Override
    public String toString() {
        return "CoachOtpEmailEvent[toAddress=" + toAddress + ", otp=[REDACTED], langKey=" + langKey + ", firstName=" + firstName + "]";
    }
}
