-- skillars-deferred-127 code review (2026-09-21, /bmad-code-review, owner decision taken live via
-- AskUserQuestion): AC1's shared player_profiles pessimistic lock serializes GdprErasureService.erase
-- against RadarCompositeCalculationService.recalculateComposite, but it does NOT close the full
-- resurrection race on its own -- the data recalculateComposite reads comes from
-- development.radar_assessment_entries, whose writer (RadarAssessmentService.submitAssessment) takes
-- no player_profiles lock and has no FK to that table. A genuinely reachable interleaving: a coach's
-- submitAssessment transaction inserts assessment rows for player P, uncommitted, while erase() is
-- mid-flight; erase's deleteAllByPlayerId(P) (under READ COMMITTED) cannot see those uncommitted
-- rows, so they survive; the coach's transaction then commits, its AFTER_COMMIT listener fires,
-- recalculateComposite blocks on the now-held lock, and once erase() releases it, the retry succeeds,
-- reads the surviving assessment rows, and re-creates composites/baselines for the already-erased
-- player. Both the derived composites AND the raw radar_assessment_entries (coach notes and scores
-- for a named minor) would survive an Article-17 erasure permanently.
--
-- Fix: a sticky "erased" tombstone on player_profiles itself (the one row both paths already lock),
-- set by deletePlayerDevelopmentData under its existing lock, and checked by recalculateComposite
-- immediately after it re-acquires/refreshes that same lock, before it reads any aggregates or
-- upserts anything. Chosen over taking a lock in submitAssessment because a single check inside
-- recalculateComposite also covers the RadarCompositeDlqProcessor retry path (every route to an
-- upsert goes through recalculateComposite), which a lock on the coach-facing write path alone would
-- not -- a DLQ-retried recalculation for an already-erased player must also see the tombstone and
-- skip, not just a live AFTER_COMMIT-triggered one.
--
-- Nullable, additive ADD COLUMN per the expand/contract standard (docs/deployment/
-- migration-conventions.md rule 1) -- no backfill needed, since a player_profiles row that predates
-- this migration was, by definition, never erased under this mechanism. timestamp with time zone,
-- matching this table's own existing nullable-timestamp convention (consent_accepted_at,
-- V138__baseline_schema.sql:904) rather than main."user"'s plain-timestamp convention (V143).
SET LOCAL lock_timeout = '5s';

ALTER TABLE main.player_profiles
    ADD COLUMN IF NOT EXISTS development_data_erased_at timestamp with time zone;
