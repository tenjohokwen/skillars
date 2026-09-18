-- skillars-deferred-123 code review 2026-09-18 (Decision 1 follow-up, third of three). Creates the two
-- tables that exist in the entity model but in no migration: main.audit_log and
-- main.user_authority_aud.
--
-- Same root cause as V146 and V147: `spring.jpa.generate-ddl: true` left Hibernate running with
-- effective hbm2ddl.auto=update, so it created these tables at boot in every environment, outside
-- Flyway's tracking. Removing that property (Decision 1) leaves a Flyway-only database without them.
--
-- How these two were found, and why they were not caught by the first sweep. The initial diagnostic
-- after the property removal grepped Hibernate's boot DDL for `add column` only, which found V146's
-- and V147's gaps but is blind to a missing TABLE. main.audit_log surfaced on the next integration run
-- as a logged (non-fatal) `relation "main.audit_log" does not exist` on the auditing write path — the
-- request itself still succeeded, so the tests stayed green and this would have shipped silently. The
-- sweep was then re-run capturing `create table`, `add column` and `create sequence`, which is what
-- produced exactly these two and nothing else. The DDL below is Hibernate's own, read off that run.
--
-- main.audit_log: a first-class table, not an audit-framework one. AuditLog extends
-- AbstractAuditingEntity, hence the created_by/created_date/last_modified_*/request_id/session_id/
-- status block, and its `id` comes from the application's own generator (no sequence is created by
-- Hibernate and none is needed). No indexes are declared: Hibernate created none either, so this keeps
-- parity with what every environment already has rather than inventing an index nobody has been
-- running with. Add one when query evidence calls for it.
--
-- main.user_authority_aud: the Envers audit table for User's @ElementCollection-style authority join.
-- Shape follows the existing *_aud convention in V138 (composite PK, `rev` FK to main.revinfo) — see
-- main.authority_aud for the reference shape.
--
-- Constraints are declared INLINE in CREATE TABLE rather than via later ALTER ... ADD CONSTRAINT. That
-- is deliberate: MigrationLint's VALIDATING_CONSTRAINT rule flags a bare `ADD CONSTRAINT ... FOREIGN
-- KEY|CHECK` (it must be added NOT VALID then validated separately), and INLINE_FK_ADD_COLUMN flags an
-- `ADD COLUMN ... REFERENCES`. Neither applies to a constraint defined as part of a CREATE TABLE, and
-- on a brand-new empty table there is nothing to validate, so inline is both lint-clean and correct.
--
-- The status CHECK constraint IS reproduced here, unlike in V145/V146/V147. That is not an
-- inconsistency: those three add a COLUMN to a table that already holds rows, where attaching a CHECK
-- risks failing against existing data and is tracked as a separate reconciliation item. These two
-- tables are created empty, so matching Hibernate's own shape exactly is the choice that minimises
-- divergence rather than adding to it.
--
-- CREATE TABLE IF NOT EXISTS keeps this a no-op wherever Hibernate already created the table.
SET lock_timeout = '5s';

CREATE TABLE IF NOT EXISTS main.audit_log (
    id bigint NOT NULL,
    created_by character varying(50),
    created_date timestamp with time zone,
    last_modified_by character varying(50),
    last_modified_date timestamp with time zone,
    request_id character varying(255),
    session_id text,
    status character varying(255) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE', 'DELETED')),
    browser_cookie text,
    client_id text,
    event_timestamp timestamp with time zone NOT NULL,
    ip_address text,
    is_authenticated boolean NOT NULL,
    log_id text,
    login text,
    msg text NOT NULL,
    relevant_properties jsonb,
    url text NOT NULL,
    user_agent text,
    CONSTRAINT audit_log_pkey PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS main.user_authority_aud (
    rev integer NOT NULL REFERENCES main.revinfo(rev),
    user_id bigint NOT NULL,
    authority_id bigint NOT NULL,
    revtype smallint,
    CONSTRAINT user_authority_aud_pkey PRIMARY KEY (rev, user_id, authority_id)
);
