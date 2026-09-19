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
(`V139`) breaks the mechanical subset of these rules. The rules it cannot mechanically
check are still your responsibility in review.

_Migration history before this point (formerly `V02`–`V137`) was squashed into a single
generated baseline by `skillars-deferred-112`. See
[`migration-rebaseline.md`](migration-rebaseline.md) for why, what closed by deletion rather
than rewrite, and the operator procedure for recreating an existing database against the new
baseline._

## The expand / contract standard

### Go-forward checklist (skillars-deferred-99 AC8)

A compressed form of the rules below — run it against every migration `> V139`. The prose
after it is the authority; this is the quick pass.

- [ ] **New column / table / enum value / index ships a release before any code reads or
      writes it.** New columns nullable or defaulted; `NOT NULL` comes later, after the backfill.
- [ ] **FK or `CHECK` on a table that can grow → `ADD CONSTRAINT … NOT VALID` now, `VALIDATE
      CONSTRAINT` in a later migration.** Never a validating `ADD` in one shot.
- [ ] **Index on a hot / large table → `CREATE INDEX CONCURRENTLY`.** **This has no working
      online-safe mechanism in this codebase yet** — see the callout under rule 4. Get sign-off
      on a plain `CREATE INDEX` (`-- migration-lint: allow-blocking-index <reason>`) or wait for
      the follow-up tracked in `migration-rebaseline.md`.
- [ ] **Enum / `CHECK` widening lands one release ahead of the first write of the new value.**
- [ ] **Backfill `UPDATE` is chunked** (`WHERE` on a key range or `ctid` batch + loop, each batch
      committed independently via the `executeInTransaction=false` sidecar — see rule 6), never
      one unbounded full-table write.
- [ ] **Every `DROP` is last and guarded**: `IF EXISTS`, a header explaining which release removed
      the last reader, and `-- migration-lint: drop-prepared-in: V<n>` immediately above the
      statement (one per `DROP`).
- [ ] **Every lock-taking DDL has `SET lock_timeout` in effect** at that point in the file
      (`0` = unbounded, does not count), or `-- migration-lint: allow-unbounded-lock-wait <reason>`.
- [ ] **`INSERT INTO main.platform_config` omits `id`** (identity since `V128`).
- [ ] **`MigrationConventionLintTest` passes.** It enforces the mechanical subset; the rest is review's.

---

1. **Additive first.** A new column, table, enum value, or index is deployed **before**
   any code path reads or writes it. New columns are nullable or carry a default;
   `NOT NULL` is added in a later migration after the backfill.

2. **`DROP` is last, and guarded.** A column or table is dropped only in a release
   **after** the one that removed every code reference to it. Every `DROP TABLE`,
   `DROP COLUMN`, and `DROP INDEX` carries `IF EXISTS`. A migration that performs any
   drop carries a header comment block explaining which release removed the last
   reader/writer.
   - **Name that release in a machine-readable marker** (skillars-deferred-92 AC7):
     `-- migration-lint: drop-prepared-in: V123` **immediately above the `DROP` statement
     it prepares** — statement-scoped, the same as every other `migration-lint` marker
     (code review: this one and `allow-drop-reference-scan` were, for a while, the two
     markers still matched against the whole file instead of the statement's own scope,
     which is exactly the leak the statement-scoping rule below exists to close). A
     migration that drops two columns needs two markers, one above each.
     `MigrationLint.Rule.DROP_WITHOUT_PRIOR_RELEASE_PREP` fails the build without it, and
     checks two things beyond the marker's mere presence: the named release must
     genuinely be **before** this migration's own version (not this migration's own
     version, and not a later one), and — this is the half that makes the marker more
     than decoration — it also searches `src/main/java` and `src/main/resources` for the
     dropped identifier and fails if a live reference remains. A marker that claims
     preparation while the code still reads the column is the failure mode worth catching.
   - The search is **qualified**, not a bare grep: a hit needs the `snake_case`
     identifier (or its camelCase JPA form) *and* the owning table's name in the same
     file, both matched at a word boundary (not a bare substring — `id` no longer matches
     inside `Invalid`), because column names like `id`, `status` and `amount` are far too
     generic to match on alone. It therefore **cannot** see a reader that never mentions
     the table — a raw SQL string assembled from fragments, say. For those the marker
     alone is the guarantee. `-- migration-lint: allow-drop-reference-scan <reason>`
     suppresses the search when its output is noise (statement-scoped, same as above);
     the marker and ordering requirements still stand.

