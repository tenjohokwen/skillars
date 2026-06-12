-- Supports the 30-second grace window in AuthService.refresh():
-- when a used token is presented within the grace window, rotated_at tells us
-- exactly when it was rotated so we can find the successor and avoid
-- false-positive theft revocation in multi-tab / concurrent refresh scenarios.
ALTER TABLE main.refresh_tokens
    ADD COLUMN IF NOT EXISTS rotated_at TIMESTAMPTZ NULL;
