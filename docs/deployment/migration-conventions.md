# Database migration conventions (rolling-deploy / online-migration safety)

_Sibling of [`first-time-setup.md`](first-time-setup.md) and [`rollback.md`](rollback.md). Introduced by `skillars-deferred-90` AC10._

Skillars runs a **single application instance** today, so a migration and the code that
depends on it currently ship together and a brief exclusive lock is invisible to users.
That will not stay true. These conventions make every **new** migration safe for a
rolling deploy (old and new code running against the same schema for a window) and for a
table large enough that an `ACCESS EXCLUSIVE` lock or a full-table rewrite would cause a
visible stall.

An automated guard (`MigrationConventionLintTest`, run in the `test` phase — no
container) fails the build when a migration **above the grandfather baseline**
(`V121`) breaks the mechanical subset of these rules. The rules it cannot mechanically
check are still your responsibility in review.

## The expand / contract standard

1. **Additive first.** A new column, table, enum value, or index is deployed **before**
   any code path reads or writes it. New columns are nullable or carry a default;
   `NOT NULL` is added in a later migration after the backfill.

2. **`DROP` is last, and guarded.** A column or table is dropped only in a release
   **after** the one that removed every code reference to it. Every `DROP TABLE`,
   `DROP COLUMN`, and `DROP INDEX` carries `IF EXISTS`. A migration that performs any
   drop carries a header comment block explaining which release removed the last
   reader/writer.

3. **Constraints validate in two steps** on any table that is not trivially small:
   `ALTER TABLE … ADD CONSTRAINT … FOREIGN KEY|CHECK … NOT VALID` in one migration,
   then `ALTER TABLE … VALIDATE CONSTRAINT …` in a **later** one. `NOT VALID` takes only
   a brief `SHARE ROW EXCLUSIVE` lock and does not scan the table; `VALIDATE` scans it
   but takes only `SHARE UPDATE EXCLUSIVE`, so writes continue.
   - Opt out for a genuinely tiny / empty table with an inline
     `-- migration-lint: allow-validating-constraint <reason>` comment.

4. **Indexes on large or hot tables use `CREATE INDEX CONCURRENTLY`** in a
   **non-transactional** migration (Flyway: a `.sql` migration whose script sets
   `-- executeInTransaction=false` is not supported directly — use a dedicated migration
   containing only the concurrent index, and mark it so Flyway does not wrap it: see the
   Flyway `CREATE INDEX CONCURRENTLY` note below). A plain `CREATE INDEX` takes a
   `SHARE` lock that blocks writes for the whole build.
   - The **current in-repo standard is still non-concurrent** — see
     `V121__phone_otp_tokens_one_active_per_user.sql:14-16`, which documents why (Flyway
     wraps migrations in a transaction; `CONCURRENTLY` cannot run in one; the table is
     tiny). The concurrent form is the target for any index on a table expected to grow.
   - Opt out for a small table with `-- migration-lint: allow-blocking-index <reason>`.

   ### `CREATE INDEX CONCURRENTLY` failure recovery (read before you use it)

   A `CONCURRENTLY` build that fails part-way (a duplicate value, a deadlock, the
   session dropping) leaves **two** artefacts behind:
   - an **`INVALID` index** in `pg_index` (`indisvalid = false`) — it consumes space,
     is maintained on every write, and is never used by the planner; and
   - a **failed row in `flyway_schema_history`** (`success = false`).

   The next deploy is then **blocked**: Flyway refuses to run with a failed history row.
   An operator must, by hand, on the database:
   1. `DROP INDEX CONCURRENTLY IF EXISTS <the_invalid_index>;`
   2. `flyway repair` (removes the failed history row);
   3. re-run the deploy, which re-attempts the migration.

   Put these three steps in the migration's header comment so whoever is paged has them.

5. **`CHECK` / enum-domain widening precedes the first write by one release.** Adding a
   value to a Postgres `enum`, or widening a `CHECK`, must land in the release **before**
   any code writes the new value — old instances still running during the rollout will
   reject it otherwise. (This is the `AdminAlertType.MODERATION_UNRESOLVED` failure mode.)

6. **Long `UPDATE` backfills are batched / chunked** (see `V98` and the `Def10`
   precedent in `skillars-6-1`), with `SET lock_timeout` / `SET statement_timeout` set
   where a full scan is unavoidable, so a slow backfill cannot hold a lock indefinitely
   or wedge the deploy.

7. **Every DDL migration that takes `ACCESS EXCLUSIVE` on a table with expected
   production volume carries a header comment** naming the lock it takes and the
   online-safe alternative that was considered and why it was or wasn't used.

## Grandfathering

Skillars has **no production system yet**. The migrations that predate this convention —
`V60`, `V89`, `V94`, `V97`, `V98`, `V117`, and the `AdminAlertType` enum widen — are
**applied and immutable**; they are not rewritten. The convention and its guard bind
**new** migrations only (version `> V121`).

## What the guard cannot catch

The lint is a **text-level backstop, not a proof**. It does not catch:

- a **backported lower-version migration** — a `V118__…` added after `V121` already
  exists is below the baseline and is skipped entirely;
- an **`R__` repeatable migration** — the version filter never matches it;
- an **inline foreign key** — `ALTER TABLE a ADD COLUMN b bigint REFERENCES c(id)` adds
  an FK with no literal `ADD CONSTRAINT` text, so the `NOT VALID` rule never fires on it;
- a **second validating constraint in the same statement** — the `NOT VALID` rule splits on
  `;` and asks only whether `NOT VALID` appears *somewhere* in the statement, so
  `ALTER TABLE t ADD CONSTRAINT a CHECK (…) NOT VALID, ADD CONSTRAINT b CHECK (…);`
  passes even though `b` validates. Add one constraint per statement and the rule is exact;
- a **second blocking index in a file that already carries an opt-out** — the
  `-- migration-lint: allow-*` markers are matched against the whole file, so one opt-out
  silences that rule for every statement in the migration. Keep opt-outs in single-purpose
  migrations.

Review still owns these.

## PR checklist

Before merging a PR that adds or changes a file under
`src/main/resources/db/migration/`:

- [ ] The change follows [`docs/deployment/migration-conventions.md`](migration-conventions.md)
      (expand/contract: additive first, guarded `DROP` last, `NOT VALID` + later
      `VALIDATE` for FK/CHECK on non-trivial tables, `CONCURRENTLY` for indexes on
      hot/large tables, enum/CHECK widening one release ahead of the first write,
      batched backfills).
- [ ] `MigrationConventionLintTest` passes (it runs in the `test` phase).
- [ ] Any `-- migration-lint: allow-*` opt-out carries a real reason.
