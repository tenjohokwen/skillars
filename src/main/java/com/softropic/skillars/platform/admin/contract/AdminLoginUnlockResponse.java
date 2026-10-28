package com.softropic.skillars.platform.admin.contract;

/** Result of an admin login unlock: how many recorded failed-login attempts were cleared. */
public record AdminLoginUnlockResponse(int clearedAttempts) {}
