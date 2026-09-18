-- skillars-deferred-123 AC3: both main.video_deletion_outbox and development.radar_composite_dlq
-- reclaim stuck CLAIMED rows via resetStaleClaimed(WHERE status = 'CLAIMED' AND next_retry_at <
-- :deadline) — but claimPendingBatch never stamped next_retry_at when it claimed a row, so
-- next_retry_at is the row's *eligibility* timestamp, not its *claim* timestamp. A row that was
-- already backlogged (eligible well before this run started) gets judged "stale" and un-claimed out
-- from under a still-in-flight processor based on how long it sat in the queue, not how long it has
-- actually been claimed. A concurrent tick's resetStaleClaimed can then free it while the first
-- instance is still processing it, and claimPendingBatch immediately re-claims it — genuine duplicate
-- deleteAsset/recalculateComposite work.
--
-- claimed_at also closes findClaimedBatch()'s own half of this: that query is globally scoped
-- (status = 'CLAIMED', no per-run filter), so a second instance's findClaimedBatch() would still
-- return the first instance's still-in-flight rows even after resetStaleClaimed itself is fixed.
-- Scoping findClaimedBatch to WHERE claimed_at = :claimedAt (this run's own claim stamp) closes the
-- duplicate-processing path completely — see VideoDeletionOutboxRepository/RadarCompositeDlqRepository
-- for the query changes.
--
-- timestamp with time zone, matching every other timestamp column already on both tables
-- (next_retry_at, created_at) — unlike V143's main."user" precedent, these two tables' own
-- convention is timestamptz throughout, so this column follows that, not V143's rationale.
--
-- Rollout/backfill: immediately after this migration every existing row has claimed_at IS NULL.
-- Under the new claimed_at-scoped findClaimedBatch, a row that was already CLAIMED at migration time
-- (e.g. left behind by an instance that crashed before this deploy) would never match any run's
-- claimed_at and would be stranded forever. Backfill CLAIMED rows to now() so they become reclaimable
-- via the normal resetStaleClaimed path on the next stale-window pass. Both tables are expected to
-- have zero or very few CLAIMED rows at any given moment in practice (a row is CLAIMED only for the
-- duration of one scheduler tick's processing loop) — not batched.
--
-- Index coverage: resetStaleClaimed's predicate moves from next_retry_at < :deadline (served by
-- idx_radar_composite_dlq_status_retry ON (status, next_retry_at) / idx_vdoutbox_status_retry) to
-- claimed_at < :deadline, which those indexes no longer cover past their status prefix
-- (idx_vdoutbox_status_claimed ON (status) WHERE status = 'CLAIMED' still covers the video side's
-- status predicate). Both tables are expected to be small enough (near-zero CLAIMED rows at any
-- moment) that this is accepted as-is; add a matching partial index only if row-count evidence at a
-- future date says otherwise.
SET lock_timeout = '5s';

ALTER TABLE main.video_deletion_outbox
    ADD COLUMN IF NOT EXISTS claimed_at timestamp with time zone;

ALTER TABLE development.radar_composite_dlq
    ADD COLUMN IF NOT EXISTS claimed_at timestamp with time zone;

UPDATE main.video_deletion_outbox
    SET claimed_at = now()
    WHERE status = 'CLAIMED' AND claimed_at IS NULL;

UPDATE development.radar_composite_dlq
    SET claimed_at = now()
    WHERE status = 'CLAIMED' AND claimed_at IS NULL;
