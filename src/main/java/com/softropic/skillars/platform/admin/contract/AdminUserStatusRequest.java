package com.softropic.skillars.platform.admin.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of the admin suspend / reactivate user endpoints; {@code reason} lands in admin_action_log. */
public record AdminUserStatusRequest(
    @NotBlank @Size(max = 500) String reason) {}
