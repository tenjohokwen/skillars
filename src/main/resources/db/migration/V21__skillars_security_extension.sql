-- V21: Skillars Security Extension
-- Adds skillars_role and verification_status to user table
-- Creates email_verification_tokens and phone_otp_tokens tables
-- Seeds ROLE_COACH and ROLE_PARENT into authority table

ALTER TABLE main."user"
    ADD COLUMN IF NOT EXISTS skillars_role       VARCHAR(20),
    ADD COLUMN IF NOT EXISTS verification_status VARCHAR(20) NOT NULL DEFAULT 'UNVERIFIED';

CREATE TABLE IF NOT EXISTS main.email_verification_tokens (
    id          BIGINT       PRIMARY KEY,
    version     BIGINT       NOT NULL DEFAULT 0,
    user_id     BIGINT       NOT NULL REFERENCES main."user"(id) ON DELETE CASCADE,
    token       UUID         NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ  NOT NULL,
    used        BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_evt_token  ON main.email_verification_tokens(token);
CREATE INDEX IF NOT EXISTS idx_evt_userid ON main.email_verification_tokens(user_id);

CREATE TABLE IF NOT EXISTS main.phone_otp_tokens (
    id          BIGINT       PRIMARY KEY,
    version     BIGINT       NOT NULL DEFAULT 0,
    user_id     BIGINT       NOT NULL REFERENCES main."user"(id) ON DELETE CASCADE,
    otp_hash    VARCHAR(64)  NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    used        BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_pot_userid ON main.phone_otp_tokens(user_id);

INSERT INTO main.authority (id, name, status, created_by, created_date)
VALUES
    (100, 'ROLE_COACH',  'ACTIVE', 'system', NOW()),
    (101, 'ROLE_PARENT', 'ACTIVE', 'system', NOW())
ON CONFLICT (name) DO NOTHING;
