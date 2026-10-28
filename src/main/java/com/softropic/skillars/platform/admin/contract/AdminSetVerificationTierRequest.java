package com.softropic.skillars.platform.admin.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Admin sets a coach's marketplace verification tier (D6). Values mirror the
 * {@code marketplace.coach_profiles.chk_verification_tier} CHECK constraint. {@code reason} is
 * optional and capped below admin_action_log.reason's 500 chars to leave room for the
 * "OLD -> NEW" prefix the audit row carries.
 */
public record AdminSetVerificationTierRequest(
    @NotBlank @Pattern(regexp = "BASIC|TRUSTED|FEATURED") String tier,
    @Size(max = 450) String reason) {}
