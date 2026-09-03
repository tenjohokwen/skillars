-- Fixture (AC7 blind spot 1): a low-versioned script that is "new in this commit". A backport past
-- the V121 grandfather baseline is how a DROP evades every content rule. Header present so only the
-- BACKPORT_BELOW_BASELINE rule is under test here.
DROP TABLE main.smuggled_legacy_table;