3. **Constraints validate in two steps** on any table that is not trivially small:
   `ALTER TABLE … ADD CONSTRAINT … FOREIGN KEY|CHECK … NOT VALID` in one migration,
   then `ALTER TABLE … VALIDATE CONSTRAINT …` in a **later** one. `NOT VALID` takes only
   a brief `SHARE ROW EXCLUSIVE` lock and does not scan the table; `VALIDATE` scans it
   but takes only `SHARE UPDATE EXCLUSIVE`, so writes continue.
   - Opt out for a genuinely tiny / empty table with an inline
     `-- migration-lint: allow-validating-constraint <reason>` comment.

4. **Indexes on large or hot tables should use `CREATE INDEX CONCURRENTLY` — but this has
   no working online-safe mechanism in this codebase yet.** A plain `CREATE INDEX` takes a
   `SHARE` lock that blocks writes for the whole build, which is exactly what rule 4 exists
   to avoid; `CONCURRENTLY` is still the right target, but getting there needs a mechanism
   this project has not built.

   **Why the obvious fix (the `executeInTransaction=false` sidecar) does not work here**
   (found empirically during `skillars-deferred-112`, not assumed): Flyway 11.7.2 does
   support running one migration outside its own transaction wrapper, via a sidecar file
   named for the migration (`V140__some_index.sql` + `V140__some_index.sql.conf` containing
   `executeInTransaction=false`) — confirmed present in the resolved jar
   (`SqlScriptMetadata`), and Flyway does log `[non-transactional]` for the migration when
   the sidecar is applied. **That is not enough.** Flyway's own `DbMigrate` holds a separate
   bookkeeping connection open, idle-in-transaction, for the *entire* `migrate()` call
   (observed query: `SELECT COUNT(*) FROM pg_namespace WHERE nspname=$1`). `CREATE INDEX
   CONCURRENTLY`'s first phase must wait for every other open transaction that could see the
   table to finish before it can proceed — and that bookkeeping connection's transaction
   won't close until `migrate()` itself returns, which can't happen until the concurrent
   index build finishes. The two conditions produce a **permanent self-deadlock**, reproduced
   three times against both Flyway's own internal connection pool and a real `HikariDataSource`
   configured identically to this project's `application.yaml` (`auto-commit: false`, pool
   size 25, min-idle 8) — confirmed via `pg_stat_activity` showing the two backends
   permanently waiting on each other (`idle in transaction` / `Lock: virtualxid`). This is not
   a slow build; left alone it never resolves.

   **What the sidecar *is* confirmed good for:** a multi-statement, multi-commit **batched
   backfill** (rule 6) — each statement in a non-transactional script commits independently,
   with no interaction with the deadlock above (a backfill doesn't wait on other sessions'
   snapshots the way `CREATE INDEX CONCURRENTLY` does). See rule 6.

   **Until a real fix exists** (a mechanism that runs outside Flyway's own connection/lock
   lifecycle entirely — e.g. an `afterMigrate` `Callback` on a hand-opened connection, once
   Flyway's bookkeeping connection has already been released, or an out-of-band operational
   script run separately from application startup; tracked as a follow-up in
   `migration-rebaseline.md`), an index on a hot/large table has two honest options:
   - Accept a plain, blocking `CREATE INDEX` with explicit sign-off:
     `-- migration-lint: allow-blocking-index <reason, incl. expected row count and an
     estimate of build time>`.
   - Build the index **outside** the application's own Flyway-triggered migration entirely —
     an operator runs `CREATE INDEX CONCURRENTLY` by hand against the database, then a later
     migration adds nothing but a `CHECK` that the index already exists (or is skipped
     entirely if the operational runbook already covers it). This is manual and easy to
     forget; prefer the first option unless the table is genuinely too large for a blocking
     build to be acceptable even briefly.

   ### `CREATE INDEX CONCURRENTLY` failure recovery (if you build one by hand, per the above)

   A `CONCURRENTLY` build that fails part-way (a duplicate value, a deadlock, the
   session dropping) leaves an **`INVALID` index** behind in `pg_index` (`indisvalid = false`)
   — it consumes space, is maintained on every write, and is never used by the planner.
   `DROP INDEX CONCURRENTLY IF EXISTS <the_invalid_index>;` before retrying. (If this were run
   through Flyway rather than by hand, a failed non-transactional migration also leaves a
   failed row in `flyway_schema_history` that blocks the next deploy until `flyway repair`
   removes it — moot for a hand-run build, but worth knowing if the follow-up above ever lands
   as a Flyway-driven mechanism instead.)

