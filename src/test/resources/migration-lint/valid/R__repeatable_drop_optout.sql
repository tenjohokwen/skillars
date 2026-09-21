-- Fixture (skillars-deferred-91 code review): an R__ repeatable that legitimately needs an
-- unconditional DROP. Before the review the REPEATABLE_HAZARD DROP branch honoured no opt-out at
-- all, despite lintRepeatable's javadoc promising "the allow-* opt-outs still apply", so a file like
-- this had no way past the lint. Uses DROP INDEX, which DROP_NO_IF_EXISTS does match, so the
-- opt-out is genuinely exercised rather than passing because no rule applied.
--
-- skillars-deferred-92 AC11.2: each marker now sits directly above the statement it covers. Before
-- statement scoping, the single allow-blocking-index below covered BOTH statements from anywhere in
-- the file; it now has to be repeated where it is actually needed, which is the point.
--
-- skillars-deferred-92 code review: repeatables are now ALSO linted by MISSING_LOCK_TIMEOUT, since
-- a rebuilt index is exactly as much a lock-taking-DDL hazard here as in a versioned migration.
--
-- /bmad-code-review fix (2026-09-21, skillars-deferred-125 AC4): repeatables are now ALSO linted by
-- SESSION_SCOPED_LOCK_TIMEOUT (a plain SET here would be exactly as much a cross-migration leak
-- hazard as in a versioned migration) — SET LOCAL still satisfies MISSING_LOCK_TIMEOUT's regex, which
-- accepts either spelling, and is the preferred form going forward regardless.
SET LOCAL lock_timeout = '5s';

-- migration-lint: allow-unconditional-drop the index is recreated immediately below; a guarded drop
-- would leave a stale definition if the index expression changed.
DROP INDEX main.idx_widget_owner;

-- migration-lint: allow-blocking-index recreated in the same repeatable; see above.
CREATE INDEX idx_widget_owner ON main.widget (owner_id);
