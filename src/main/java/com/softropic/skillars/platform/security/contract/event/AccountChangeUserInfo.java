package com.softropic.skillars.platform.security.contract.event;

public record AccountChangeUserInfo(
    String email,
    String firstname,
    String lastname,
    String langKey,
    String title,
    String gender
) {}
