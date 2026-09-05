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
     `SET lock_timeout` is worth adding defensively even here; `V129` does, for exactly
     this reason, despite touching only a handful of rows.
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

Skillars has **no production system yet**. The migrations that predate this convention —
`V60`, `V89`, `V94`, `V97`, `V98`, `V117`, and the `AdminAlertType` enum widen — are
**applied and immutable**; they are not rewritten. The convention and its guard bind
**new** migrations only (version `> V121`).

### Two baselines (skillars-deferred-92)

The rules added by skillars-deferred-92 — `DROP_WITHOUT_PRIOR_RELEASE_PREP`,
`MISSING_LOCK_TIMEOUT`, `UNBATCHED_DML`, `PLATFORM_CONFIG_EXPLICIT_ID` — bind from
**`V128`**, not `V122`, and `MigrationLint` carries a second constant
(`DEFERRED_92_BASELINE = 127`) for exactly that.

The reason is mechanical rather than editorial: **Flyway checksums a migration's whole
file, comments included.** `V122`–`V127` are already applied, so they cannot be edited —
not even to add an opt-out marker — without breaking `flyway validate` on every
environment that has run them. Several of them would trip the new rules. The choice was
between rewriting applied migrations (which the first sentence of this section forbids)
and grandfathering once more; grandfathering is the same call this project already made at
`V121`, applied a second time.

Anyone adding a rule in future should expect to add a third baseline rather than edit
history.

### Per-migration disposition (skillars-deferred-91 AC8)

Each grandfathered migration was re-read for a validating `ACCESS EXCLUSIVE` operation on a
table that can grow. Disposition: **documented safe, left as-is** for all six.

> **Pre-production grandfathering (added by the skillars-deferred-91 code review, decision D7).**
> AC8 as written offered only two dispositions: an online-safe redo, or "document it if the table
> is genuinely small at any realistic scale". Three of the six — `V60` (`videos`), `V94`
> (`booking_payments`), `V117` (`coach_radar_preferences`) — **do not** meet the second condition
> by this table's own admission: each names a growing table and a validating `ACCESS EXCLUSIVE`
> operation. They were left as-is under a third rationale, "no production system exists", which is
> a real and consistently applied project fact (`V124`, `V125`, `V126` and `V127` all lean on it)
> but is not what AC8 permitted.
>
> That third disposition is hereby **explicit and permitted**, with a hard trigger:
>
> **Before the first production deploy, `V60`, `V94` and `V117` must be redone online-safe**
> (`NOT VALID` + a later `VALIDATE CONSTRAINT`, and `CREATE INDEX CONCURRENTLY`), **and `V124`'s
> same-release CHECK widen must be split into widen-then-write across two releases.** Until then
> they are accepted as-is. This trigger is repeated in `docs/deployment/runbook.md` so it cannot be
> lost with this document. A retroactive
"online-safe redo" would have to `DROP` the already-valid constraint / index and re-add it
`NOT VALID` + `VALIDATE` (or `CONCURRENTLY`), producing a **byte-identical end state** whose
only runtime effect is overhead on every fresh install — it helps only a large *existing*
deployment mid-rolling-upgrade, and none of these already-applied migrations will ever run in
that situation. The expand/contract standard is enforced from `V122` onward instead.

| Migration | Operation | Growth table? | Blocking validate under `ACCESS EXCLUSIVE`? | Why safe / left as-is |
| :--- | :--- | :--- | :--- | :--- |
| `V60` | `video_approval_requests` FK + `chk_var_status` inside `DO … IF NOT EXISTS`; re-add `chk_videos_operational_state` on `main.videos` | `videos` grows; `video_approval_requests` moderate | The `video_approval_requests` constraints run on a table that is empty at this point (created just above / a fresh V59 stub) → instant. The `main.videos` CHECK re-add **would** full-scan under `ACCESS EXCLUSIVE` at scale. | `videos` has no production rows; a redo yields the identical CHECK. Future CHECK widenings on `videos` follow rule 5 (`NOT VALID` + later `VALIDATE`). |
| `V89` | `DROP TABLE booking.session_packs_purchased` | legacy table, deliberately removed (skillars-11-3) | No — `DROP TABLE` is a catalog-only operation, brief `ACCESS EXCLUSIVE`, **no scan or rewrite**. | Nothing to make online-safe; the lock is held for microseconds regardless of former table size. |
| `V94` | `booking_payments` `chk_bp_status` `DROP` + re-`ADD` (enum-value widen) | `booking_payments` grows | Yes — the re-`ADD CONSTRAINT … CHECK` validates the whole table under `ACCESS EXCLUSIVE`. | No production rows. The end state is the current `chk_bp_status`; a redo is churn. Any *further* status-value change must land `NOT VALID` first per rule 5. |
| `V97` | `bookings` `DROP COLUMN refund_eligibility`, `DROP COLUMN refund_amount` | `bookings` grows | No — `DROP COLUMN` in PostgreSQL is catalog-only (marks the column dropped); **no table rewrite or scan**, brief lock. | Metadata-only; online-safe as written. |
| `V98` | `ADD COLUMN distinct_coach_count INT NOT NULL DEFAULT 0` + single unbatched `UPDATE … FROM (aggregate)` backfill on `player_radar_composites` | `player_radar_composites` grows | The `ADD COLUMN` is metadata-only since PG 11 (constant default, no rewrite). The **backfill `UPDATE`** is the real at-scale hazard: one unbatched full-table write (ordinary row locks, not `ACCESS EXCLUSIVE`) → long transaction + bloat. | No production rows. Rule 6 now requires batched backfills for `V122+`; this one is already applied and cannot be re-chunked retroactively. |
| `V117` | unbatched orphan `DELETE` + `ADD CONSTRAINT fk_crp_player_id … REFERENCES` (validating) + non-`CONCURRENTLY` `CREATE INDEX ix_crp_player_id` on `development.coach_radar_preferences` | `coach_radar_preferences` grows (per coach × player) | Yes — the FK `ADD CONSTRAINT` validates under `ACCESS EXCLUSIVE` (scans both tables); the plain `CREATE INDEX` takes a `SHARE` lock blocking writes for the build. | No production rows. A redo would `DROP` the valid FK + index and re-create them `NOT VALID` / `CONCURRENTLY` for an identical end state. Rules 3 (`NOT VALID` FK) and 4 (`CONCURRENTLY`) bind any future index/FK here. |
| `AdminAlertType` widen (`V70`/`V91` `admin_alerts_type_check`) | `DROP`/`ADD` the `type` CHECK to admit `MODERATION_UNRESOLVED` | `admin_alerts` grows slowly (moderation queue) | Yes in principle, but `admin_alerts` is a bounded work-queue (OPEN rows are actioned and resolved). | Small bounded table. The *read* side is now tolerant per skillars-deferred-91 AC6 (`AdminQueueService` skips an unknown `alert_type` with a WARN instead of 500ing the page). |

## What the guard now covers (skillars-deferred-91 AC7)

The three blind spots this section used to name are now checked by `MigrationLint`:

- a **backported lower-version migration** — a `V<n>__…` with `n <= 121` that is *new in the
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
- **A decimal minor version in the baseline band is not silently grandfathered.** `V127.1` is
  newer than `V127`, even though `DEFERRED_92_BASELINE = 127` — see the two-baselines note above.

**Still not covered:** rule 5 (enum / `CHECK` widening one release ahead of the first write) has
no lint rule. `V124` deviates from it knowingly under the pre-production clause below and carries
a `-- migration-lint: allow-enum-widen-same-release` marker for the day the rule is implemented.

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
