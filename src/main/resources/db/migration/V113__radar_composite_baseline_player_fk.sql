-- Story Deferred-77 AC9: development.player_radar_composites and development.player_radar_baselines
-- (V50/V51) were both created without an FK to main.player_profiles(id), leaving orphaned composite/
-- baseline rows after a player deletion. ON DELETE CASCADE since both tables are pure per-player
-- derived state with no independent value once the player is gone.

-- Defensive: delete any rows already orphaned in a deployed environment before adding the FK, so this
-- migration cannot fail on unexpected existing data (mirrors V109's established pattern).
DELETE FROM development.player_radar_composites c
WHERE NOT EXISTS (SELECT 1 FROM main.player_profiles p WHERE p.id = c.player_id);

DELETE FROM development.player_radar_baselines b
WHERE NOT EXISTS (SELECT 1 FROM main.player_profiles p WHERE p.id = b.player_id);

ALTER TABLE development.player_radar_composites
    ADD CONSTRAINT fk_prc_player_id FOREIGN KEY (player_id) REFERENCES main.player_profiles(id) ON DELETE CASCADE;

ALTER TABLE development.player_radar_baselines
    ADD CONSTRAINT fk_prb_player_id FOREIGN KEY (player_id) REFERENCES main.player_profiles(id) ON DELETE CASCADE;
