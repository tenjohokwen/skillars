-- Story skillars-deferred-86 AC1: idempotency marker for the weekly SLU snapshot write path.
--
-- SnapshotBatchWriter.writeAll issues additive upserts
-- (total_slu = player_slu_weekly_snapshot.total_slu + EXCLUDED.total_slu). skillars-deferred-85 AC1
-- widened SnapshotPersistenceRetrier's retryFor to include TransactionSystemException, which is
-- raised on a commit-phase system error — including the case where PostgreSQL committed server-side
-- but the client lost the ack. A whole-method retry of writeAll after such an ambiguous commit
-- re-adds every delta and silently inflates a player's weekly totals, with no dedup key.
--
-- This table is that dedup key: one row per (source session, weekly bucket). SnapshotBatchWriter
-- now applies each additive delta only when the marker row for that (session_id, player_id,
-- skill_code, iso_year, iso_week) is newly inserted (single CTE statement, both writes inside
-- writeAll's @Transactional). A retry / duplicate replay of the same session's batch finds the
-- marker already present -> ON CONFLICT DO NOTHING -> the delta is a no-op. Two DISTINCT sessions
-- for the same weekly bucket still each insert their own marker and both deltas apply, so additive
-- weekly aggregation across sessions is unchanged.
--
-- FK asymmetry note (deliberate — do NOT "tidy up"): this marker table carries BOTH a
-- skill_code -> skill_definitions(code) FK and a player_id -> main.player_profiles(id) ON DELETE
-- CASCADE FK, whereas player_slu_weekly_snapshot itself (V48) has only the skill_code FK and no
-- player_id FK. The stronger set here is intentional defense-in-depth on a new table, matching the
-- V113 / V117 late-FK pattern. The explicit deleteAllByPlayerId call wired into GdprErasureService
-- is the real erasure path; the ON DELETE CASCADE FK is the backstop.

CREATE TABLE development.player_slu_weekly_snapshot_applied (
    session_id  UUID         NOT NULL,
    player_id   BIGINT       NOT NULL,
    skill_code  VARCHAR(10)  NOT NULL REFERENCES development.skill_definitions(code),
    iso_year    SMALLINT     NOT NULL,
    iso_week    SMALLINT     NOT NULL,
    applied_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (session_id, player_id, skill_code, iso_year, iso_week)
);

-- Defensive: delete any rows already orphaned before adding the FK, so this migration cannot fail on
-- unexpected existing data (mirrors V117 / V113 / V109). The table is new here, so this is a no-op
-- today — kept identical to the established pattern for the next reader.
DELETE FROM development.player_slu_weekly_snapshot_applied a
WHERE NOT EXISTS (SELECT 1 FROM main.player_profiles p WHERE p.id = a.player_id);

ALTER TABLE development.player_slu_weekly_snapshot_applied
    ADD CONSTRAINT fk_pswsa_player_id FOREIGN KEY (player_id) REFERENCES main.player_profiles(id) ON DELETE CASCADE;

-- player_id is not the leading PK column, so without a standalone index every
-- DELETE FROM main.player_profiles cascade and every FK-integrity check sequentially scans the whole
-- table. Same reasoning V117 spells out for its own ix_crp_player_id.
CREATE INDEX ix_pswsa_player_id ON development.player_slu_weekly_snapshot_applied (player_id);
