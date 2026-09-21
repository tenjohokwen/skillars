-- /bmad-code-review fix (2026-09-21, owner decision — option 1 of 3, see optionsAndRecommendations.md):
-- the counterpart to invalid/V931 — this sidecar (executeInTransaction=false, see the .sql.conf next
-- to this file) migration's plain SET lock_timeout IS followed by a RESET lock_timeout, the
-- hand-rolled equivalent of what SET LOCAL would do automatically at COMMIT in a transactional
-- migration. Must NOT trigger SIDECAR_LOCK_TIMEOUT_NOT_RESET.
SET lock_timeout = '5s';

ALTER TABLE main.widget ADD COLUMN sidecar_reset_probe VARCHAR(10);

RESET lock_timeout;
