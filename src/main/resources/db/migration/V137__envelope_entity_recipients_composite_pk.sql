-- skillars-deferred-111 AC12 (owner decision): envelope_entity_recipients has had no primary key
-- and no index on its envelope_entity_id foreign key since V136 pinned it as a faithful,
-- unindexed copy of what Hibernate's auto-DDL had been silently producing (Hibernate's own
-- @ElementCollection mapping has never carried an @Id for this table). Every collection load and
-- every FK cascade-check on this join table has therefore been a sequential scan, and nothing at
-- the DB level prevented a duplicate recipient row for the same envelope.
--
-- Owner decision: add a composite PRIMARY KEY on (envelope_entity_id, email) rather than a weaker
-- surrogate-PK-plus-index — email is the table's actual recipient-identifying column (it has no
-- column literally named "recipient"; its columns are email, firstname, gender, lang_key,
-- lastname, title). This is a stronger guarantee than a surrogate key: a true duplicate INSERT now
-- fails instead of silently succeeding. Confirmed acceptable to apply directly, with no separate
-- backfill/dedup pass first: there is no dev/uat/prod data in this table today (registration/OTP
-- mail only started routing through EnvelopeEntity as of ses-1.4, and this codebase only ever
-- sends one recipient per envelope per SmtpErrorClassifier's own javadoc), so no existing row can
-- violate the new constraint.
--
-- A composite PRIMARY KEY needs every key column NOT NULL — Postgres enforces this itself, but the
-- explicit SET NOT NULL below (email was nullable, unenforced, under Hibernate's own mapping) makes
-- the requirement visible in this file rather than surfacing only as a PRIMARY KEY error. This is
-- safe without a prior nullable-then-backfill release (rule 1's usual two-step) for the same
-- "no existing data" reason above — there is no NULL email in any environment to break on.
--
-- V137 is above the V129 baseline, so MigrationConventionLintTest binds fully; both ALTER TABLE
-- statements below are lock-taking DDL (ALTER COLUMN / ADD CONSTRAINT ADD PRIMARY KEY per
-- migration-conventions.md's go-forward checklist), hence the bounded lock_timeout. Postgres
-- creates the underlying unique index for the PRIMARY KEY as part of ADD CONSTRAINT itself — not a
-- separate CREATE INDEX statement — so MigrationLint's BLOCKING_INDEX rule (which matches literal
-- CREATE INDEX text) does not apply here and no allow-blocking-index opt-out is needed.

SET lock_timeout = '5s';

ALTER TABLE main.envelope_entity_recipients
    ALTER COLUMN email SET NOT NULL;

ALTER TABLE main.envelope_entity_recipients
    ADD CONSTRAINT envelope_entity_recipients_pkey PRIMARY KEY (envelope_entity_id, email);
