-- V95: UAT.5 AC3 — a self-registered adult PLAYER has no real parent, so a conversation about their
-- own booking cannot carry a real parent_id. No CHECK constraint is needed here (unlike
-- player_profiles.chk_pp_owner): conversations has no second "owner" column to XOR against;
-- parent_id IS NULL simply means "no parent to notify," which is the ADULT-tier reality this AC enables.
ALTER TABLE messaging.conversations ALTER COLUMN parent_id DROP NOT NULL;
