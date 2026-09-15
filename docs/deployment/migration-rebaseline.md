# Pre-production migration rebaseline (skillars-deferred-112)

_Sibling of [`migration-conventions.md`](migration-conventions.md), which this document is
cross-linked from. Read that document first for the ongoing, enforce-on-all-new-migrations rules;
this document is the one-time historical record of the rebaseline itself._

## Why this happened

Skillars' migration history (`V02`–`V137`, 131 files) predated the rolling-deploy safety
conventions in `migration-conventions.md`. Six of those files carried patterns the current lint
rules would reject if they were new:

- `V60` (`main.videos`), `V94` (`payment.booking_payments`), `V117`
  (`development.coach_radar_preferences`) — a validating `ADD CONSTRAINT` or a non-concurrent
  `CREATE INDEX` under `ACCESS EXCLUSIVE` on a table expected to grow.
- `V124` — a `CHECK` widen shipping in the same release as its first write, deviating from
  convention rule 5.
- `V98` — an unbatched full-table `UPDATE` backfill.
- `V125`/`V126`/`V127` — `CREATE INDEX` without `CONCURRENTLY`, because Flyway runs migrations in
  a transaction.

None of these could be fixed in place. **Flyway checksums a migration's whole file, comments
included**, so an applied migration cannot be edited — not even to add an opt-out marker —
without breaking `flyway validate` on every environment that has already run it. That constraint
is also why `MigrationLint` carried **two** grandfather baselines instead of one: each was a
workaround for a band of files that could be neither fixed nor exempted the normal way.

Before the first production deploy, these needed to become online-safe and the migration system
needed to be ready to enforce safety on every future change, with no more grandfather exceptions.
This document records how `skillars-deferred-112` did both.

## What was done

Three decisions, made explicit before writing any code (see the story file's Resolved Decisions
section for the full rationale and the correction below):

- **D1 — true squash, not a Flyway `baselineVersion`.** The entire pre-baseline history was
  physically **deleted**, not edited or exempted, and replaced with two generated files:
  `V138__baseline_schema.sql` (pure DDL) and `V139__baseline_seed_data.sql` (seed rows). A fresh
  database now runs exactly these two migrations — there is no dual bootstrap path, no
  `flyway info` "SKIP" state to reason about, and nothing left to make online-safe: `V60`/`V94`/
  `V117`/`V124` do not exist in any form, so the lock-unsafe patterns they carried are gone rather
  than fixed.
- **D2 — a companion seed migration.** `V138` stays pure DDL so it stays regenerable straight from
  `pg_dump --schema-only`; every seeded row lives in the one obvious companion file instead.
- **D3 — `executeInTransaction=false`, for batched backfills only.** See the correction below —
  this did **not** turn out to also solve `CREATE INDEX CONCURRENTLY`, despite that being the
  original plan.

### Which gate items closed by deletion

`docs/deployment/runbook.md`'s former *"Pre-production release gate: outstanding migration
rewrites"* section tracked four items — `V60`, `V94`, `V117`, `V124` — plus a follow-on note about
`V125`/`V126`/`V127`. All are closed **by deletion**: the files no longer exist, so the patterns
they carried cannot run against a database with data in it. `migration-conventions.md` made the
argument for why this is a real closure and not a deferral, before the squash happened:

> A retroactive "online-safe redo" would have to `DROP` the already-valid constraint / index and
> re-add it `NOT VALID` + `VALIDATE` (or `CONCURRENTLY`), producing a byte-identical end state
> whose only runtime effect is overhead on every fresh install — it helps only a large *existing*
> deployment mid-rolling-upgrade, and none of these already-applied migrations will ever run in
> that situation.

The squash goes one step further than that argument anticipated: instead of leaving the files in
place and accepting the churn of a redo that would only matter for a rolling upgrade that will
never happen against them, the files are gone. `V138` declares each object once, in its final
shape, and only ever runs against an empty database — there is no "redo" left to do because there
is no lock-unsafe operation left to make safe.

### Correction found during dev: `CREATE INDEX CONCURRENTLY` is still open

D3 was decided assuming the `executeInTransaction=false` sidecar would let a future migration run
`CREATE INDEX CONCURRENTLY` safely, closing the `V125`/`V126`/`V127` follow-on note for good.
**Empirical testing during implementation disproved that.** Full detail, including the exact
`pg_stat_activity` evidence, lives in `migration-conventions.md` rule 4; the short version:

