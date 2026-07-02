-- Story deferred-3 review follow-up: correct the AC 2 partial index predicate on
-- booking.session_packs_purchased (introduced in V76).
--
-- The original predicate (`status NOT IN ('EXHAUSTED', 'EXPIRED')`) has two problems:
-- 1. It never matches the queries it was meant to serve. SessionPackPurchasedRepository's
--    expiry/warning queries all filter on `status = 'ACTIVE'` (findExpiredActivePacks,
--    findPacksNeedingWarning30d/7d), and Postgres's partial-index predicate proof cannot infer
--    that `status = 'ACTIVE'` satisfies `status NOT IN (...)`, so the planner was never able to
--    use this index for the scheduler queries it was added for.
-- 2. It is a blacklist. chk_spp_status (V30__booking_session_packs.sql) currently allows only
--    'ACTIVE', 'EXHAUSTED', 'EXPIRED', but if a future status is ever added to that constraint
--    without also updating this predicate, it would silently fall inside the "active" partial
--    index by default.
--
-- Rewriting as a whitelist on the literal value every current query actually filters on fixes
-- both: the index becomes usable, and any status added in the future is excluded by default
-- instead of included by default.
DROP INDEX IF EXISTS booking.idx_session_packs_purchased_coach_expires;

CREATE INDEX IF NOT EXISTS idx_session_packs_purchased_coach_expires
    ON booking.session_packs_purchased(coach_id, expires_at)
    WHERE status = 'ACTIVE';
