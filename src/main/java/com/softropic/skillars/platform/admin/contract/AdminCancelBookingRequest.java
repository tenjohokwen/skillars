package com.softropic.skillars.platform.admin.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * C6: an admin cancelling one booking. {@code note} (the support ticket / safety-hold follow-up) is
 * required and capped at 450, like {@link AdminForceCompleteBookingRequest}: the service appends the
 * outcome (" [outcome: PAYMENT_IN_PROGRESS]", 31 characters at most) before storing it in the
 * {@code varchar(500)} {@code admin_action_log.reason}.
 */
public record AdminCancelBookingRequest(
    @NotBlank @Size(max = 450) String note) {}
