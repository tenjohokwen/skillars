-- skillars-deferred-90 AC13: durable outbox for storage-key deletions that must NOT run inside a
-- request / erasure transaction.
--
-- Before this, GdprErasureService.erase() (a REQUIRES_NEW transaction) issued N sequential blocking
-- S3 deleteObject round-trips inside the transaction — one per COMPLETED export .zip and one per
-- performance-report PDF — so the DB connection was held for the full duration of every S3 call and
-- the erasure cost scaled with the account's report/export history. No bulk-delete API exists in the
-- storage stack (StorageService/S3StorageService/FileStorageService are all single-key), so the
-- fix is option (a): enqueue every key here inside the erasure transaction, commit, then drain this
-- table from a @TransactionalEventListener(AFTER_COMMIT) step off the request path. `attempts` +
-- `last_error` make a post-commit S3 failure re-drivable on the next sweep instead of lost.
--
-- Expand/contract: additive CREATE TABLE only, no DROP, no FK/CHECK, no secondary index — this is
-- the first migration above the V121 grandfather baseline and passes MigrationConventionLintTest.
CREATE TABLE main.pending_blob_deletions (
    id          bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    storage_key text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    attempts    integer     NOT NULL DEFAULT 0,
    last_error  text
);
