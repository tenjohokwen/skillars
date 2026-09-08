-- skillars-deferred-100 AC2: durable pre-create record for a video-provider (Bunny) asset.
--
-- The gap: VideoService.initializeUpload creates the provider asset, then persists the local
-- Video / UploadSession rows. When a CALLER wraps that in its own transaction
-- (DrillUploadService.initiateUpload is @Transactional and retries under a PessimisticLockRetryer
-- that can exhaust), a rollback after the provider call discards the local rows but not the remote
-- asset. ReconciliationWorkerScheduler only iterates rows that HAVE a Video, so nothing ever
-- revisits the asset — it bills and stores PII forever, and ReconciliationIncidentType.ORPHANED_ASSET
-- had no producer.
--
-- A row is written here in its OWN committed transaction (PendingProviderAssetTracker, REQUIRES_NEW)
-- the instant the asset is created, so it survives the caller's rollback. A successful upload's
-- step-8 transaction deletes its own row (shares fate with the Video persist).
-- ReconciliationWorkerScheduler.sweepOrphanedProviderAssets() purges any row older than
-- app.video.orphan-asset.ttl with no matching videos.provider_asset_id via
-- videoProviderAdapter.deleteAsset(...) (idempotent — 404 == success) outside any transaction, and
-- records a ReconciliationIncident(ORPHANED_ASSET, ...).
--
-- Expand/contract: additive CREATE TABLE only — no DROP, no FK (provider_asset_id is a Bunny
-- string; the sweeper checks videos.provider_asset_id in application code), one UNIQUE on the
-- natural key. Above the V121 baseline; passes MigrationConventionLintTest.
--
-- Asset-id reuse (skillars-deferred-100 AC2 review): the UNIQUE(provider_asset_id) here plus
-- Bunny returning a fresh GUID per create call (never content-deduped) mean a swept row can never
-- collide with a concurrent live upload — see ReconciliationWorkerScheduler.sweepOrphanedProviderAssets
-- Javadoc. created_at drives the TTL grace window and is the tunable safety margin.
--
-- skillars-deferred-100 code review (2026-09-08): `attempts` lets the sweeper order fewest-failures
-- first so a permanently-failing purge (bad provider API key, persistent 5xx) sinks below fresh
-- work instead of blocking the batch head; the (attempts, created_at) index backs that ordered,
-- TTL-filtered scan.
SET lock_timeout = '5s';

CREATE TABLE main.pending_provider_asset (
    id                bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provider_asset_id text        NOT NULL UNIQUE,
    provider          text        NOT NULL,
    attempts          integer     NOT NULL DEFAULT 0,
    created_at        timestamptz NOT NULL DEFAULT now()
);

-- migration-lint: allow-blocking-index brand-new empty table created in this same migration — nothing to lock
CREATE INDEX ix_pending_provider_asset_sweep
    ON main.pending_provider_asset (attempts, created_at);
