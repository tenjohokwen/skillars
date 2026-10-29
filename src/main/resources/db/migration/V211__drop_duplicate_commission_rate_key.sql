-- fix/prod-readiness-gaps: one commission key.
--
-- V139 seeded the platform commission twice: platform.commission.rate (read by payouts, booking
-- payments and session packs) and platform.commission_rate (read only by ProjectedRevenueService).
-- An admin who changed one in the config editor left the coach's projected-revenue widget on the
-- other rate. ProjectedRevenueService now reads platform.commission.rate too, so the duplicate row
-- goes. Data-only: one DELETE on a tiny table, nothing else references the key.
SET LOCAL lock_timeout = '5s';

DELETE FROM main.platform_config WHERE key = 'platform.commission_rate';