Flyway's own `DbMigrate` holds a bookkeeping connection open, idle-in-transaction, for the entire
duration of one `migrate()` call. `CREATE INDEX CONCURRENTLY`'s first phase must wait for every
other open transaction that could see the table to finish before it can proceed — and that
bookkeeping connection's transaction won't close until `migrate()` itself returns, which can't
happen until the concurrent index build finishes. The result is a **permanent self-deadlock**,
reproduced three times against both Flyway's own connection pool and a real `HikariDataSource`
configured identically to this project's `application.yaml` (`auto-commit: false`, pool size 25,
min-idle 8). This is not a slow build; left alone it never resolves.

The sidecar **is** confirmed to work for the other half of D3 — a multi-statement, multi-commit
**batched backfill** — tested independently and documented in `migration-conventions.md` rule 6.

**Consequence:** the three deleted migrations that used to assert this was impossible —
`V125`, `V126`, `V127`, each reading *"this codebase runs Flyway migrations in a transaction so
`CREATE INDEX CONCURRENTLY` cannot be used"* — turn out to have had the right conclusion, even
though their stated mechanism (simple transaction-wrapping) undersold the real one. `runbook.md`'s
former gate table is retired as closed (the files are gone), but the underlying problem is
**reopened as ongoing project debt, not a pre-production gate**: it doesn't block the first
production deploy the way the four closed items did, but it blocks the first time this codebase
genuinely needs a `CONCURRENTLY` index on a hot/large table. See "Follow-up work" below.

### `V138`/`V139` are grandfathered by design

With both `MigrationLint` baseline constants (`GRANDFATHER_BASELINE`,
`DEFERRED_92_BASELINE`) at `139`, neither baseline file is linted:

- `V138` is machine-generated from `pg_dump`. It necessarily contains validating `ADD
  CONSTRAINT`s, non-concurrent `CREATE INDEX`es, and no `SET lock_timeout` anywhere. Every one of
  those is correct here — the file runs exactly once, against an empty database, where all of
  them are instantaneous. Linting generated output would demand hundreds of opt-out comments for
  zero safety gain.
- `V139` is pure `INSERT … ON CONFLICT DO NOTHING`, which trips no rule regardless
  (`UNBATCHED_DML` covers `UPDATE`/`DELETE`/`TRUNCATE`, not `INSERT`).

Every migration from `V140` onward is bound by the full rule set with no grandfather exceptions —
the entire point of collapsing the two baselines into one.

### The two baseline constants stay two, for now

`MigrationLint.GRANDFATHER_BASELINE` and `MigrationLint.DEFERRED_92_BASELINE` are both `139`, but
were deliberately **not** collapsed into a single constant in this story. They still gate
mechanically distinct rule sets (the original expand/contract rules vs. the skillars-deferred-92
additions), threaded through a four-parameter `MigrationLint.lint(...)` overload and several
`MigrationConventionLintTest` call sites, including a fixture pair (`V800`–`V808`,
`FIXTURE_DEFERRED_92_BASELINE = 808`) that exercises the two-baseline mechanism independently of
the real tree's constants. **Follow-up:** now that both real constants share one value, a future
story could collapse them into a single `BASELINE` constant and retire the now-redundant
parameter — worth doing once the fixture set is reviewed for whether it still needs to exercise
two independent values or can be simplified alongside it.

## Operator procedure: recreating an existing database

Deleting `V02`–`V137` invalidates every `flyway_schema_history` row in every database that
already applied them. This is expected and is what makes the squash "true" rather than a
`baselineVersion`-based workaround — but it means every environment that has ever run the old
history needs this procedure.

**Pre-flight assertion — do this first.** Confirm no production database exists. If one does,
**stop**: this rebaseline is no longer safe to apply as a plain database recreation, and the
story that authorized it must be reopened against a `baselineVersion`-based approach instead. As
of this writing that assertion holds — no production deploy has ever happened, which is the
precondition that made this story legal at all.

**What breaks, and why.** `flyway validate` fails with:

```
Detected applied migration not resolved locally: 138.
If you removed this migration intentionally, run repair to mark the migration as deleted.
```

