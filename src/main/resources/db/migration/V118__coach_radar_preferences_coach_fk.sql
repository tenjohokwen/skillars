-- Story skillars-deferred-86 AC4: development.coach_radar_preferences (V51) was created with an FK
-- on player_id (added by skillars-deferred-84 AC1 / V117) but NOT on coach_id. V117's own scope note
-- explicitly left coach_id "for a future pass on its own merits" — this is that pass. ON DELETE
-- CASCADE since a coach_radar_preferences row is pure per-coach chart-view preference state with no
-- independent value once the coach profile is gone (same rationale V117 used for the player_id FK).
--
-- Not written for an online-safe deploy (ADD CONSTRAINT ... NOT VALID + separate VALIDATE, CREATE
-- INDEX CONCURRENTLY) — deliberately, matching V117 / V113 / the codebase-wide convention at this
-- table's size. The skillars-deferred-84 ledger bullet tracking that convention stays open by design.

-- Defensive: delete any rows already orphaned in a deployed environment before adding the FK, so this
-- migration cannot fail on unexpected existing data (mirrors V117 / V113 / V109).
DELETE FROM development.coach_radar_preferences crp
WHERE NOT EXISTS (SELECT 1 FROM marketplace.coach_profiles c WHERE c.id = crp.coach_id);

ALTER TABLE development.coach_radar_preferences
    ADD CONSTRAINT fk_crp_coach_id FOREIGN KEY (coach_id) REFERENCES marketplace.coach_profiles(id) ON DELETE CASCADE;

-- coach_id IS the leading column of coach_radar_preferences' PK (coach_id, player_id), so it already
-- has a usable index for the cascade / FK check — no separate CREATE INDEX needed (contrast V117,
-- which had to add ix_crp_player_id because player_id is the trailing PK column).
