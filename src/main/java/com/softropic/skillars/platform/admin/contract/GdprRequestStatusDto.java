package com.softropic.skillars.platform.admin.contract;

import java.time.Instant;
import java.util.UUID;

/**
 * A requester's own view of one of their GDPR requests. {@code downloadUrl} is populated only for a
 * COMPLETED, not-yet-expired EXPORT — the frontend links to it directly instead of following the
 * legacy {@code GET /api/gdpr/export/{id}} 302 (a cross-origin redirect an XHR cannot use).
 */
public record GdprRequestStatusDto(
    UUID requestId,
    String requestType,
    String status,
    Instant createdAt,
    Instant completedAt,
    Instant expiresAt,
    String downloadUrl
) {}
