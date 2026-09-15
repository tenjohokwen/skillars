-- skillars-deferred-113 AC1: per-recipient delivery tracking for multi-recipient envelopes.
--
-- Before this column, MailManager.sendEmailSync's recipient loop had no durable record of which
-- individual recipients within an envelope had already been sent to. A mid-loop rate-limit
-- rejection (or any other exception) at recipient k of n aborted the whole send; on retry the loop
-- restarted at recipient 1, re-sending to every recipient before k a second time.
--
-- Additive, nullable-safe default per the expand/contract standard (docs/deployment/
-- migration-conventions.md rule 1): a constant boolean DEFAULT on ADD COLUMN is a fast,
-- non-rewriting metadata change in PostgreSQL 11+, so NOT NULL DEFAULT false ships in one step —
-- envelope_entity_recipients is also a small, transient table (per-send working state, not
-- historical/analytical), unlike the large-table case rule 1 is mainly written for.
SET lock_timeout = '5s';

ALTER TABLE main.envelope_entity_recipients
    ADD COLUMN IF NOT EXISTS delivered boolean NOT NULL DEFAULT false;
