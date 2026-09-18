-- skillars-deferred-123 AC5. User.skillarsRole/verificationStatus are @Audited (no @NotAudited) but
-- main.user_aud (V138__baseline_schema.sql) declared no matching columns.
--
-- Investigation finding (skillars-deferred-123 AC5, empirical — see UserEnversAuditGapIT and this
-- story's completion notes for the full trace): this does NOT crash and does NOT silently drop the
-- fields. Hibernate/Envers issued its own `alter table ... add column ... check (...)` DDL for both
-- columns at application boot -- confirmed by raising org.hibernate.SQL to DEBUG and observing the
-- literal ALTER TABLE statements, and separately confirming main.flyway_schema_history contains no
-- migration that adds them. Both columns already round-trip real values correctly on every insert in
-- every environment that has booted the app at least once since these fields were added to User.java.
--
-- CAUSE, corrected by the skillars-deferred-123 code review (2026-09-18). The original text of this
-- header attributed the boot-time DDL to Hibernate acting "independent of `hibernate.ddl-auto: none`".
-- That is not a real Hibernate behaviour and was the wrong diagnosis. The actual cause was
-- `spring.jpa.generate-ddl: true` sitting one line above `ddl-auto: none` in application.yaml, which
-- made the effective setting `hibernate.hbm2ddl.auto=update` in every profile. Verified by decompiling
-- the resolved artifacts: HibernateProperties.getAdditionalProperties (spring-boot-autoconfigure
-- 3.5.16) *removes* the `hibernate.hbm2ddl.auto` key when ddl-auto is `none` rather than setting it;
-- HibernateJpaVendorAdapter.getJpaPropertyMap (spring-orm 6.2.19) then puts the key with value
-- "update" because isGenerateDdl() is true; AbstractEntityManagerFactoryBean merges vendor properties
-- only when the key is absent, so the vendor's "update" won. That `generate-ddl` line has now been
-- REMOVED -- see the comment left in its place in application.yaml.
--
-- This migration is therefore no longer a no-op-in-practice tidy-up: with auto-DDL switched off, it is
-- the only thing that creates these two columns in any database built from Flyway alone. ADD COLUMN IF
-- NOT EXISTS keeps it a no-op wherever Hibernate had already added the column before the fix.
--
-- varchar(255), not the main."user" table's own varchar(20) for these same two columns: matching
-- Hibernate's own already-applied column width (an ALTER COLUMN to narrow an existing populated
-- audit column carries real risk for no functional benefit -- these are enum-backed values, well
-- under 20 characters in practice regardless of declared width) rather than fighting what every
-- environment already has.
--
-- Audit history is intentionally kept (no @NotAudited added) -- a skillars_role/verification_status
-- change is plausibly meaningful history, unlike V143's cleanup_* operational-marker columns.
--
-- Broader consequence of the corrected cause above: for as long as `generate-ddl: true` was set,
-- Hibernate was generating `check (col in (...))` constraints for EVERY @Enumerated(STRING) column in
-- the application, in every environment -- not just these two. Now that auto-DDL is off, databases
-- already built under the old setting carry those CHECK constraints while a database built from Flyway
-- alone does not. That divergence is closed below, corrected 2026-09-18 code review (Patch): the
-- constraints are named to match Postgres's own default naming for an unnamed inline CHECK
-- (`<table>_<column>_check`), which is exactly what Hibernate's own DDL produced, so an
-- already-Hibernate-patched database recognises them as already present and this is a genuine no-op
-- there; a Flyway-only database gets them for the first time. NOT VALID (rather than a plain ADD
-- CONSTRAINT, which MigrationLint's VALIDATING_CONSTRAINT rule flags on an already-populated table
-- regardless) skips the full-table scan against existing data -- safe here because every existing row
-- was written through the entity's own enum setters, never bulk SQL, so no row can violate it; a
-- later migration can VALIDATE CONSTRAINT once that assumption is worth spending the scan on.
SET lock_timeout = '5s';

ALTER TABLE main.user_aud
    ADD COLUMN IF NOT EXISTS skillars_role character varying(255),
    ADD COLUMN IF NOT EXISTS verification_status character varying(255);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'user_aud_skillars_role_check' AND conrelid = 'main.user_aud'::regclass
    ) THEN
        ALTER TABLE main.user_aud ADD CONSTRAINT user_aud_skillars_role_check
            CHECK (skillars_role IN ('COACH', 'PARENT', 'PLAYER', 'ADMIN')) NOT VALID;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'user_aud_verification_status_check' AND conrelid = 'main.user_aud'::regclass
    ) THEN
        ALTER TABLE main.user_aud ADD CONSTRAINT user_aud_verification_status_check
            CHECK (verification_status IN ('UNVERIFIED', 'EMAIL_VERIFIED', 'BASIC_VERIFIED', 'SUSPENDED')) NOT VALID;
    END IF;
END $$;
