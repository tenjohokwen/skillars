-- Validates the NOT VALID constraint added in V107 against every existing row, under a SHARE UPDATE
-- EXCLUSIVE lock that does not block concurrent reads/writes — a separate migration (separate
-- transaction) from V107, mirroring V101's and V106's own reasoning exactly.
ALTER TABLE marketplace.coach_pricing
    VALIDATE CONSTRAINT chk_coach_pricing_session_duration;
