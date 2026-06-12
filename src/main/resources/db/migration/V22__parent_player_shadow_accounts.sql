-- V22: Parent Player Shadow Accounts
-- Creates player_profiles and parent_player_links tables
-- Seeds age policy defaults into platform_config

CREATE TABLE IF NOT EXISTS main.player_profiles (
    id                          BIGINT       PRIMARY KEY,
    name                        VARCHAR(100) NOT NULL,
    date_of_birth               DATE         NOT NULL,
    position                    VARCHAR(30)  NOT NULL,
    age_tier                    VARCHAR(15)  NOT NULL,
    parent_id                   BIGINT       NOT NULL REFERENCES main."user"(id) ON DELETE CASCADE,
    independent_account_allowed BOOLEAN      NOT NULL DEFAULT TRUE,
    consent_accepted_at         TIMESTAMPTZ,
    consent_policy_version      VARCHAR(10),
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by                  VARCHAR(50)  NOT NULL DEFAULT 'system',
    last_modified_date          TIMESTAMPTZ,
    last_modified_by            VARCHAR(50)
);

CREATE INDEX IF NOT EXISTS idx_pp_parent_id ON main.player_profiles(parent_id);

CREATE TABLE IF NOT EXISTS main.parent_player_links (
    id                     BIGINT      PRIMARY KEY,
    parent_id              BIGINT      NOT NULL REFERENCES main."user"(id) ON DELETE CASCADE,
    player_id              BIGINT      NOT NULL REFERENCES main.player_profiles(id) ON DELETE CASCADE,
    consent_accepted_at    TIMESTAMPTZ NOT NULL,
    consent_policy_version VARCHAR(10) NOT NULL,
    CONSTRAINT uq_ppl_player_id UNIQUE (player_id)
);

CREATE INDEX IF NOT EXISTS idx_ppl_parent_id ON main.parent_player_links(parent_id);
CREATE INDEX IF NOT EXISTS idx_ppl_player_id  ON main.parent_player_links(player_id);

-- Seed age policy config defaults (IDs 100-102; safe above V20 max of 37 and V21's 100/101 for authorities)
-- Note: V21 uses IDs 100 and 101 for authority rows — platform_config and authority are different tables, no conflict
INSERT INTO main.platform_config (id, key, value, value_type, description, updated_at)
VALUES
    (100, 'security.age-policy.u10-max-age',       '9',  'LONG', 'Maximum age (inclusive) for U10 tier',    NOW()),
    (101, 'security.age-policy.young-teen-max-age', '12', 'LONG', 'Maximum age (inclusive) for 10-12 tier',  NOW()),
    (102, 'security.age-policy.teen-max-age',       '17', 'LONG', 'Maximum age (inclusive) for 13-17 tier',  NOW())
ON CONFLICT (key) DO NOTHING;