5. **`CHECK` / enum-domain widening precedes the first write by one release.** Adding a
   value to a Postgres `enum`, or widening a `CHECK`, must land in the release **before**
   any code writes the new value — old instances still running during the rollout will
   reject it otherwise. (This is the `AdminAlertType.MODERATION_UNRESOLVED` failure mode.)

6. **Long `UPDATE` backfills are batched / chunked** (the `Def10` precedent in
   `skillars-6-1`; a pre-baseline example that used to live at `V98` is now folded into the
   `V138` baseline — see `migration-rebaseline.md`), with `SET lock_timeout` /
   `SET statement_timeout` set where a full scan is unavoidable, so a slow backfill cannot
   hold a lock indefinitely or wedge the deploy. **Multi-batch, multi-commit backfills need
   the `executeInTransaction=false` sidecar** (confirmed working — see the callout under
   rule 4): name the migration's `.conf` file after it
   (`V140__some_backfill.sql` + `V140__some_backfill.sql.conf` containing
   `executeInTransaction=false`) so each batch's `UPDATE` commits independently instead of
   accumulating in one long-held transaction. A non-transactional migration cannot be rolled
   back, so this is for genuine multi-commit backfills only, never for convenience.
   - `MigrationLint.Rule.UNBATCHED_DML` (skillars-deferred-92 AC9) fails an `UPDATE`,
     `DELETE` or `TRUNCATE` with **no** `WHERE` (or none possible, for `TRUNCATE`), or
     with a tautological one that is the *entire* predicate (`WHERE TRUE`, `WHERE 1=1` —
     `WHERE 1=1 AND id = 7` is a bounded predicate and does not count). Opt out with
     `-- migration-lint: allow-full-table-dml <reason>` immediately above the statement.
   - **What that rule does not do:** decide whether a *present* `WHERE` actually bounds
     anything. `WHERE status = 'X'` may match three rows or three million, and no
     text-level check can tell. Review still owns that half.
   - A row-level `UPDATE`/`DELETE` that is bounded (a small, named set of rows) does not
     take `ACCESS EXCLUSIVE` and is not what rule 7 below binds — but it still takes
     ordinary row locks, which a concurrent writer on the same rows can hold indefinitely.
     `SET lock_timeout` is worth adding defensively even here, despite touching only a
     handful of rows.
   - The same applies to application code, not just migrations.
     `BandwidthResetService.resetMonthlyBandwidth` was a single unpartitioned `UPDATE` over
     every `video_quotas` row at the month boundary — it locked all of them and blocked
     every concurrent `QuotaService.reserve()` for the duration. It is now a bounded loop
     of chunks, **each committed in its own transaction** by a separate
     `BandwidthResetChunkProcessor` bean. Two non-obvious requirements come with that
     shape: the driving method must **not** be `@Transactional` (one enclosing transaction
     holds every row lock to the end, which is strictly worse than the statement it
     replaced), and the chunk predicate must be **self-excluding** so the loop terminates
     and a crashed run resumes rather than double-applying.

