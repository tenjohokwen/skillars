-- feat/user-self-service-safety: two additive changes in the admin schema.
--
-- 1. admin.gdpr_requests.player_id -- a parent may now request export/erasure for a parent-owned
--    (shadow) child profile. user_id stays the REQUESTER (the parent); player_id, when set, scopes
--    the request to that main.player_profiles row. NULL keeps today's meaning (the requester's own
--    account). No FK: an erased child's profile row survives (it is scrubbed, not deleted) and the
--    request row must outlive any future hard delete of the profile.
--
--    The active-request uniqueness guard (idx_gdpr_requests_unique_active, baseline V138) is
--    (user_id, request_type) with no notion of scope, so a parent's own pending export would collide
--    with an export for their child. It is replaced by two scoped partial indexes:
--      * self-scope:  (user_id, request_type)   WHERE player_id IS NULL     AND status active
--      * child-scope: (player_id, request_type) WHERE player_id IS NOT NULL AND status active
--    Both back GdprRequestService's existence pre-check against the concurrent-duplicate race (the
--    loser's INSERT fails and is mapped to 409 gdpr.requestAlreadyPending).
--
-- 2. admin.appeals -- FR-ADM-004 / FR-TSC-003: one appeal per enforcement action, within 14 days,
--    final decision. (subject_type, subject_id) names the enforced thing; enforced_at pins WHICH
--    enforcement of it (a coach can be suspended, reinstated and suspended again -- same subject_id,
--    a new action). uq_appeals_per_enforcement makes "one appeal per action, decision final" a DB
--    invariant; uq_appeals_one_open_per_subject additionally caps a subject at one OPEN appeal.
SET LOCAL lock_timeout = '5s';

ALTER TABLE admin.gdpr_requests
    ADD COLUMN IF NOT EXISTS player_id bigint;

-- migration-lint: allow-blocking-index <reason: admin.gdpr_requests is a low-volume table (one row per
-- user-initiated GDPR request); no production deployment yet; CREATE INDEX CONCURRENTLY has no working
-- mechanism in this repo's Flyway runner (migration-conventions.md rule 4)>
CREATE UNIQUE INDEX IF NOT EXISTS idx_gdpr_requests_unique_active_self
    ON admin.gdpr_requests (user_id, request_type)
    WHERE player_id IS NULL AND status IN ('PENDING', 'PROCESSING');

-- migration-lint: allow-blocking-index <reason: same low-volume table as above>
CREATE UNIQUE INDEX IF NOT EXISTS idx_gdpr_requests_unique_active_player
    ON admin.gdpr_requests (player_id, request_type)
    WHERE player_id IS NOT NULL AND status IN ('PENDING', 'PROCESSING');

-- The scope-blind predecessor. Its last reader is GdprRequestService.requestExport/requestErasure's
-- pre-V171 unscoped insert path, replaced in this same release by the two scoped indexes above, which
-- together enforce a strict superset of the per-scope guarantees (a self request can no longer block
-- an unrelated child-scoped one). No code references the index by name.
-- migration-lint: drop-prepared-in: V138
DROP INDEX IF EXISTS admin.idx_gdpr_requests_unique_active;

CREATE TABLE IF NOT EXISTS admin.appeals (
    id                 uuid DEFAULT gen_random_uuid() NOT NULL,
    appellant_user_id  bigint NOT NULL,
    subject_type       character varying(30) NOT NULL,
    subject_id         uuid NOT NULL,
    enforced_at        timestamp with time zone NOT NULL,
    reason             character varying(2000) NOT NULL,
    status             character varying(15) DEFAULT 'OPEN' NOT NULL,
    decision_note      character varying(1000),
    decided_by         bigint,
    decided_at         timestamp with time zone,
    reversal_applied   boolean,
    created_at         timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT appeals_pkey PRIMARY KEY (id),
    CONSTRAINT appeals_subject_type_check CHECK (subject_type IN
        ('RELIABILITY_STRIKE', 'COACH_SUSPENSION', 'REVIEW_BLOCK', 'DISPUTE_RESOLUTION')),
    CONSTRAINT appeals_status_check CHECK (status IN ('OPEN', 'UPHELD', 'OVERTURNED')),
    CONSTRAINT appeals_decision_consistent CHECK (
        (status = 'OPEN' AND decided_at IS NULL AND decided_by IS NULL)
        OR (status <> 'OPEN' AND decided_at IS NOT NULL AND decided_by IS NOT NULL))
);

-- migration-lint: allow-blocking-index <reason: admin.appeals is created empty by this same migration>
CREATE UNIQUE INDEX IF NOT EXISTS uq_appeals_per_enforcement
    ON admin.appeals (subject_type, subject_id, enforced_at);

-- migration-lint: allow-blocking-index <reason: admin.appeals is created empty by this same migration>
CREATE UNIQUE INDEX IF NOT EXISTS uq_appeals_one_open_per_subject
    ON admin.appeals (subject_type, subject_id)
    WHERE status = 'OPEN';

-- migration-lint: allow-blocking-index <reason: admin.appeals is created empty by this same migration>
CREATE INDEX IF NOT EXISTS idx_appeals_status_created
    ON admin.appeals (status, created_at);

-- migration-lint: allow-blocking-index <reason: admin.appeals is created empty by this same migration>
CREATE INDEX IF NOT EXISTS idx_appeals_appellant
    ON admin.appeals (appellant_user_id, created_at DESC);
