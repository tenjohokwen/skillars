-- feat/security-s27-age-verification (S-27): one row per Stripe Identity VerificationSession a self-registered player
-- starts to prove they are 18+ (triggered lazily at their first chat or first booking, never at signup).
--
-- Stores only the outcome: status, the Stripe session id, when it verified and the age tier the VERIFIED date of birth
-- fell in. Never document images, document numbers or the verified date of birth itself (Stripe holds / redacts those;
-- on a mismatch the verified DOB overwrites main."user".date_of_birth, the one place a DOB already lives).
-- "Is verified" = a VERIFIED row exists for the user; the per-day attempt cap counts rows by created_at.
--
-- GDPR: GdprErasureService deletes the user's rows (user_id) in the erasure transaction.
--
-- Migration conventions (docs/deployment/migration-conventions.md): brand-new empty table, constraints and the FK
-- inline, nothing to validate (V148/V175/V186/V200/V217 precedent); the indexes are built on an empty table. No
-- platform_config seed rows: identity.ageVerification.enabled is read with a code default of false and
-- identity.ageVerification.maxAttemptsPerDay (3) is a HAS_CODE_DEFAULT ConfigBounds key.
SET LOCAL lock_timeout = '5s';

CREATE TABLE IF NOT EXISTS main.age_verifications (
    id                bigint       NOT NULL PRIMARY KEY,
    user_id           bigint       NOT NULL REFERENCES main."user" (id) ON DELETE CASCADE,
    stripe_session_id varchar(255) NOT NULL,
    status            varchar(16)  NOT NULL,
    verified_tier     varchar(15),
    verified_at       timestamptz,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT chk_agever_status CHECK (status IN
        ('CREATED', 'PROCESSING', 'REQUIRES_INPUT', 'VERIFIED', 'UNDERAGE', 'CANCELED')),
    CONSTRAINT uq_agever_stripe_session UNIQUE (stripe_session_id)
);

-- migration-lint: allow-blocking-index new empty table, nothing to scan.
CREATE INDEX IF NOT EXISTS idx_agever_user_created ON main.age_verifications USING btree (user_id, created_at);
