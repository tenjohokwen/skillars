package com.softropic.skillars.platform.admin.contract;

import java.time.Instant;

/** One row of the admin user search (D7). {@code suspended} is {@code main."user".locked}. */
public record AdminUserSummaryDto(
    Long userId,
    String email,
    String firstName,
    String lastName,
    String role,
    boolean activated,
    boolean suspended,
    String verificationStatus,
    Instant createdAt) {}