(Reproduced empirically — every deleted version from `V02` through `V137` produces its own such
line.) This is not recoverable by configuration: `ignoreMigrationPatterns` would suppress the
error but leave a `flyway_schema_history` table describing migrations that no longer exist, which
defeats the point of the rebaseline. `flyway repair` has the same problem at a different point —
it would mark 131 rows deleted rather than actually resolving the mismatch.

**The procedure.** Drop and recreate the database, then let the application migrate it from
`V138`/`V139` on boot:

```bash
dropdb skillars   # or your environment's equivalent
createdb skillars
# start the application normally — Flyway migrates V138, then V139, on the FlywayMigrationInitializer bean
```

**The `flyway clean` caveat.** `flyway clean` is disabled by default — this is **Spring Boot's**
Flyway autoconfiguration default (`spring.flyway.clean-disabled` defaults to `true` per its
configuration metadata), not a Flyway-core default (raw Flyway defaults `cleanDisabled` to
`false`), and this project sets no override. So `clean` fails out of the box. Either set
`spring.flyway.clean-disabled=false` for the operation, or (preferred, and what this document's
procedure above uses) `DROP DATABASE` / `CREATE DATABASE` directly.

**Which environments are affected.** Every developer workstation, plus any dev/UAT database that
has ever run the pre-squash migrations. Production is explicitly **not** affected — no production
deploy has occurred.

**A build-artifact gotcha specific to this squash (found during dev, not obvious from the
procedure above).** Maven's `target/classes` directory is populated incrementally by
`process-resources` and is **not** pruned when a source file is deleted. If `target/classes/db/
migration/` was populated by a build from before this rebaseline, it still contains all 131
deleted files alongside the new `V138`/`V139` — and `classpath:db/migration` (what Flyway
actually reads at runtime) sees all 133. Against a genuinely fresh database this reproduces as:

```
ERROR: schema "admin" already exists
```

(`admin` is simply the first schema a stale `V70` — long deleted from the source tree, still
sitting in `target/classes` — tries to create a second time, after the equally-stale `V02`–`V137`
files it's bundled with have already run past that point once via their `IF NOT EXISTS` guards;
`V138` itself carries no such guard and fails loudly instead of silently succeeding, per its own
design.) **The fix is `mvn clean` before the next build**, not a change to any migration file.
Anyone hitting this error locally after pulling this rebaseline should run a clean build before
suspecting the migrations themselves.

**Verification.** A reviewer following this procedure on a scratch database that has `V02`–`V137`
applied should reach a working application without consulting anything outside this document.

## Reference

- **Last pre-squash commit:** `4a3f218dabd04e0c8419282e922f60de5f93934d` — the migration history
  is fully recoverable from git at this SHA (`git show
  4a3f218dabd04e0c8419282e922f60de5f93934d:src/main/resources/db/migration/V137__envelope_entity_recipients_composite_pk.sql`,
  for example). This is the only place that history needs to live now.
- **Go-forward rules:** [`migration-conventions.md`](migration-conventions.md) — every migration
  from `V140` onward is bound by the full rule set with no grandfather exceptions.

## Follow-up work (not done by this story)

- **`CREATE INDEX CONCURRENTLY` has no working online-safe mechanism in this codebase.** See the
  correction above and `migration-conventions.md` rule 4 for the full empirical finding. A real
  fix needs a mechanism that runs outside Flyway's own connection/lock lifecycle entirely — e.g.
  an `afterMigrate` `Callback` on a hand-opened connection (fired once Flyway's bookkeeping
  connection has already been released), or an out-of-band operational script run separately from
  application startup. Neither is implemented here. Until one exists, a hot/large-table index must
  either accept a plain, sign-off'd blocking `CREATE INDEX`, or be built by hand outside Flyway
  (see `migration-conventions.md` rule 4 for both options in detail).
- **The two `MigrationLint` baseline constants could be collapsed into one**, now that both sit at
  the same value. See "The two baseline constants stay two, for now" above.
- **`docs/architecture/payout-and-capture-pending.md`** narrates `V124`'s own design reasoning
  (~10 references) and is deliberately **not** rewritten to describe the post-squash state — it's
  a historical decision record of what was decided and why at the time, the same way git history
  is the only place the deleted migrations need to live now. Its `V124` references are expected to
  remain.
