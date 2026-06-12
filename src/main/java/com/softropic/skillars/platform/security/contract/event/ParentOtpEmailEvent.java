package com.softropic.skillars.platform.security.contract.event;

public record ParentOtpEmailEvent(String toAddress, String otp, String langKey, String firstName) {

    @Override
    public String toString() {
        return "ParentOtpEmailEvent[toAddress=" + toAddress + ", otp=[REDACTED], langKey=" + langKey + ", firstName=" + firstName + "]";
    }
}
