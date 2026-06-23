package com.softropic.skillars.platform.video.contract.event;

import java.util.UUID;

/**
 * Fired AFTER_COMMIT when a video is logically deleted (set to PURGED).
 * This is an extension hook — it does NOT mean Bunny.net physical deletion is complete.
 * Physical deletion is handled asynchronously by VideoDeletionOutboxProcessor.
 * Story 10.4 must NOT treat this event as "Bunny deletion done."
 */
public record VideoPhysicalDeletionEvent(UUID videoId) {}
