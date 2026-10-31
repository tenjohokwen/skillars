package com.softropic.skillars.platform.admin.contract;

import java.time.Instant;
import java.util.UUID;

/**
 * Admin queue row for a coach's safeguarding-check request. {@code hasUpload} says whether an encrypted certificate
 * upload is still on file (it is deleted when staff decide, and by the sweeper); without one the inspection happens
 * another way (e.g. a video call). {@code hasValidCheck}: this is a renewal.
 */
public record AdminSafeguardingRequestDto(
    UUID coachId,
    String coachDisplayName,
    String checkType,
    Instant requestedAt,
    boolean hasUpload,
    Instant uploadStoredAt,
    boolean hasValidCheck
) {
}
