package com.softropic.skillars.platform.security.contract.event;

public record PlayerVerificationEmailEvent(String toAddress, String verifyUrl, String langKey, String firstName) {}
