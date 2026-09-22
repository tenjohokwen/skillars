-- skillars-deferred-128 AC2: GdprErasureService's new per-erase lock-budget deadline needs to raise
-- a targeted AdminAlert when a PARENT erasure's children exceed GDPR_ERASE_LOCK_BUDGET, so operators
-- learn about a request stuck in FAILED with no auto-retry instead of it sitting silent. Neither
-- admin.admin_alerts CHECK constraint has a value that fits: `type` is limited to MESSAGE_REPORT /
-- CONVERSATION_REPORT / REVIEW_FLAG / STRIKE_THRESHOLD / DISPUTE_RAISED / MODERATION_UNRESOLVED, and
-- `reference_type` to MESSAGE / CONVERSATION / REVIEW / COACH / BOOKING (V138__baseline_schema.sql).
-- Reusing an existing value (e.g. MODERATION_UNRESOLVED / COACH) would misrepresent this alert's real
-- subject in the queue UI and in AdminQueueService's per-type counts. Per this project's own rule 5
-- (docs/deployment/migration-conventions.md — "CHECK / enum-domain widening precedes the first write
-- by one release") this widening should ship a release ahead of the code that writes the new values;
-- per skillars-deferred-117's owner decision (re-confirmed at V149, still true here — no production
-- deploy of this application has ever happened), there is no live rolling-deploy window this could
-- break today, so the widening and its first write ship together in this same story, matching V145's
-- own precedent for a first-time CHECK addition.
--
-- New values: GDPR_ERASURE_DEADLINE (21 chars, fits the existing type varchar(25) — no column
-- widening needed) and GDPR_REQUEST (12 chars, fits the existing reference_type varchar(15)) —
-- referenceId will be the GdprRequest's own requestId (a UUID, 36 chars, already the column's max).
--
-- NOT VALID / VALIDATE skipped (migration-lint: allow-validating-constraint) — admin.admin_alerts is
-- an internal admin-queue table with no bulk-insert path and no expected row count anywhere near
-- large-table territory; a validating ADD CONSTRAINT here is a normal, fast full-table CHECK scan,
-- not the kind of lock exposure rule 3 exists to avoid on a large/hot table.
SET LOCAL lock_timeout = '5s';

ALTER TABLE admin.admin_alerts
    DROP CONSTRAINT IF EXISTS admin_alerts_type_check,
    -- migration-lint: allow-validating-constraint small, internal admin-queue table; no bulk-insert
    -- path and no row count anywhere near large-table territory, so a validating CHECK swap is a fast
    -- full-table scan, not a rolling-deploy lock-exposure risk.
    ADD CONSTRAINT admin_alerts_type_check CHECK (((type)::text = ANY ((ARRAY[
        'MESSAGE_REPORT'::character varying,
        'CONVERSATION_REPORT'::character varying,
        'REVIEW_FLAG'::character varying,
        'STRIKE_THRESHOLD'::character varying,
        'DISPUTE_RAISED'::character varying,
        'MODERATION_UNRESOLVED'::character varying,
        'GDPR_ERASURE_DEADLINE'::character varying
    ])::text[])));

ALTER TABLE admin.admin_alerts
    DROP CONSTRAINT IF EXISTS admin_alerts_reference_type_check,
    -- migration-lint: allow-validating-constraint same table/reasoning as above.
    ADD CONSTRAINT admin_alerts_reference_type_check CHECK (((reference_type)::text = ANY ((ARRAY[
        'MESSAGE'::character varying,
        'CONVERSATION'::character varying,
        'REVIEW'::character varying,
        'COACH'::character varying,
        'BOOKING'::character varying,
        'GDPR_REQUEST'::character varying
    ])::text[])));
