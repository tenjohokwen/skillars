package com.softropic.skillars.platform.admin.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * skillars-deferred-153 AC1. {@code note} is capped at 450, not 500, deliberately.
 *
 * <p>{@code admin.admin_action_log.reason} is {@code varchar(500)}, and
 * {@code AdminBookingCompletionService} appends the force-complete outcome to the note before
 * storing it (" [outcome: ALREADY_RESOLVED]" is the longest suffix, at 28 characters). At the
 * previous {@code max = 500} a full-length note overflowed the column and surfaced as a
 * {@code DataIntegrityViolationException} at commit — caught in code review (2026-10-17). 450 + 28
 * leaves headroom without needing to truncate, which would risk cutting the outcome marker that is
 * the audit-critical part of the row.
 */
public record AdminForceCompleteBookingRequest(
    @NotBlank @Size(max = 450) String note) {}
