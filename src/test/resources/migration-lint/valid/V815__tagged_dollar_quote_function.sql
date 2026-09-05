-- Fixture (skillars-deferred-92 code review): a TAGGED dollar-quote body ($fn$...$fn$, not just the
-- plain $$ form) must not be torn apart at any ';' inside it, AND its body text must not be exposed
-- to the DDL/DML rules — a DROP inside a function definition is not a statement this migration
-- itself executes. Before the fix, the untagged scanner mis-split this at the first ';' inside the
-- body, and even a correctly-scoped single statement would still have surfaced the embedded DROP to
-- every pattern match. Neither must happen here.
SET lock_timeout = '5s';
CREATE OR REPLACE FUNCTION main.widget_debug_reset() RETURNS void AS $fn$
BEGIN
    DROP TABLE main.widget_debug_temp;
END;
$fn$ LANGUAGE plpgsql;
