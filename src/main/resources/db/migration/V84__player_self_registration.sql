-- V84: Player self-registration (adult players, 18+)
-- Seeds ROLE_PLAYER into authority (previously referenced by SkillarsRole/AuthoritiesConstants but never seeded)
-- Allows player_profiles to be owned by a User directly (self-registered adult) instead of only a parent

INSERT INTO main.authority (id, name, status, created_by, created_date)
VALUES
    (102, 'ROLE_PLAYER', 'ACTIVE', 'system', NOW())
ON CONFLICT (name) DO NOTHING;

ALTER TABLE main.player_profiles
    ALTER COLUMN parent_id DROP NOT NULL,
    ADD COLUMN IF NOT EXISTS user_id BIGINT REFERENCES main."user"(id) ON DELETE CASCADE;

ALTER TABLE main.player_profiles
    ADD CONSTRAINT chk_pp_owner CHECK (
        (parent_id IS NOT NULL AND user_id IS NULL) OR
        (parent_id IS NULL AND user_id IS NOT NULL)
    );

CREATE UNIQUE INDEX IF NOT EXISTS uq_pp_user_id ON main.player_profiles(user_id) WHERE user_id IS NOT NULL;
