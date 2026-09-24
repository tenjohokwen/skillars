-- skillars-deferred-133 AC3: StripeWebhookService.handleSubscriptionUpdated's existing orphan-
-- detection branch (a customer.subscription.updated event whose stripeSubscriptionId matches no local
-- payment.coach_subscriptions/payment.player_subscriptions row) currently just log.warns and drops the
-- event. This closes the Stripe -> payment reconciliation residual (deferred-work.md, open since
-- skillars-deferred-131) for the live/non-terminal-status, coach-resolvable case: alert an admin
-- instead of silently dropping it. admin.admin_alerts's `type` CHECK constraint
-- (V138__baseline_schema.sql, last widened by V152) has no value that fits — reusing an existing one
-- (e.g. STRIKE_THRESHOLD) would misrepresent this alert's real subject in the queue UI and in
-- AdminQueueService's per-type counts, mirroring V152's own reasoning.
--
-- New value: SUBSCRIPTION_ORPHANED (21 chars, fits the existing type varchar(25) — no column widening
-- needed). No reference_type migration needed: this alert's referenceId is a marketplace coach_profiles
-- id, and COACH already exists in admin_alerts_reference_type_check.
--
-- NOT VALID / VALIDATE skipped (migration-lint: allow-validating-constraint), matching V152's own
-- reasoning verbatim: admin.admin_alerts is an internal admin-queue table with no bulk-insert path and
-- no expected row count anywhere near large-table territory, so a validating ADD CONSTRAINT here is a
-- normal, fast full-table CHECK scan, not the kind of lock exposure rule 3 exists to avoid on a
-- large/hot table. Per skillars-deferred-117's owner decision (re-confirmed at V149/V152, still true
-- here — no production deploy of this application has ever happened), there is no live rolling-deploy
-- window this could break today, so the widening and its first write ship together in this same story.
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
        'GDPR_ERASURE_DEADLINE'::character varying,
        'SUBSCRIPTION_ORPHANED'::character varying
    ])::text[])));
