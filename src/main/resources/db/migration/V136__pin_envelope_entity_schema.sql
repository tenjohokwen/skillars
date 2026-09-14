-- skillars-deferred-110 AC9: pins envelope_entity/envelope_entity_recipients — the transactional
-- email outbox's own durable record — as an explicit, version-controlled migration.
--
-- The premise that originally motivated this AC ("no Flyway DDL for this table, so its schema is
-- out-of-band / undocumented provenance") was WRONG: this codebase's own
-- application.yaml sets BOTH `spring.jpa.generate-ddl: true` AND
-- `spring.jpa.hibernate.ddl-auto: none` together, and (a documented Spring Boot property-precedence
-- quirk, independently confirmed empirically for this exact table — see the Dev Agent Record) that
-- combination still results in `hibernate.hbm2ddl.auto=update`. Hibernate has therefore been
-- silently creating AND altering this table in every environment since it was introduced
-- (skillars-ses-1.4).
--
-- Code review 2026-09-14 (owner decision D-3) — corrected claim: an earlier revision of this
-- comment said this migration "is a no-op wherever it runs". That is false on any database where
-- the table does not already exist — which includes every fresh CI Testcontainers Postgres
-- container, since Flyway migrations run BEFORE Hibernate's schema management on every boot. On
-- such a database, THIS migration is what creates the table, not Hibernate — Hibernate then only
-- ever sees an already-existing table and takes its ALTER-only path. The safety-net framing still
-- holds (a future environment should not have to depend on Hibernate auto-DDL for this table), but
-- "no-op" was the wrong description of what it actually does.
--
-- Column shape is pinned to what Hibernate actually produced (dumped from a live Testcontainers
-- Postgres via information_schema/pg_constraint/pg_indexes — not regenerated from the entity, not
-- hand-guessed; see the Dev Agent Record for the raw dump). One shape is deliberately NOT
-- "corrected" here even though it looks surprising: `retry` is `text`, not `boolean`.
-- `EnvelopeEntity.retry` is declared `@Column(columnDefinition = "text")` — with Hibernate auto-DDL
-- live, that columnDefinition IS used, so `retry` really is a text column in every existing
-- database, which is exactly why EnvelopeEntityRepository's native query compares
-- `e.retry = 'true'` (a string literal). A freshly migrated `boolean` column would diverge from
-- every existing database and break that query.
--
-- Code review 2026-09-14 (owner decision D-3) — the `email_template`/`status` CHECK constraints ARE
-- included below, reversing the original draft's omission. That draft argued Hibernate would keep
-- managing them on every boot regardless — false: `AbstractSchemaMigrator` only emits a CHECK
-- constraint in its CREATE TABLE branch; `migrateTable` (its ALTER path for an already-existing
-- table) only adds missing columns and never adds a CHECK. Since this migration is what creates
-- the table on a fresh database (see above), omitting the CHECKs here would have meant every fresh
-- database permanently lacks them — a silent, permanent schema divergence from every database that
-- predates this migration. Both CHECK constraints below are left UNNAMED deliberately: an unnamed
-- single-column CHECK gets PostgreSQL's own default name (`<table>_<column>_check`), which is
-- exactly the name already live on every existing database (confirmed in the same dump) — naming
-- them explicitly would risk a mismatch and a spurious "already exists" on an environment that
-- predates this migration. This does still leave a sync burden: the constant list must be updated
-- by hand alongside EmailTemplate/EmailDeliveryStatus (Hibernate will keep the CHECK a subset match
-- on every subsequent boot in the meantime — see the Dev Agent Record for why an explicit follow-up
-- migration is still needed whenever either enum changes, not left to auto-widen itself).
--
-- `send_id` uniqueness and the recipients FK are both declared INLINE in the CREATE TABLE (matching
-- what `@Column(unique = true)`/the `@ElementCollection` mapping actually produced), not as
-- separate `CREATE UNIQUE INDEX`/`ALTER TABLE ... ADD CONSTRAINT` statements — the former binds
-- both Rule.BLOCKING_INDEX (needs an allow-blocking-index opt-out) and Rule.MISSING_LOCK_TIMEOUT,
-- and CREATE INDEX CONCURRENTLY cannot run inside this project's single-transaction Flyway
-- migrations (see V121's own header comment); an inline constraint sidesteps both cleanly.
--
-- Code review 2026-09-14 (patch) — BOTH are explicitly named to match Hibernate's own
-- generated names (`uk428hhm4tjgrg8cy2092q025po` for the unique constraint,
-- `fk89qpyuf6j5fgg7aorxxh8mqyn` for the FK — both confirmed in the same dump, both Hibernate's own
-- hash-based naming convention, NOT PostgreSQL's default `<table>_<column>_key`/`_fkey` naming).
-- This is load-bearing, not cosmetic: unlike CHECK constraints, `AbstractSchemaMigrator` DOES run
-- `applyUniqueKeys`/`applyForeignKeys` against an already-existing table, and it looks up an
-- existing constraint BY NAME. A plain unnamed inline UNIQUE/FK here would get PostgreSQL's own
-- default name, which would NOT match what Hibernate expects to find — Hibernate would then add a
-- second, redundant constraint under its own name on every fresh database. Naming them to match
-- exactly is what makes this migration idempotent with Hibernate's own subsequent boot, verified
-- empirically against a fresh Testcontainers Postgres (see the Dev Agent Record: no duplicate
-- constraints after a real boot with this migration in place).
--
-- No FK index on envelope_entity_recipients.envelope_entity_id: Hibernate's own auto-DDL did not
-- create one (confirmed in the same dump), so this migration stays a faithful, additive pin of the
-- live shape rather than introducing new index-maintenance cost this table has never actually paid.
--
-- Expand-only: no DROP, no ALTER on an existing column, both CREATE TABLE guarded with
-- IF NOT EXISTS. Neither MISSING_LOCK_TIMEOUT nor BLOCKING_INDEX applies to a plain CREATE TABLE
-- (MigrationConventionLintTest's own patterns only match ALTER TABLE / DROP TABLE|INDEX / TRUNCATE /
-- CREATE INDEX), so no `SET lock_timeout` is needed here.

CREATE TABLE IF NOT EXISTS main.envelope_entity (
    id             uuid          NOT NULL PRIMARY KEY,
    version        bigint        NOT NULL,
    attempts       bigint        NOT NULL,
    data           jsonb,
    deadline       timestamptz,
    email_template varchar(255)  CHECK (email_template IN (
        'NONE', 'ACTIVATION', 'CREATION_DUP', 'PASSWORD_RESET', 'SEND_OTP', 'COACH_EMAIL_VERIFY',
        'COACH_OTP', 'PARENT_EMAIL_VERIFY', 'PARENT_OTP', 'PLAYER_EMAIL_VERIFY', 'PLAYER_OTP',
        'EMAIL_CHANGE', 'PROFILE_CHANGE', 'BOOKING_REQUESTED', 'BOOKING_CONFIRMED',
        'BOOKING_DECLINED', 'BOOKING_PAYMENT_UNRESOLVED', 'BOOKING_EXPIRED', 'BOOKING_REMINDER',
        'BOOKING_QUICK_COMPLETE_CONFIRM', 'BOOKING_RESCHEDULE_REQUESTED',
        'BOOKING_RESCHEDULE_ACCEPTED', 'BOOKING_RESCHEDULE_DECLINED',
        'BOOKING_RESCHEDULE_REQUESTED_BY_COACH', 'BOOKING_RESCHEDULE_DECLINED_BY_PARENT',
        'BOOKING_DUPLICATE_PROPOSED', 'BOOKING_BATCH_REQUESTED', 'BOOKING_BATCH_ACCEPTED',
        'SESSION_PACK_EXPIRY_WARNING', 'SESSION_PACK_EXPIRED', 'BOOKING_CANCELLED_DUE_TO_PAUSE',
        'PACK_PAUSED', 'PERFORMANCE_REPORT_SHARED', 'VIDEO_MODERATION_ADMIN_ALERT',
        'VIDEO_MODERATION_OWNER_FLAGGED', 'BOOKING_CANCELLED_BY_PARENT', 'BOOKING_CANCELLED_BY_COACH',
        'COACH_NO_SHOW', 'PLAYER_NO_SHOW', 'COACH_VISIBILITY_REDUCED'
    )),
    error          text,
    retry          text,
    send_id        varchar(255)  CONSTRAINT uk428hhm4tjgrg8cy2092q025po UNIQUE,
    status         varchar(255)  CHECK (status IN ('SENT', 'FAILED', 'SENDING', 'DELETE', 'DEADLINE_EXPIRED', 'ATTEMPTS_EXHAUSTED'))
);

CREATE TABLE IF NOT EXISTS main.envelope_entity_recipients (
    envelope_entity_id uuid         NOT NULL
        CONSTRAINT fk89qpyuf6j5fgg7aorxxh8mqyn REFERENCES main.envelope_entity (id),
    email               varchar(255),
    firstname           varchar(255),
    gender              varchar(255),
    lang_key            varchar(255),
    lastname            varchar(255),
    title               varchar(255)
);
