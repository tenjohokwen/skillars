-- Story Deferred-84 AC1: development.coach_radar_preferences (V51) was created without an FK on
-- player_id to main.player_profiles(id), leaving orphaned per-coach chart-view preference rows after
-- a player deletion. skillars-deferred-77 AC9 (V113) added the equivalent ON DELETE CASCADE FK to the
-- sibling player_radar_composites / player_radar_baselines tables but explicitly left this one out of
-- scope. ON DELETE CASCADE since a coach_radar_preferences row is pure per-player derived view state
-- with no independent value once the player is gone.
--
-- Scope: only player_id. coach_radar_preferences.coach_id also has no FK to marketplace.coach_profiles(id),
-- but that gap was never raised by any code review and is left for a future pass on its own merits.

-- Defensive: delete any rows already orphaned in a deployed environment before adding the FK, so this
-- migration cannot fail on unexpected existing data (mirrors V113's / V109's established pattern).
DELETE FROM development.coach_radar_preferences crp
WHERE NOT EXISTS (SELECT 1 FROM main.player_profiles p WHERE p.id = crp.player_id);

ALTER TABLE development.coach_radar_preferences
    ADD CONSTRAINT fk_crp_player_id FOREIGN KEY (player_id) REFERENCES main.player_profiles(id) ON DELETE CASCADE;

-- coach_radar_preferences's PK is (coach_id, player_id), so player_id is not the leading column and
-- has no standalone index — unlike player_radar_composites / player_radar_baselines, both PK-led by
-- player_id, whose cascades and FK checks are index-covered for free. Without this index every
-- DELETE FROM main.player_profiles and every future FK-integrity check sequentially scans the whole
-- coach_radar_preferences table.
CREATE INDEX ix_crp_player_id ON development.coach_radar_preferences (player_id);
