package com.softropic.skillars.platform.video.contract;

/**
 * Filter for the admin video review queue (story 6.3 / FR-ADM-002). Every value excludes videos that
 * are already gone ({@code DELETED}, {@code PURGED}).
 *
 * <ul>
 *   <li>{@link #ATTENTION} — any of the three below (the default queue).</li>
 *   <li>{@link #LOCKED} — {@code operational_state = LOCKED}: moderation flagged the content; terminal
 *       until an admin acts (delete, or leave it locked).</li>
 *   <li>{@link #FAILED} — {@code operational_state = FAILED}: upload/transcode/moderation failed;
 *       reconcile against the provider or delete.</li>
 *   <li>{@link #BLOCKED} — {@code access_state = BLOCKED}: playback blocked (subscription expiry or a
 *       manual admin block); the lifecycle scheduler archives it after the configured window.</li>
 * </ul>
 */
public enum AdminVideoQueueFilter {
    ATTENTION, LOCKED, FAILED, BLOCKED
}
