-- skillars-deferred-172 (RS03, owner decision R5): one account per address, whatever its casing.
--
-- Sign-in lowercases the typed id and looks up login by exact match (LoadUserByUserNameService, AuthService.login),
-- but the parent, player and coach registration paths stored req.email() as typed, and the account facade stored
-- login as typed. An account registered as 'Anna@X.com' could therefore never sign in, and user_email_key /
-- user_login_key (case-sensitive) let 'anna@x.com' register next to it. The code now lowercases both on every
-- write; this migration brings the existing rows into line and makes the database refuse case duplicates.
--
-- Rows changed by the UPDATE are exactly the accounts that could not sign in, so nothing keyed on their old login
-- (created_by audit columns, file ownership) was ever written by a session of theirs. GDPR tombstones
-- (deleted.<id>@erased.io) and phone logins are already lowercase and are not touched.
--
-- If two rows collide once lowercased, the migration stops and names them: merging accounts is an owner decision,
-- never something a migration does silently. Run the collision query from the provisioning checklist on UAT/PROD
-- data before deploying.
--
-- The existing UNIQUE constraints stay (ApiAdvice maps them); the new indexes are mapped next to them.
--
-- Migration conventions (docs/deployment/migration-conventions.md): prod not live, main."user" is small (dev/uat
-- data only), and the code that relies on these indexes ships in the same release (rules 1 and 5 waived on that
-- basis, as V220/V222/V228). lock_timeout makes the statements fail fast instead of queueing behind live traffic.
SET LOCAL lock_timeout = '5s';

DO $$
DECLARE
    collisions text;
BEGIN
    SELECT string_agg(k, ', ') INTO collisions FROM (
        SELECT 'email ' || lower(email) AS k FROM main."user" GROUP BY lower(email) HAVING count(*) > 1
        UNION ALL
        SELECT 'login ' || lower(login) FROM main."user" GROUP BY lower(login) HAVING count(*) > 1
    ) c;
    IF collisions IS NOT NULL THEN
        RAISE EXCEPTION 'V231: accounts differ only by letter case, resolve before migrating: %', collisions;
    END IF;
END $$;

UPDATE main."user"
SET email = lower(email),
    login = lower(login)
WHERE email <> lower(email)
   OR login <> lower(login);

-- migration-lint: allow-blocking-index prod not live, main."user" is small (dev/uat data only), as V220/V222/V228.
CREATE UNIQUE INDEX ux_user_email_lower ON main."user" (lower(email));

-- migration-lint: allow-blocking-index prod not live, main."user" is small (dev/uat data only), as V220/V222/V228.
CREATE UNIQUE INDEX ux_user_login_lower ON main."user" (lower(login));
