package com.softropic.skillars.platform.security.contract.event;

public record CoachVerificationEmailEvent(String toAddress, String verifyUrl, String langKey, String firstName) {}
