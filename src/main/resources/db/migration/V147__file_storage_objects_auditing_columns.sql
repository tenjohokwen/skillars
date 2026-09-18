-- skillars-deferred-123 code review 2026-09-18 (Decision 1 follow-up, second of two). Adds the seven
-- AbstractAuditingEntity columns that main.file_storage_objects has never declared.
--
-- FileStorageObject extends AbstractAuditingEntity, which maps created_by, created_date,
-- last_modified_by, last_modified_date, request_id, session_id and status — but
-- V138__baseline_schema.sql's CREATE TABLE for this table declares none of them, and no later
-- migration adds them. As with V146's `provider` column, the application worked anyway only because
-- `spring.jpa.generate-ddl: true` left Hibernate running with effective hbm2ddl.auto=update, silently
-- issuing these ALTERs at every boot outside Flyway's tracking.
--
-- How the full set was established (not guessed, and not inferred from the single error that surfaced
-- first): after removing the property, the application was booted once against a Flyway-only
-- Testcontainers database with hbm2ddl.auto=update and org.hibernate.SQL at DEBUG, and every
-- `alter table ... add column` Hibernate emitted was captured. That is the exact, complete set of
-- columns the property had been masking. It listed V146's `provider` and the seven below — nothing
-- else, across every entity in the application.
--
-- Column shapes match what Hibernate itself was applying, following V145's precedent (match the width
-- already in place rather than narrowing a column that environments already have). `status` is
-- @Enumerated(STRING) and therefore varchar; its CHECK constraint is deliberately not reproduced here,
-- consistent with V145 and V146 — that divergence is tracked as one item rather than being partially
-- addressed across three migrations.
--
-- On `status NOT NULL DEFAULT 'INACTIVE'`: AbstractAuditingEntity initialises the field to
-- EntityStatus.INACTIVE, so every row ever inserted through JPA already carries that value unless a
-- call site overrode it, and exactly one does (StorageMigrationService sets ACTIVE explicitly). No
-- query in the codebase filters FileStorageObject by status, so the column is inert today. The DEFAULT
-- therefore reproduces existing behaviour exactly and also makes this a metadata-only, non-rewriting
-- ALTER on PostgreSQL 11+, safe on a populated table — which a bare NOT NULL without a default would
-- not have been. (Hibernate's own generated DDL omitted the default, so on any environment where this
-- table already held rows its ALTER would have failed and been swallowed; this migration is
-- strictly more correct than what it replaces.)
SET lock_timeout = '5s';

ALTER TABLE main.file_storage_objects
    ADD COLUMN IF NOT EXISTS created_by character varying(50),
    ADD COLUMN IF NOT EXISTS created_date timestamp with time zone,
    ADD COLUMN IF NOT EXISTS last_modified_by character varying(50),
    ADD COLUMN IF NOT EXISTS last_modified_date timestamp with time zone,
    ADD COLUMN IF NOT EXISTS request_id character varying(255),
    ADD COLUMN IF NOT EXISTS session_id text,
    ADD COLUMN IF NOT EXISTS status character varying(255) DEFAULT 'INACTIVE' NOT NULL;
