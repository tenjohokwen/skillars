-- fix/s18-media-scanning (S-18): abuse-media scanning state on uploaded objects.
--
-- scan_status gates who may be handed a download URL (FileStorageService): only NOT_REQUIRED and CLEAN are served
-- beyond the uploader; PENDING (awaiting a Project Arachnid verdict, or the scanner was unreachable), FLAGGED (hash
-- match) and QUARANTINED (another object of an uploader who had a match) are withheld. See ScanStatus.
-- scan_attempts / last_scan_attempt_at drive the retry sweeper (MediaScanRetryScheduler) for objects the scanner
-- could not judge yet.
--
-- Existing rows default to NOT_REQUIRED: PROD is not live yet (dev/uat data only), and an image uploaded before this
-- migration was never scanned. No CHECK constraint on scan_status: the values are an enum (ScanStatus) written only
-- by targeted UPDATEs, and a validating CHECK on this table would scan it for no safety gain.
--
-- Migration conventions (docs/deployment/migration-conventions.md): the three columns are catalog-only
-- (constant defaults / nullable, PG 11+); the partial index is tiny (only rows awaiting a verdict); lock_timeout
-- bounds the wait behind the storage pollers.
SET LOCAL lock_timeout = '5s';

ALTER TABLE main.file_storage_objects
    ADD COLUMN IF NOT EXISTS scan_status varchar(20) NOT NULL DEFAULT 'NOT_REQUIRED',
    ADD COLUMN IF NOT EXISTS scan_attempts integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_scan_attempt_at timestamp with time zone;

-- migration-lint: allow-blocking-index <reason: CREATE INDEX CONCURRENTLY has no working online-safe
-- mechanism in this repo's single-connection Flyway runner yet (docs/deployment/migration-conventions.md
-- rule 4); the partial predicate keeps the build tiny (only rows awaiting a verdict), and lock_timeout bounds the wait>
CREATE INDEX IF NOT EXISTS idx_storage_objects_scan_pending
    ON main.file_storage_objects USING btree (id)
    WHERE scan_status = 'PENDING' AND deleted_at IS NULL;
