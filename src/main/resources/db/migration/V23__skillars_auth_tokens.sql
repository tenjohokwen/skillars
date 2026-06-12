-- V23: Skillars Auth Tokens
-- refresh_tokens: refresh token rotation (HttpOnly cookie)
-- login_attempts: DB-backed rate limiting (multi-node safe)

CREATE TABLE IF NOT EXISTS main.refresh_tokens (
    id             BIGINT       PRIMARY KEY,
    version        BIGINT       NOT NULL DEFAULT 0,
    user_id        BIGINT       NOT NULL REFERENCES main."user"(id) ON DELETE CASCADE,
    token_hash     VARCHAR(64)  NOT NULL UNIQUE,
    expires_at     TIMESTAMPTZ  NOT NULL,
    used           BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    rotated_at     TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_rt_user_id    ON main.refresh_tokens(user_id);
CREATE INDEX IF NOT EXISTS idx_rt_token_hash ON main.refresh_tokens(token_hash);

CREATE TABLE IF NOT EXISTS main.login_attempts (
    id             BIGINT       PRIMARY KEY,
    version        BIGINT       NOT NULL DEFAULT 0,
    identifier     VARCHAR(255) NOT NULL,
    attempted_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_la_identifier ON main.login_attempts(identifier, attempted_at);

INSERT INTO main.platform_config (id, key, value, value_type, description, updated_at)
VALUES
    (110, 'security.login.max-attempts',        '5',  'LONG', 'Max failed login attempts before lockout', NOW()),
    (111, 'security.login.lock-window-minutes', '15', 'LONG', 'Lock window in minutes for failed logins',  NOW())
ON CONFLICT (key) DO NOTHING;
