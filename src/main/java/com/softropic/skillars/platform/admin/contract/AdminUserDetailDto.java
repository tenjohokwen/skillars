package com.softropic.skillars.platform.admin.contract;

import java.time.Instant;
import java.util.List;

/**
 * Admin view of one user account (D7). {@code privileged} is true for ADMIN / LTD_ADMIN accounts,
 * which the suspend endpoint refuses; {@code recentFailedLoginAttempts} counts failed logins for this
 * account (any IP) inside the current login rate-limit window.
 */
public record AdminUserDetailDto(
    Long userId,
    String login,
    String email,
    String firstName,
    String lastName,
    String role,
    boolean activated,
    boolean suspended,
    String verificationStatus,
    Instant createdAt,
    boolean privileged,
    long recentFailedLoginAttempts,
    List<AdminUserActionDto> history) {}
