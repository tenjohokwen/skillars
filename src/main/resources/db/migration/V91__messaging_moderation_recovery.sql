-- Story Deferred-16.

-- AC2: grace period for MessageModerationSweeper. 30 minutes is well clear of Gemini call
-- latency: the moderation call is synchronous in the request thread, so anything still PENDING
-- after half an hour is not in flight — it is stranded.
INSERT INTO main.platform_config (id, key, value, value_type, description, updated_at)
VALUES (602, 'platform.messaging.moderation_orphan_grace_minutes', '30', 'LONG',
        'Minutes a message may sit in PENDING moderation before MessageModerationSweeper treats it as stranded', NOW())
ON CONFLICT (key) DO NOTHING;

-- AC3: admin.admin_alerts' type CHECK (V70) must admit MODERATION_UNRESOLVED or every insert of
-- that new alert type fails at INSERT time (the JPA @Enumerated(EnumType.STRING) side will
-- happily write a value the CHECK rejects).
ALTER TABLE admin.admin_alerts DROP CONSTRAINT IF EXISTS admin_alerts_type_check;
ALTER TABLE admin.admin_alerts ADD CONSTRAINT admin_alerts_type_check
    CHECK (type IN ('MESSAGE_REPORT','CONVERSATION_REPORT','REVIEW_FLAG','STRIKE_THRESHOLD','DISPUTE_RAISED','MODERATION_UNRESOLVED'));

-- Code review 2026-08-05: carry MessageHeldForReviewEvent.reason onto the alert. Without it an
-- admin cannot tell "the AI was unsure about this content" (MODERATION_UNCERTAIN) from "this was
-- never moderated at all" (MODERATION_ORPHAN_SWEPT) — two situations warranting different scrutiny.
-- Nullable: every other alert type has no reason, and the column is written only by the messaging
-- moderation path.
ALTER TABLE admin.admin_alerts ADD COLUMN IF NOT EXISTS reason VARCHAR(64);

-- AC6d / Task 8(d): idx_message_reports_message_id and idx_conversation_reports_conversation_id
-- (V66) were the intended target here, but V81__drop_redundant_report_indexes.sql already dropped
-- both against the same uq_message_reports_message_reporter / uq_conversation_reports_conv_reporter
-- leading-column justification. Re-verified against the current tree at story implementation time
-- (2026-08-05): both indexes are already gone. No DDL needed — see deferred-work.md audit block
-- for the correction to the story's AC6d text.
