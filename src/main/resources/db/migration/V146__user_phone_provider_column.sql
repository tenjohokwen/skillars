-- skillars-deferred-123 code review 2026-09-18 (Decision 1 follow-up). Adds the `provider` column that
-- PhoneNumber's `@Enumerated(EnumType.STRING) private Provider provider` maps to, on both main."user"
-- and its Envers audit table.
--
-- Why this was missing. PhoneNumber is @Embedded into Customer (and so into User) under an
-- @AttributeOverrides block that names phone, iso2Country and phoneType but NOT provider, so the field
-- falls through to its default column name `provider`. No migration has ever created it. The
-- application nevertheless worked in every environment because `spring.jpa.generate-ddl: true` silently
-- left Hibernate running with effective hbm2ddl.auto=update, which created the column at boot — the
-- same untracked-DDL mechanism V145's header describes. Removing that property (Decision 1) turned this
-- long-standing, invisible schema gap into a hard failure: every `SELECT ... u1_0.provider ... FROM
-- main."user"` — i.e. every login — failed with `column u1_0.provider does not exist` against a
-- Flyway-only database. Caught by re-running ReinstateIT after the property removal, not by inspection.
--
-- This migration does not change behaviour anywhere the app has already booted; ADD COLUMN IF NOT
-- EXISTS is a no-op wherever Hibernate had already created the column. It restores Flyway as the source
-- of truth for it, which is the project's own rule and the entire point of the Decision 1 change.
--
-- varchar(255), following V145's precedent for the identical situation: match the width Hibernate
-- already applied rather than narrowing an existing populated column for no functional benefit. The
-- field is @Enumerated(STRING), so Hibernate's own DDL for it was varchar(255) plus a CHECK constraint.
-- The CHECK is deliberately NOT reproduced here — see V145's closing note on the same divergence, which
-- is tracked as one item rather than being partially addressed in two migrations.
--
-- main.user_aud gets the column too: User is @Audited and the embedded field carries no @NotAudited, so
-- Envers maps `provider` into the audit table exactly as it does phone/phone_type/iso2_country.
--
-- Note on current data: `provider` (the telco enum MTN/ORANGE/NEXTTEL) is never read or written
-- anywhere in src/main — every call site of `setProvider`/`getProvider` in the codebase belongs to an
-- unrelated video or mail provider. The column is therefore NULL everywhere today, so there is nothing
-- to backfill and no batching concern.
SET lock_timeout = '5s';

ALTER TABLE main."user"
    ADD COLUMN IF NOT EXISTS provider character varying(255);

ALTER TABLE main.user_aud
    ADD COLUMN IF NOT EXISTS provider character varying(255);
