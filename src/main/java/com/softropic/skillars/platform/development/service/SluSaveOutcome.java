package com.softropic.skillars.platform.development.service;

/**
 * Outcome of {@link SluPersistenceRetrier#saveSluWithRetry} — a tri-state, not a boolean, because
 * {@link SluPersistenceDispatcher} must distinguish three cases before deciding whether to run the
 * weekly-snapshot write (skillars-deferred-89 AC2, tightened by its code review):
 *
 * <ul>
 *   <li>{@link #SAVED} — <em>this</em> invocation persisted the detail rows (or the batch was empty).
 *       The dispatcher runs {@code writeAllWithRetry} for this task's iso-week bucket.</li>
 *   <li>{@link #ALREADY_PERSISTED} — the rows were already persisted by a <em>different</em>
 *       {@code BookingCompletedEvent} delivery (the {@code existsBySessionId} short-circuit or the
 *       V47 {@code uq_player_skill_stats_session_skill} collision catch). This is not a failure, but
 *       the dispatcher must <strong>skip</strong> the snapshot write: the delivery that actually
 *       persisted the rows runs the snapshot write for the correct bucket, and this task's own
 *       {@code isoYear}/{@code isoWeek} — derived from a per-invocation {@code Instant.now()} — can
 *       differ (two deliveries seconds apart across a Monday 00:00 UTC boundary land in different
 *       week buckets), so running it here would add a delta to a bucket the winner never marked and
 *       {@code player_slu_weekly_snapshot} would over-report against the detail rows.</li>
 *   <li>{@link #FAILED} — retries exhausted, rows lost (the {@code @Recover} paths). The dispatcher
 *       skips the snapshot write and logs the recovery ERROR.</li>
 * </ul>
 */
public enum SluSaveOutcome {
    SAVED,
    ALREADY_PERSISTED,
    FAILED
}
