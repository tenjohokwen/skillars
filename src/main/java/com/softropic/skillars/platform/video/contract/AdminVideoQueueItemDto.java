package com.softropic.skillars.platform.video.contract;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One row of the admin video review queue, also returned by the single-video admin lookup.
 *
 * <p>{@code flaggedLayers} names the moderation layers ({@code ARACHNID}, {@code VIDEOINTEL},
 * {@code MINOR_GATE}) whose latest scan outcome is {@code FLAGGED}. The scan {@code details} text is
 * deliberately not exposed here — for an ARACHNID hit it describes a CSAM hash match, which an
 * operations list has no reason to render.
 */
public record AdminVideoQueueItemDto(
        UUID id,
        String title,
        OperationalState operationalState,
        AccessState accessState,
        String ownerId,
        Visibility visibility,
        String videoType,
        Instant createdAt,
        Instant updatedAt,
        Instant lifecycleLockedAt,
        Instant archivedAt,
        List<String> flaggedLayers) {}
