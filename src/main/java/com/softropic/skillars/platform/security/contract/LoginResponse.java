package com.softropic.skillars.platform.security.contract;

public record LoginResponse(Long userId, String role, String displayName) {}
