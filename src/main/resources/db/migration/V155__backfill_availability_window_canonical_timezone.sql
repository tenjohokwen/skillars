-- skillars-deferred-140 code review (D1): backfill coach_availability_windows.canonical_timezone
-- from the owning coach profile's canonical_timezone.
--
-- Why: deferred-140 AC1 made the coach profile's timezone authoritative for availability. AC1.1
-- stamps it on every Step-4 write and AC1.2 re-stamps every window when a coach relocates, but AC1.3
-- deliberately shipped no backfill, so rows written BEFORE this story can still carry a divergent
-- value. Per-window divergence was a deliberate, UI-exposed feature under deferred-63/-64
-- (ProfileBuilderStep4.vue shipped an editable TimezoneSelect until deferred-140 removed it), and
-- pre-story saveStep1 changed the profile zone without touching windows — so divergent rows are
-- expected, not hypothetical.
--
-- The code-review fix moves every computational reader onto the profile zone
-- (BookingService.isSlotWithinAvailabilityWindow, AvailabilityService.hasBookingConflict), which
-- makes behaviour correct regardless of what this column holds. This migration is the data-hygiene
-- half: it makes CoachAvailabilityWindow.canonicalTimezone's "always equal to the profile zone"
-- javadoc true rather than aspirational, and is the precondition for dropping the column in a later
-- release. computeAvailabilitySignature still reads it as a cache-invalidation key, so leaving stale
-- values in place would keep producing signatures that disagree with what the calendar renders.
--
-- Migration conventions (docs/deployment/migration-conventions.md):
--   * Single-transaction backfill — coach_availability_windows holds a handful of rows per coach
--     (one per weekly window), so this is far below the batching threshold of rule 6. No
--     executeInTransaction=false sidecar is needed or wanted.
--   * The WHERE clause is a real, non-tautological top-level predicate (it compares the two columns
--     and skips rows already in agreement), so UNBATCHED_DML does not apply.
--   * Additive/idempotent: no schema change, re-runnable, and a no-op on a already-consistent
--     database.

SET LOCAL lock_timeout = '3s';

UPDATE marketplace.coach_availability_windows w
SET canonical_timezone = p.canonical_timezone
FROM marketplace.coach_profiles p
WHERE w.coach_id = p.id
  AND w.canonical_timezone <> p.canonical_timezone;
