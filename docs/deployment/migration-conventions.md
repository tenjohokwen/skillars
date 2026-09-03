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

**Still not covered:** rule 5 (enum / `CHECK` widening one release ahead of the first write) has
no lint rule. `V124` deviates from it knowingly under the pre-production clause below and carries
a `-- migration-lint: allow-enum-widen-same-release` marker for the day the rule is implemented.

## What the guard still cannot catch

The lint is a **text-level backstop, not a proof**. It does not catch:

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
