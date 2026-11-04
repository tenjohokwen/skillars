-- skillars-deferred-168: ALTER COLUMN ... DROP NOT NULL / DROP DEFAULT widen or relax a column; they remove
-- nothing, so they must not trip DROP_WITHOUT_IF_EXISTS or DROP_WITHOUT_PRIOR_RELEASE_PREP (the lint used to read
-- "DROP NOT" as dropping a column named NOT).
SET LOCAL lock_timeout = '5s';

ALTER TABLE main.widget ALTER COLUMN nickname DROP NOT NULL;
ALTER TABLE main.widget ALTER COLUMN nickname DROP DEFAULT;
