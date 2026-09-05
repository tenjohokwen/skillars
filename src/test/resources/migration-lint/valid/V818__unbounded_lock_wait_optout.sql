-- Fixture (skillars-deferred-92 code review): allow-unbounded-lock-wait had no fixture and no
-- assertion anywhere, though hasMarker compares a hand-written literal and both the doc and the PR
-- checklist tell authors to use it — a typo here would have shipped undetected.
-- migration-lint: allow-unbounded-lock-wait main.widget_type is a 6-row static lookup table; an
-- unbounded wait here is bounded in practice by how briefly anything else ever locks it.
ALTER TABLE main.widget_type ADD COLUMN note text;
