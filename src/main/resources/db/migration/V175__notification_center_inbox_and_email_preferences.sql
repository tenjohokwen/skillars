-- Notification centre (UI-gap chunk C9, gap D10): the in-app inbox and per-category email opt-out.
--
-- main.user_notifications — one user-facing inbox row per in-app-eligible email event. Written by
-- InAppNotificationService inside the SAME business transaction that enqueues the email (the
-- BEFORE_COMMIT listeners → NotificationOutboxSupport.enqueueEmail, or the BEFORE_COMMIT Envelope
-- listener), so a rolled-back business transaction never leaves an inbox row behind.
--   * recipient_user_id is a main."user" id (never a PlayerProfile PK) — resolved from the email
--     recipient, so a minor's notifications land with the parent exactly as the email does.
--   * type is the EmailTemplate name; the frontend renders notifications.types.<type> with params.
--   * params holds a whitelisted, string-only subset of the email data (never tokens, OTPs or URLs).
--   * source_send_id + recipient dedupes a double hook (ON CONFLICT DO NOTHING in the writer).
--   * ON DELETE CASCADE: UserAdminService's non-activated-user cleanup hard-deletes users; an inbox
--     row must never block that delete.
--
-- main.notification_email_preferences — sparse opt-out rows. No row = the default (email enabled).
-- Only opt-out-able categories (NotificationCategory.optOutable) are ever written; the writer is an
-- atomic INSERT ... ON CONFLICT DO UPDATE, so concurrent toggles/unsubscribes cannot lose a write.
--
-- No CHECK on category/type: both are application enums that will grow, and a CHECK would force an
-- enum-widening migration a release ahead of every new email (docs/deployment/migration-conventions.md
-- rule 5). Constraints are inline in CREATE TABLE (new, empty tables — nothing to validate; see V148).
SET LOCAL lock_timeout = '5s';

CREATE TABLE IF NOT EXISTS main.user_notifications (
    id                uuid         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    recipient_user_id bigint       NOT NULL REFERENCES main."user" (id) ON DELETE CASCADE,
    category          varchar(32)  NOT NULL,
    type              varchar(64)  NOT NULL,
    params            jsonb        NOT NULL DEFAULT '{}'::jsonb,
    deep_link         varchar(512),
    source_send_id    varchar(255),
    created_at        timestamptz  NOT NULL DEFAULT now(),
    read_at           timestamptz
);

-- migration-lint: allow-blocking-index <reason: the table is created empty by this same migration, so
-- the SHARE lock is held on zero rows>
CREATE INDEX IF NOT EXISTS idx_un_recipient_created
    ON main.user_notifications (recipient_user_id, created_at DESC);

-- Serves the unread badge (COUNT ... WHERE read_at IS NULL) and mark-all-read without touching read rows.
-- migration-lint: allow-blocking-index <reason: the table is created empty by this same migration, so
-- the SHARE lock is held on zero rows>
CREATE INDEX IF NOT EXISTS idx_un_recipient_unread
    ON main.user_notifications (recipient_user_id)
    WHERE read_at IS NULL;

-- migration-lint: allow-blocking-index <reason: the table is created empty by this same migration, so
-- the SHARE lock is held on zero rows>
CREATE UNIQUE INDEX IF NOT EXISTS uq_un_recipient_send_id
    ON main.user_notifications (recipient_user_id, source_send_id)
    WHERE source_send_id IS NOT NULL;

-- Retention sweep (InAppNotificationRetentionScheduler) deletes by created_at across all recipients.
-- migration-lint: allow-blocking-index <reason: the table is created empty by this same migration, so
-- the SHARE lock is held on zero rows>
CREATE INDEX IF NOT EXISTS idx_un_created_at
    ON main.user_notifications (created_at);

CREATE TABLE IF NOT EXISTS main.notification_email_preferences (
    user_id       bigint       NOT NULL REFERENCES main."user" (id) ON DELETE CASCADE,
    category      varchar(32)  NOT NULL,
    email_enabled boolean      NOT NULL,
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, category)
);
