-- skillars-deferred-170 AC1: drop the profile builder's former pack table, marketplace.session_packs.
--
-- skillars-deferred-168 (PR #436) removed its last reader and writer (SessionPack, SessionPackRepository,
-- CoachProfileService.saveStep3 / getOwnProfile / getPublicProfile): a coach's one pack is now their active
-- payment.session_pack_tiers row, reached through CoachSessionPackPort. V226, in that same release, copied
-- each coach's purchasable builder pack into payment.session_pack_tiers. V226 is the marker migration for that
-- release boundary.
--
-- migration-conventions.md rule 2 would hold this drop for a release after V226. Owner decision (2026-11-04,
-- skillars-deferred-170, D1), on the same ground as V142 (skillars-deferred-117, 2026-09-16): the rule
-- protects an old-release pod still reading the table while a new release drops it, and no production
-- deploy of this application has ever happened (docs/deployment/migration-rebaseline.md), so there is no
-- old release to protect. If production has gone live by the time this ships, this migration must wait.
--
-- The table's primary key, its uq_session_pack unique constraint, its CHECK and its FK to
-- marketplace.coach_profiles (V138__baseline_schema.sql) go with it; the id is a uuid default, so there is no
-- sequence to drop. BuilderPackTierBackfillIT pinned V226's behaviour against this table until now and is
-- deleted with it.
SET LOCAL lock_timeout = '5s';

-- migration-lint: drop-prepared-in: V226
DROP TABLE IF EXISTS marketplace.session_packs;
