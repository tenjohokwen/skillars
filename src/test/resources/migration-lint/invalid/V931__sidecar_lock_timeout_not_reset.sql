-- /bmad-code-review fix (2026-09-21, owner decision — option 1 of 3, see optionsAndRecommendations.md):
-- a sidecar (executeInTransaction=false, see the .sql.conf next to this file) migration's plain SET
-- lock_timeout has no later RESET lock_timeout — with no COMMIT to bound it automatically, this
-- setting is GUARANTEED to carry forward into every later migration in the same Flyway deploy. Must
-- trigger SIDECAR_LOCK_TIMEOUT_NOT_RESET. The ALTER TABLE below is deliberately still bounded for
-- MISSING_LOCK_TIMEOUT's own purposes (the plain SET is a genuine, if leaky, bound) — this fixture
-- isolates the new rule rather than conflating it with the pre-existing one.
SET lock_timeout = '5s';

ALTER TABLE main.widget ADD COLUMN sidecar_no_reset_probe VARCHAR(10);
