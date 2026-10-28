package com.softropic.skillars.platform.video.contract;

import java.time.Instant;
import java.util.UUID;

/**
 * @param subjectBookingId V184: the session a coach video is about (null when it has no subject)
 * @param subjectPlayerId  V184: the PlayerProfile id of the child it shows (null when no subject)
 */
public record VideoSummaryResponse(
        UUID id,
        String title,
        OperationalState operationalState,
        AccessState accessState,
        String ownerId,
        Visibility visibility,
        String videoType,
        Instant createdAt,
        Instant updatedAt,
        UUID subjectBookingId,
        Long subjectPlayerId) {

    public VideoSummaryResponse(UUID id, String title, OperationalState operationalState, AccessState accessState,
                                String ownerId, Visibility visibility, String videoType, Instant createdAt,
                                Instant updatedAt) {
        this(id, title, operationalState, accessState, ownerId, visibility, videoType, createdAt, updatedAt,
            null, null);
    }
}
