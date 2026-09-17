-- skillars-deferred-117 AC1: drop the now-fully-unused main.pending_blob_deletions legacy table.
--
-- skillars-deferred-100 (2026-09-08) stopped WRITING this table, folding it onto the generic
-- platform.outbox, but kept the PendingBlobDeletion entity, PendingBlobDeletionRepository and
-- PendingBlobDeletionResidualDrainRunner (a startup ApplicationRunner) for one release so any row
-- a prior release left behind could still be read once and migrated onto the generic outbox. That
-- Java surface is deleted in this same commit (skillars-deferred-117 AC1); V141 (this same story)
-- is the marker migration for that release boundary.
--
-- The table's own originally-stated closing condition — "confirmed deployed and provably empty in
-- every environment" — was re-confirmed unmet as recently as 2026-09-15, but can never be met
-- pre-launch: no production deploy of this application has ever happened, at all, in any release.
-- Owner decision (2026-09-16, skillars-deferred-117): the expand/contract "drop only in a later
-- release" convention exists to protect an old-release pod still reading this table while a new
-- release's migration drops it mid-rolling-deploy — that scenario cannot occur here, since there
-- has never been a running release to be "old." Proceed with the drop now.
--
-- Postgres drops an IDENTITY column's backing sequence automatically when its owning table is
-- dropped (main.pending_blob_deletions_id_seq was created via `GENERATED ALWAYS AS IDENTITY`, see
-- V138__baseline_schema.sql:2408-2412) — no separate DROP SEQUENCE is needed or added here.
SET lock_timeout = '5s';

-- migration-lint: drop-prepared-in: V141
DROP TABLE IF EXISTS main.pending_blob_deletions;