7. **`SET lock_timeout` on every lock-taking DDL statement** (skillars-deferred-92 AC8).
   Verified during that story: only **2** of 121 migrations set one.

   **Why this matters more than it looks.** The danger is not that the `ALTER` itself is
   slow — it is usually instant. It is that a *blocked* `ALTER` sits in the lock queue, and
   **every subsequent query on that table queues behind it**. So one long-running `SELECT`
   holding a conflicting lock is enough to stall the entire table and exhaust the
   connection pool, for as long as that `SELECT` runs. A bounded wait turns that outage
   into a failed migration, which is the far better outcome. The doc previously gave the
   rule without this reason, which is most of why it was ignored.

   Put `SET lock_timeout = '5s';` near the top of the migration (it is a session/transaction
   setting, so one statement covers the whole script), or opt out with
   `-- migration-lint: allow-unbounded-lock-wait <reason>`. A `SET lock_timeout = 0`
   (Postgres's own spelling for "wait forever") does **not** satisfy the rule — the guard
   reads the value, not just the keyword's presence — and a later `RESET lock_timeout`
   correctly un-bounds every statement after it.

   **The lock levels are not all the same, and the rule does not pretend they are:**

   | Statement | Lock | Blocks |
   |---|---|---|
   | `ALTER TABLE … ADD/DROP/ALTER/RENAME COLUMN`, `ADD/DROP CONSTRAINT`, `ADD PRIMARY KEY`, `DROP TABLE`, `DROP INDEX`, `TRUNCATE` | `ACCESS EXCLUSIVE` | reads **and** writes |
   | `CREATE INDEX` (non-`CONCURRENTLY`) | `SHARE` | writes and other DDL; **reads continue** |
   | `CREATE INDEX CONCURRENTLY` | `SHARE UPDATE EXCLUSIVE` | other DDL / index builds only |

   All three are covered, because all three can wait indefinitely for a conflicting lock.
   They are not equally disruptive and the rule's message does not claim they are.

   **`lock_timeout` and `CREATE INDEX CONCURRENTLY` interact badly, and it is worth
   knowing before you hit it.** If the timeout fires *during* a concurrent build, the
   build aborts and leaves an `INVALID` index behind in `pg_index` — which must be
   dropped by hand before the migration can be retried (see the failure-recovery section
   above). A plain `CREATE INDEX` that times out simply rolls back with nothing to clean
   up. So a bounded wait on a `CONCURRENTLY` build trades a hung migration for a manual
   cleanup step on the next retry — still the better failure mode than an unbounded wait,
   but not a free one.

   **A migration on an actively-polled table should *expect* to occasionally lose this
   bounded race, and knowing what happens next matters** (skillars-deferred-124 AC7). The
   rule above is what turns an unbounded hang into a failed-and-recoverable migration; this
   is the missing other half — what "recoverable" actually means here. Before altering any
   table, grep `@Scheduled` for a poller that owns it — if one exists, its next tick can
   hold a conflicting lock at the exact moment the migration's `ACCESS EXCLUSIVE` request
   arrives. This codebase has 44 `@Scheduled` methods across 36 classes at the time of
   writing (re-count at review time — this is a worked-example pointer, not a maintained
   list): `main.video_deletion_outbox`/`development.radar_composite_dlq` (polled every 60s
   by default) are two confirmed examples, not the complete set — `main."user"` (swept by
   `UserAdminService.removeNotActivatedUsers`) and any `payment.*` table
   (`PaymentPendingSweeper`) are two more.

   For the **ordinary case this rule already covers** — a plain transactional migration,
   which is every migration in this repository outside the `CREATE INDEX CONCURRENTLY`
   sidecar case above — the failure mode is clean: the `lock_timeout` abort rolls back the
   *entire* script, schema-history bookkeeping included. There is no failed row left in
   `flyway_schema_history` and no manual cleanup; the recovery step is simply **retry the
   deploy**. Do not reach for `flyway repair` here — that command, and the "leaves a failed
   row you must remove by hand" mechanism it exists for, is specific to the
   **non-transactional** `executeInTransaction=false` sidecar case documented in the
   `CREATE INDEX CONCURRENTLY` failure-recovery section above. Writing that recovery
   mechanism for an ordinary transactional migration would put two contradictory
   descriptions of Flyway's own failure behaviour in this same document, a few paragraphs
   apart — confirm which shape a migration actually is (sidecar file present or not) before
   choosing a recovery step, not just which table it touches.

   This is accepted as a live, currently-theoretical risk, not a bug to fix — per
   skillars-deferred-117's owner decision, no production deploy of this application has
   ever happened, so the collision has had no real occurrence to date. A migration-retry
   wrapper would be a materially larger, speculative investment against that risk;
   revisit if/when this project's own "no production deploy has ever happened" premise
   changes.

   **A rolling deploy that changes a run-identity column carries the same "no production
   deploy has ever happened" caveat, and needs its own expand/contract step when it stops
   being theoretical** (skillars-deferred-124 AC4, code review 2026-09-19 Decision). When a
   migration adds a column that REPLACES an existing identity predicate outright (rather than
   adding a genuinely new one) — `main.video_deletion_outbox`/`development.radar_composite_dlq`'s
   `claimed_by` replacing `claimed_at`-exact-equality is the worked example — a pre-migration
   instance still running against the new schema during the rollout window does not know the
   new column exists. Its own claim/release/reset writes leave the new column stale (non-NULL
   from an earlier claim, never cleared), which a post-migration instance's predicate can then
   match against a row it does not actually own — a lost-update window the old, single-column
   predicate was immune to. The fix, if/when a first production deploy of a table like this is
   ever planned: keep BOTH predicates (`new_column = :x AND old_column = :y`) on every identity
   write for one full release, per rule 1's additive-first principle, then drop the old
   predicate in a follow-up release once every instance is known to be running the new code.
   Accepted as documentation only for now, exactly like the collision risk above it, for the
   identical reason.

8. **`main.platform_config` seeds omit `id`** (skillars-deferred-92 AC10). `V128` attached
   `GENERATED BY DEFAULT AS IDENTITY` to the column and seeded the sequence from the live
   maximum. Before that, every seeding migration hand-picked the next free id, and because
   the seeds use `ON CONFLICT (key)` — a *different* unique constraint from the primary key
   — an id collision raised a PK violation the `ON CONFLICT` clause never saw, failing
   Flyway on every database that had already used that id. `V99`'s header spends six lines
   on this; the ledger records five separate brushes with it.
   `MigrationLint.Rule.PLATFORM_CONFIG_EXPLICIT_ID` now fails the build on any
   `INSERT INTO main.platform_config (id, …)`.

   `BY DEFAULT` rather than `ALWAYS` is deliberate: `ALWAYS` rejects an explicit `id`, which
   would break every historical seed the moment Flyway replayed it on a fresh database —
   breaking CI and every new environment while continuing to look fine on migrated ones.

9. **Every DDL migration that takes `ACCESS EXCLUSIVE` on a table with expected
   production volume carries a header comment** naming the lock it takes and the
   online-safe alternative that was considered and why it was or wasn't used.

## Grandfathering

Skillars had no production system as of `skillars-deferred-112`, which used that fact to close
this section's entire subject rather than keep managing it: the whole pre-baseline migration
history (formerly `V02`–`V137`, six of them carrying lock-unsafe patterns — `V60`, `V94`, `V117`,
`V124`, `V98`, and the three `CREATE INDEX` migrations `V125`/`V126`/`V127`) was **deleted**, not
rewritten, and replaced with one generated baseline: `V138__baseline_schema.sql` (pure DDL) and
`V139__baseline_seed_data.sql` (seed rows). See
[`migration-rebaseline.md`](migration-rebaseline.md) for why, the full list of what closed by
deletion, the git SHA the deleted history is still findable at, and the operator procedure for
recreating an existing database against the new baseline.

`V138` and `V139` are themselves grandfathered — deliberately, not by oversight. `V138` is
machine-generated from `pg_dump` and necessarily contains validating `ADD CONSTRAINT`s and
non-concurrent `CREATE INDEX`es; both are correct here because the file runs exactly once,
against an empty database, where they are instantaneous. Linting generated output would demand
hundreds of opt-out comments for zero safety gain. `V139` is pure `INSERT … ON CONFLICT DO
NOTHING`, which trips no rule regardless. The convention and its guard bind **new** migrations
only (version `> V139`).

### One baseline, not two

Before the squash, this section described **two** grandfather baselines
(`MigrationLint.GRANDFATHER_BASELINE` and `MigrationLint.DEFERRED_92_BASELINE`), because the
skillars-deferred-92 rules arrived after some migrations (`V122`–`V127`) were already applied and
checksum-frozen, and Flyway checksums a migration's whole file — an applied migration cannot be
edited, not even to add an opt-out marker, without breaking `flyway validate` on every
environment that has run it. Rewriting those applied migrations was off the table for the same
reason `V60`/`V94`/`V117` couldn't be rewritten either, so the rules bound from a second,
later constant instead of the first.

The squash deleted that whole band along with everything else below it. There is no longer a gap
between the two boundaries to bridge: **both constants now sit at `V139`.** They are kept as two
distinct constants rather than collapsed into one, because they still gate mechanically distinct
rule sets and could diverge again if a future rule needs its own grandfather band — see
`migration-rebaseline.md`'s follow-up note for the case to collapse them properly.

Anyone adding a rule in future that would flag an already-applied migration above `V139` should
expect to add a third baseline rather than edit history — the same call this project has now made
twice.

## What the guard now covers (skillars-deferred-91 AC7)

The three blind spots this section used to name are now checked by `MigrationLint`:

- a **backported lower-version migration** — a `V<n>__…` with `n <= 139` that is *new in the
  working tree* (absent from `git cat-file -e HEAD:<path>`) fails as `BACKPORT_BELOW_BASELINE`.

  **Scope, stated honestly (corrected by the skillars-deferred-91 code review).** This rule is a
  *pre-commit, local* guard — **not a CI gate**. In CI the checked-out commit already contains
  every migration file, so `git cat-file -e HEAD:<path>` answers `0` for all of them and the rule
  cannot fire there. It catches a below-baseline migration while its author still has it staged or
  untracked. Catching a *committed* backport would need a merge-base diff
  (`git log --diff-filter=A` against the base branch), which is not reliable on the shallow clones
  CI uses; that remains open.

  (The same review also fixed the rule being inert *everywhere*: `git cat-file -e HEAD:<absent>`
  exits **128**, which the original code read as "git could not answer ⇒ assume known". "Cannot
  answer" is now decided up front via `git rev-parse --is-inside-work-tree`, and only that case
  assumes known.)
- an **`R__` repeatable migration** — scanned for a `DROP` without `IF EXISTS`
  (opt-out: `-- migration-lint: allow-unconditional-drop <reason>`, which the code review added —
  the branch previously honoured no opt-out at all despite this sentence), a blocking
  `CREATE INDEX`, or a validating constraint (`REPEATABLE_HAZARD`); the `allow-*` opt-outs
  still apply;
- an **inline foreign key** — `ALTER TABLE a ADD b bigint REFERENCES c(id)` without `NOT VALID`
  fails as `INLINE_FK_ADD_COLUMN` (the `ADD CONSTRAINT` rule never saw it). The code review
  widened this to the two other spellings PostgreSQL accepts for the identical hazard: `COLUMN`
  is optional (`ADD b bigint REFERENCES c(id)`) and so is the referenced column list
  (`ADD COLUMN b bigint REFERENCES c`, defaulting to the referenced PK);
- a **`DROP CONSTRAINT` without `IF EXISTS`** — added by the code review alongside
  `DROP TABLE`/`COLUMN`/`INDEX`. `V124`'s own `DROP CONSTRAINT chk_bp_status` was unlinted until
  then and would have failed hard on any environment where the constraint was already absent.

### Added by skillars-deferred-92 (AC7–AC11)

- **expand/contract ORDERING**, not just statement shape — `DROP_WITHOUT_PRIOR_RELEASE_PREP`.
  A `DROP TABLE`/`DROP COLUMN` must carry `-- migration-lint: drop-prepared-in: V<n>`, that
  release must genuinely be before this migration's own version, **and** no live reference to
  the dropped identifier may remain in `src/main`. Rule 2 above states exactly what the
  reference search can and cannot see. `COLUMN` is optional in PostgreSQL's grammar for a
  `DROP` sub-clause, and the rule accounts for both spellings.
- **`SET lock_timeout` genuinely in effect** on lock-taking DDL — `MISSING_LOCK_TIMEOUT`. See
  rule 7 for the queue-behind-the-blocked-`ALTER` mechanism, the per-statement lock levels, and
  the `SET … = 0` / `RESET` handling. *Implementation note:* the check strips comments — literal-
  aware, so a string value that happens to contain `--` does not corrupt the statements after it
  — before looking for `SET lock_timeout`. Without that, a migration whose header merely
  **discusses** `lock_timeout` silences the rule for its own DDL.
- **Unbatched full-table DML** — `UNBATCHED_DML`, for an `UPDATE`, `DELETE` or `TRUNCATE` with no
  `WHERE` (or none possible) or a tautological one. `WHERE` and the write keyword itself are
  matched at the statement's top level, not inside a subquery's own parentheses, and a leading
  CTE (`WITH x AS (...) DELETE FROM t`) is recognised as the write it feeds. Rule 6 states the
  half it cannot decide.
- **Hand-picked `platform_config` ids** — `PLATFORM_CONFIG_EXPLICIT_ID`. See rule 8. Covers both
  an explicit `id` in the column list and an omitted column list (which supplies every column,
  `id` included, positionally).
- **Per-clause `NOT VALID` evaluation** (AC11.1). `ALTER TABLE t ADD CONSTRAINT a CHECK (…) NOT
  VALID, ADD CONSTRAINT b CHECK (…);` used to pass, because the rule asked only whether
  `NOT VALID` appeared *somewhere* in the statement. Clauses are now split on **top-level**
  commas with balanced-paren tracking — necessary because a `CHECK` body carries its own commas
  (`CHECK (x IN (1,2,3))`), which a naive split would tear in half. The same splitting closes the
  identical gap for a multi-target `DROP TABLE a, b` / multi-clause `DROP COLUMN a, DROP COLUMN
  b`, which used to have only the first target reference-scanned.
- **Statement-scoped opt-outs** (AC11.2), `drop-prepared-in` and `allow-drop-reference-scan`
  included. A `-- migration-lint: allow-*` marker used to match against the whole file, so one
  opt-out silenced that rule for every statement in the migration. A marker now covers only the
  statement that follows it — and is honoured only when it is an actual comment, not when the
  identical text happens to appear inside a string literal.
- **`R__` repeatables, and a subdirectory of the Flyway location, are linted too.** Both were
  previously invisible to the four rules above — a repeatable re-runs on every checksum change,
  which makes an unprepared drop or an unbounded lock wait there at least as much a hazard as in
  a versioned migration, and Flyway's own scan of `classpath:db/migration` is recursive where the
  lint's used not to be.
- **A decimal minor version in the baseline band is not silently grandfathered.** `V139.1` is
  newer than `V139`, even though both baseline constants equal `139` — see the "One baseline,
  not two" note above.

**Still not covered:** rule 5 (enum / `CHECK` widening one release ahead of the first write) has
no lint rule. A pre-baseline example (`V124`, now folded into `V138`) deviated from it knowingly
and carried a `-- migration-lint: allow-enum-widen-same-release` marker for the day the rule is
implemented — that marker is gone with the file, but the gap it documented is not: rule 5 still
has no mechanical check.

## What the guard still cannot catch

The lint is a **text-level backstop, not a proof**. The two items this section used to name —
a second validating constraint in the same statement, and an opt-out leaking to every other
statement in the file — were both closed by skillars-deferred-92 AC11 and now appear above.

What genuinely remains:

- **Whether a present `WHERE` actually bounds anything.** `UNBATCHED_DML` sees a missing or
  tautological `WHERE`; it cannot know that `WHERE status = 'X'` matches three rows rather than
  three million.
- **A reader that never names its table.** `DROP_WITHOUT_PRIOR_RELEASE_PREP`'s reference search
  requires the identifier and the table name in the same file, so a raw SQL string assembled
  from fragments, or a column accessed purely by index, is invisible to it. The
  `drop-prepared-in` marker is the guarantee for those; the search is what stops the marker
  being a rubber stamp.
- **File types the reference scan does not read.** It scans `.java`, `.sql`, `.yaml`, `.yml`,
  `.xml`, `.properties`, `.html` and `.json` (the `.html` addition matters: `src/main/resources/
  mails/*.html` is a live template location). A reader in any other file type is invisible to it.
- **Performance / fragility at scale.** The reference search re-walks the whole of `src/main`
  once per dropped identifier with no caching, and does so against the *real* tree for the real
  migrations — so an unrelated new class that happens to contain a dropped identifier's substring
  can, in principle, break a build on a migration nobody touched. Word-boundary matching (above)
  narrows this considerably but does not eliminate it.
- **Enum / `CHECK` widening one release ahead of the first write** (rule 5) — no rule yet.
- **A committed backport below the baseline.** `BACKPORT_BELOW_BASELINE` is a pre-commit,
  working-tree guard; in CI every migration already exists at `HEAD`, so it cannot fire there.
- **Anything requiring a database**: actual table sizes, actual lock contention, whether an
  index is really needed. The lint reads text.

Review still owns these. They are listed rather than implied because this project has three
recorded instances of a guard believed stronger than it was, and an overstated guard is worse
than a missing one.

## PR checklist

Before merging a PR that adds or changes a file under
`src/main/resources/db/migration/`:

- [ ] The change follows [`docs/deployment/migration-conventions.md`](migration-conventions.md)
      (expand/contract: additive first, guarded `DROP` last, `NOT VALID` + later
      `VALIDATE` for FK/CHECK on non-trivial tables, `CONCURRENTLY` for indexes on
      hot/large tables, enum/CHECK widening one release ahead of the first write,
      batched backfills).
- [ ] Any `DROP TABLE` / `DROP COLUMN` carries its own `-- migration-lint:
      drop-prepared-in: V<n>` immediately above the statement, naming a release that is
      strictly before this one and that really did remove the last reader — one marker
      per `DROP`, not one for the file.
- [ ] Any lock-taking DDL has a `SET lock_timeout` genuinely in effect at that point in the
      file (see rule 7 for why — and note `0` does not count, and a `RESET` un-bounds
      everything after it), or carries `-- migration-lint: allow-unbounded-lock-wait <reason>`.
- [ ] Any `UPDATE` / `DELETE` / `TRUNCATE` bounds its row count, or carries
      `-- migration-lint: allow-full-table-dml <reason>`.
- [ ] Any `INSERT INTO main.platform_config` **omits `id`** — the column has an identity as of
      `V128`.
- [ ] `MigrationConventionLintTest` passes (it runs in the `test` phase).
- [ ] Any `-- migration-lint: allow-*` opt-out carries a real reason **and sits immediately above
      the statement it covers** — markers are statement-scoped, not file-scoped.
