-- Deferred-63 AC6: one-time backfill of coach_availability_windows.canonical_timezone rows that
-- have drifted from their owning coach_profiles.canonical_timezone. Backfill-only, per this
-- story's own story-review scope narrowing — CoachProfileService.saveStep4 still accepts an
-- independently-writable per-window timezone from the request (ProfileBuilderStep4.vue ships a
-- real, coach-editable picker for it), so new drift can still occur after this migration runs.
-- Single UPDATE, no batching: availability windows are capped at 14 per coach by the request
-- DTO's own @Size(max = 14), so no table-scale concern applies here.
UPDATE marketplace.coach_availability_windows w
SET canonical_timezone = p.canonical_timezone
FROM marketplace.coach_profiles p
WHERE w.coach_id = p.id
  AND w.canonical_timezone != p.canonical_timezone;
