-- Fixture (skillars-deferred-91 code review): an R__ repeatable that legitimately needs an
-- unconditional DROP. Before the review the REPEATABLE_HAZARD DROP branch honoured no opt-out at
-- all, despite lintRepeatable's javadoc promising "the allow-* opt-outs still apply", so a file like
-- this had no way past the lint. Uses DROP INDEX, which DROP_NO_IF_EXISTS does match, so the
-- opt-out is genuinely exercised rather than passing because no rule applied.
--
-- skillars-deferred-92 AC11.2: each marker now sits directly above the statement it covers. Before
-- statement scoping, the single allow-blocking-index below covered BOTH statements from anywhere in
-- the file; it now has to be repeated where it is actually needed, which is the point.
-- migration-lint: allow-unconditional-drop the index is recreated immediately below; a guarded drop
-- would leave a stale definition if the index expression changed.
DROP INDEX main.idx_widget_owner;

-- migration-lint: allow-blocking-index recreated in the same repeatable; see above.
CREATE INDEX idx_widget_owner ON main.widget (owner_id);
