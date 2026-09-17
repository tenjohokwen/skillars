# Story: Pre-production Migration Rebaseline

**Story Key:** `skillars-deferred-112-pre-production-migration-rebaseline`
**Epic:** Deferred Work
**Priority:** Pre-production blocker (must complete before first production deploy)
**Status:** done
**Created:** 2026-09-15
**Last Updated:** 2026-09-15 (dev complete via `/bmad-dev-story`; see Dev Agent Record for the D3/AC5 correction found during implementation)
**Story File Version:** 2.0

---

## User Story

As a **DevOps/Platform Engineer**, I want to **squash the entire migration history into a single clean baseline before the first production deploy** so that **the lock-unsafe applied migrations stop existing, the checksum-frozen-file constraint is removed, and `MigrationLint` binds every future migration with zero grandfather exceptions**.

---

## Resolved Decisions

Revision 1 of this story was blocked on three unresolved questions. All three are now decided; the ACs below implement those decisions and must not be re-litigated during dev.

| # | Question | Decision | Consequence |
| :-- | :--- | :--- | :--- |
| **D1** | How does a fresh database get the baseline instead of replaying history? | **True squash.** Physically delete `V02`–`V137` and replace them with one DDL baseline plus one seed migration. | No Flyway `baselineVersion` trickery, no dual bootstrap path. Every existing dev/UAT database must be dropped and recreated (AC7). **V60, V94, V97, V98, V117 and V124 cease to exist, so the pre-production gate closes by deletion, not by rewrite.** |
| **D2** | Where does seed data live? | **Companion seed migration.** `V138__baseline_schema.sql` stays pure DDL; `V139__baseline_seed_data.sql` carries every seeded row. | The DDL file stays regenerable from `pg_dump --schema-only`; seeds are one obvious file. |
| **D3** | Adopt per-script `executeInTransaction=false`? | **Partially — yes for batched backfills, no for `CREATE INDEX CONCURRENTLY`.** Sanction the `V<n>__<desc>.sql.conf` sidecar as the supported way to run a multi-statement, multi-commit batched backfill (rule 6). **Do NOT claim it makes `CREATE INDEX CONCURRENTLY` (rule 3) safe** — empirically it doesn't, under this project's actual configuration; see below. | The sidecar mechanism is real and confirmed available in the resolved `flyway-core` **11.7.2** (`SqlScriptMetadata`, keyed on `executeInTransaction`, sidecar named `<script>.sql.conf`). It closes the runbook's `V125`/`V126`/`V127` item **by deletion** (D1), not by making their pattern newly safe — the pattern itself remains unsafe for a future migration; see the correction below. |

### Why the gate closes by deletion (D1 consequence — read this before AC2)

`docs/deployment/migration-conventions.md:255-259` already makes the argument, and the squash completes it:

> A retroactive "online-safe redo" would have to `DROP` the already-valid constraint / index and re-add it `NOT VALID` + `VALIDATE` (or `CONCURRENTLY`), producing a **byte-identical end state** whose only runtime effect is overhead on every fresh install — it helps only a large *existing* deployment mid-rolling-upgrade, and none of these already-applied migrations will ever run in that situation.

After the squash, `V60`/`V94`/`V117`/`V124` do not exist in any form. The baseline declares each constraint once, in its final shape, and only ever executes against an **empty** database, where a validating `ADD CONSTRAINT` and a blocking `CREATE INDEX` are both instantaneous. There is nothing left to make online-safe.

**This story therefore does NOT rewrite V60/V94/V98/V117 into `NOT VALID` + `VALIDATE` pairs.** Revision 1 specified that work across its AC2–AC5; it is deliberately removed. Expand/contract binds **new** migrations from `V140` onward (AC5), which is where it actually buys something.

### Correction to D3 (found during dev, not during review): `executeInTransaction=false` does not make `CREATE INDEX CONCURRENTLY` safe here

D3 as originally decided assumed the `.conf` sidecar alone would let a future migration run `CREATE INDEX CONCURRENTLY` safely. **Empirical testing during implementation disproves that** for this project's actual configuration (Flyway 11.7.2 + a HikariCP pool with `hikari.auto-commit: false`, matching `application.yaml:85-92` exactly):

- Flyway's own `DbMigrate` holds a separate bookkeeping connection open, idle-in-transaction (observed query: `SELECT COUNT(*) FROM pg_namespace WHERE nspname=$1`), for the **entire duration** of a single `migrate()` call.
- `CREATE INDEX CONCURRENTLY`'s first phase must wait for every other open transaction that could see the table to finish, before it can proceed to build and validate the index.
- Those two facts combine into a genuine, **permanent self-deadlock**: the bookkeeping connection's transaction won't close until `migrate()` returns, and `migrate()` can't return until the concurrent index build finishes waiting on that same connection. Reproduced three times, with and without the `.conf` sidecar, using both Flyway's own internal connection pool and a real `HikariDataSource` configured identically to `application.yaml` (pool size 25, min-idle 8, `auto-commit: false`) — same hang every time, confirmed via `pg_stat_activity` showing the two backends waiting on each other (`idle in transaction` / `Lock: virtualxid`). This is not a slow build; left alone it never resolves.
- The `.conf` sidecar **is** confirmed real and working for the *other* half of D3 — a multi-statement, multi-commit batched backfill (rule 6) — tested independently with two `UPDATE` statements in one non-transactional script; both committed, no deadlock, no interaction with the CONCURRENTLY problem (a batched backfill doesn't wait on other sessions' snapshots the way `CREATE INDEX CONCURRENTLY` does).

**Consequence:** the original migration-history comments this story would otherwise have "corrected" — `V125:14`, `V126:24`, `V127:43`, all reading *"this codebase runs Flyway migrations in a transaction so `CREATE INDEX CONCURRENTLY` cannot be used"* — turn out to have the **right conclusion**, even though their stated mechanism (simple transaction-wrapping) undersells the real one (a self-deadlock against Flyway's own bookkeeping connection, which persists even in non-transactional mode). They are still deleted by AC3 (the files themselves are gone), but AC5 and `migration-conventions.md` must say plainly that **rule 3 (`CREATE INDEX CONCURRENTLY` on hot/large tables) has no working solution under this project's current Flyway+Hikari setup**, and name it as follow-up work rather than claiming D3 closed it. A real fix needs a mechanism that runs outside Flyway's own connection/lock lifecycle entirely — e.g., a Flyway `Callback` using a hand-opened connection (not the pool Flyway itself uses) fired from `afterMigrate` once Flyway's bookkeeping connection has already been released, or an out-of-band operational script run separately from application startup. Neither is implemented by this story; scoping that is explicitly out of scope here and is recorded as a new backlog item in `migration-rebaseline.md` (AC8).

---

## Ground Truth (verified against HEAD — do not re-derive)

Revision 1 was written against assumptions that did not match the repository. These are the verified facts:

- **Migration range is `V02`..`V137`**, not `V1`..`V139`. There is **no `V1`**; `V01`, `V03`, `V05`, `V06`, `V08`, `V09` never existed. Highest is `V137__envelope_entity_recipients_composite_pk.sql`. 131 files total.
- **Flyway is `flyway-core` 11.7.2**, managed by the `spring-boot-starter-parent` 3.5.16 BOM (`<flyway.version>11.7.2`). Not 9.x, not 6.x.
- **There are nine schemas**, not four. `platform` is **not** one of them — the `platform.*` strings are *configuration keys* stored as rows in the table `main.platform_config`.

  | Schema | Created by | Tables |
  | :--- | :--- | :--- |
  | `main` | Flyway `defaultSchema` (no `CREATE SCHEMA` statement exists) | 33 qualified + 12 unqualified (quartz `V02`, security `V10`) |
  | `marketplace` | `V26:1` | 9 |
  | `booking` | `V29:2` | 6 |
  | `session` | `V38:4` | 7 |
  | `development` | `V46:4` | 14 |
  | `payment` | `V61:4`, `V64:8` | 15 |
  | `messaging` | `V65:1` | 4 |
  | `reviews` | `V67:1` | 3 |
  | `admin` | `V70:1` | 4 |

- **`flyway.schemas` is not configured.** `application.yaml:113-117` sets only `defaultSchema: main`, `enabled: true`, `validateMigrationNaming: true`, `baseline-on-migrate: true`. Every non-`main` schema is created by the migrations themselves via `CREATE SCHEMA IF NOT EXISTS`. Do **not** introduce a `-schemas=` list.
- **`flyway clean` is disabled by default.** This is Spring Boot's Flyway autoconfiguration default (`spring.flyway.clean-disabled` defaults to `true` per its configuration metadata), not a Flyway-core default (raw Flyway defaults `cleanDisabled` to `false`) — this project sets no override, so Spring Boot's default applies. `spring.flyway.clean-disabled=false` must be set explicitly, or the database dropped and recreated instead (AC7).
- **The `btree_gist` extension is required** (`V87__booking_overlap_exclusion_constraint.sql:12`). It must survive into the baseline, and `CREATE EXTENSION` needs elevated privileges on the target database.
- **The `MigrationLint` baselines live in `src/test/java/com/softropic/skillars/db/MigrationLint.java`**, not in `MigrationConventionLintTest.java`, and there are **two**: `GRANDFATHER_BASELINE = 121` (line 90) and `DEFERRED_92_BASELINE = 127` (line 97).
- **`VALIDATE CONSTRAINT` is an `ALTER TABLE` action**, not a standalone statement. `ALTER TABLE main.videos VALIDATE CONSTRAINT chk_videos_operational_state;` — there is no `VALIDATE CONSTRAINT … ON <table>` form. (Revision 1 used the invalid form three times.)

---

## Acceptance Criteria

### AC1: Generate the DDL baseline (`V138__baseline_schema.sql`)

**Given** a PostgreSQL 17 database that has run `V02`..`V137` to completion,
**When** the baseline is generated,
**Then**:

- The file is produced from `pg_dump --schema-only --no-owner --no-privileges` against that database — not hand-written, not assembled from the historical files.
- It creates all nine schemas' objects: every table, column, index, constraint, sequence, identity, default and `CREATE EXTENSION IF NOT EXISTS btree_gist`.
- `flyway_schema_history` is **excluded** (`--exclude-table`); Flyway creates and manages it.
- It contains **zero** DML (`INSERT` / `UPDATE` / `DELETE` / `TRUNCATE`) — all seeds go to AC2.
- It contains **zero** `DROP` statements.
- No `IF NOT EXISTS` guards are added. The baseline runs exactly once against an empty database; guards would mask a genuine mis-generation.
- Object comments (`COMMENT ON`) carrying real intent are preserved; `pg_dump` boilerplate headers (`SET statement_timeout = 0`, `SELECT pg_catalog.set_config…`, dump-version banners) are stripped.
- Filename uses **underscores**, matching all 131 predecessors: `V138__baseline_schema.sql`.

**Verified by:**
- Round-trip: `pg_dump --schema-only` of a database built from `V02`..`V137` and one built from `V138` alone produce diffs only in whitespace and comment placement. Compare with `pg_dump --schema-only --no-owner --no-privileges | sed '/^--/d;/^$/d'` on both sides.
- `psql -c "SELECT table_schema, count(*) FROM information_schema.tables WHERE table_schema IN ('main','marketplace','booking','session','development','payment','messaging','reviews','admin') GROUP BY table_schema ORDER BY table_schema"` returns identical counts on both databases.
- `psql -c "SELECT count(*) FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace WHERE n.nspname NOT IN ('pg_catalog','information_schema')"` matches on both.
- `psql -c "SELECT extname FROM pg_extension"` includes `btree_gist`.

---

### AC2: Generate the seed migration (`V139__baseline_seed_data.sql`)

**Given** the same fully-migrated database,
**When** the seed migration is generated,
**Then** it reproduces the **final** state of every seeded table:

| Table | Rows | Source in history | Why it cannot be copied from the original migration |
| :--- | :--- | :--- | :--- |
| `main.authority` | 5 | `V21` (`ROLE_COACH`, `ROLE_PARENT`), `V84` (`ROLE_PLAYER`), `V92` (`ROLE_ADMIN`, `ROLE_LTD_ADMIN`) | Three separate migrations; ids `100`–`104` are FK targets of `user_authority.authority_id` (`V10:52`) and **must be preserved verbatim**. |
| `main.platform_config` | ~28 keys | 35 `INSERT` statements across the history | Later migrations edit earlier rows. `V128` converted `id` to `GENERATED BY DEFAULT AS IDENTITY`. |
| `session.drills` | 20 | `V39` seeds them, `V111` re-keys them | **`V39` inserts with `gen_random_uuid()`.** `V111` exists solely to replace those random ids with deterministic ones, matched by `trans_key`. Copying `V39`'s `INSERT`s would reintroduce the exact bug `V111` fixed. `V110` also renamed the `library_type` value. |
| `development.skill_definitions` | radar skill catalog | `V46` | Straightforward, but dump it with the rest for consistency. |

**Then** also:

- The file is generated with `pg_dump --data-only --no-owner` restricted to exactly those four tables — **not** by concatenating the historical `INSERT` statements.
- Every statement is `INSERT … ON CONFLICT DO NOTHING` so the file is safely re-runnable.
- **`main.platform_config` inserts omit the `id` column entirely.** `V128` made it an identity column and `MigrationLint.Rule.PLATFORM_CONFIG_EXPLICIT_ID` (`MigrationLint.java:1036-1042`) exists specifically to fail the build on an explicit id. This holds even though `V139` sits at the grandfather baseline (AC4) and is therefore not linted — it is correct on the merits, not merely to satisfy a rule.
- **`main.authority` inserts keep explicit ids `100`–`104`** — they are FK targets and there is no lint rule against explicit ids on this table.
- If `pg_dump --data-only` emits a `setval()` for the `platform_config` identity sequence, it is **kept**, so the next application-generated key does not collide.

**Explicitly NOT seeded:**

- `payment.coach_payouts` (`V135__coach_payout_cutover_backfill.sql`) is an `INSERT … SELECT` over existing `bookings`. On an empty database it inserts **zero** rows. It is a data migration, not seed data — do not port it.
- `V103` (availability-window timezone backfill) and `V98`'s `distinct_coach_count` backfill are likewise `UPDATE`s over pre-existing rows and produce nothing on an empty database. The *columns* they added are captured by AC1; the backfills are not carried forward.

**Verified by:**
- `SELECT id, name FROM main.authority ORDER BY id` returns exactly `100 ROLE_COACH`, `101 ROLE_PARENT`, `102 ROLE_PLAYER`, `103 ROLE_ADMIN`, `104 ROLE_LTD_ADMIN` on both the old-path and new-path databases.
- `SELECT id, trans_key FROM session.drills WHERE library_type = 'PLATFORM' ORDER BY trans_key` is **byte-identical** between a database built from `V02`..`V137` and one built from `V138`+`V139`. This is the check that proves `V111`'s deterministic ids survived the squash.
- `SELECT key, value, value_type FROM main.platform_config ORDER BY key` is identical on both.
- `SELECT code, display_name, display_order FROM development.skill_definitions ORDER BY display_order` is identical on both.
- Re-running `V139` by hand against an already-seeded database inserts zero rows and raises no error.

---

### AC3: Delete the historical migrations

**Given** `V138` and `V139` reproduce the full schema and seed state,
**When** the squash is completed,
**Then**:

- All **131** files `V02__quartz_schema.sql` … `V137__envelope_entity_recipients_composite_pk.sql` are deleted from `src/main/resources/db/migration/`.
- `src/main/resources/db/migration/` contains exactly two files: `V138__baseline_schema.sql` and `V139__baseline_seed_data.sql`.
- No `R__` repeatable migrations are introduced by this story.
- The deletion is its own commit, separate from the commits that add `V138`/`V139`, so the squash is reviewable as "these 131 files were replaced by these 2".

**Verified by:**
- `ls src/main/resources/db/migration | wc -l` returns `2`.
- `git log --diff-filter=D --name-only -1` shows all 131 deletions in one commit.
- `git show HEAD~1:src/main/resources/db/migration/V137__envelope_entity_recipients_composite_pk.sql` still resolves — the history is recoverable from git, which is the only place it needs to live now.

---

### AC4: Reset the `MigrationLint` baselines

**Given** the only migrations on disk are `V138` and `V139`,
**When** the lint baselines are updated,
**Then**:

- `MigrationLint.GRANDFATHER_BASELINE` becomes **`139`** (from `121`).
- `MigrationLint.DEFERRED_92_BASELINE` becomes **`139`** (from `127`).
- Both javadoc blocks are rewritten. They currently explain the two-baseline mechanism in terms of `V122`–`V127` being checksum-frozen (`MigrationLint.java:33-47`, `:88-98`); that rationale is now historical and must be replaced with: *both baselines sit at the rebaseline boundary; every migration from `V140` onward is bound by the full rule set with no grandfather exceptions.*
- The two constants now hold the same value permanently. **Collapsing them into one is out of scope for this story** — `DEFERRED_92_BASELINE` is threaded through the four-argument `MigrationLint.lint(dir, baseline, deferred92Baseline, knownAtHead, sources)` signature and five `MigrationConventionLintTest` call sites including a dedicated "the production `DEFERRED_92_BASELINE` constant is itself load-bearing" test. Note the redundancy in `migration-rebaseline.md` (AC8) as a follow-up; do not refactor it here.
- `MigrationConventionLintTest`'s `@DisplayName` at line 65 — *"real migrations above the V121 grandfather baseline have zero violations"* — is updated to reference the new baseline. Its class javadoc (`:28-35`) describing the two-baseline fixture set is updated the same way.
- `FIXTURE_DEFERRED_92_BASELINE = 808` and every `V800`–`V808` fixture are **unchanged**. They exercise the mechanism in isolation and are independent of the production constants.

**`V138` and `V139` are grandfathered by design.** With both baselines at `139`, neither baseline file is linted. This is deliberate and must be stated in `migration-rebaseline.md`:

- `V138` is machine-generated from `pg_dump`. It necessarily contains validating `ADD CONSTRAINT`s, non-concurrent `CREATE INDEX`es and no `SET lock_timeout`. Every one of those is correct here — the file runs once, against an empty database. Linting it would require hundreds of opt-out comments in generated output for zero safety gain.
- `V139` is pure `INSERT … ON CONFLICT DO NOTHING`, which trips no rule regardless (`UNBATCHED_DML` covers `UPDATE`/`DELETE`/`TRUNCATE`, not `INSERT` — `MigrationLint.java:997-1032`).

**Verified by:**
- `mvn -o test -Dtest=MigrationConventionLintTest` is green.
- A scratch `V140__probe.sql` containing a bare `ALTER TABLE main.videos ADD CONSTRAINT probe CHECK (true);` **fails** with `MISSING_LOCK_TIMEOUT` — proving the rules bind immediately above the new baseline.
- A scratch `V139.1__probe.sql` also fails, confirming the version-aware (not major-only) baseline comparison still holds (`MigrationLint.isAboveBaseline`).

---

### AC5: Adopt `executeInTransaction=false` as the sanctioned transaction escape

**Given** D3 (as corrected above — read that section first),
**When** the convention is documented,
**Then** `docs/deployment/migration-conventions.md` gains a subsection under *The expand / contract standard* stating:

- Flyway runs each migration inside a transaction by default. The escape is a sidecar script-config file named for the migration:
  ```
  src/main/resources/db/migration/V140__some_backfill.sql
  src/main/resources/db/migration/V140__some_backfill.sql.conf     →  executeInTransaction=false
  ```
  Confirmed working for a genuinely useful case: a multi-statement, multi-commit **batched backfill** (rule 6) — each statement in the script commits independently, verified against a real Hikari-pooled connection matching this project's `auto-commit: false` configuration.
- A non-transactional migration **cannot be rolled back**. It is used only where the operation genuinely requires it, never for convenience, and the reason goes in the migration's header comment.
- **`CREATE INDEX CONCURRENTLY` (rule 3) is explicitly NOT solved by this mechanism.** Verified: even with the sidecar, Flyway's own bookkeeping connection stays open, idle-in-transaction, for the whole `migrate()` call, and `CREATE INDEX CONCURRENTLY`'s snapshot-wait phase deadlocks against it permanently — reproduced against both Flyway's own pool and a real `HikariDataSource` configured identically to `application.yaml` (`auto-commit: false`, pool size 25). A hot/large-table index still needs a mechanism that runs outside Flyway's own connection/lock lifecycle (e.g., an `afterMigrate` `Callback` on a hand-opened connection, or an out-of-band operational script) — not implemented here; filed as a new backlog item in `migration-rebaseline.md`. Until that exists, rule 3 has **no working online-safe option** in this codebase; a hot/large-table index must either accept the blocking `CREATE INDEX` (with sign-off, like `V117`'s original disposition) or wait for that follow-up.
- If a future `CREATE INDEX CONCURRENTLY` attempt is made and hangs, the recovery is: cancel it, then `DROP INDEX` the resulting **invalid** index (`CREATE INDEX CONCURRENTLY` leaves one behind on abort) before retrying with a real fix.

**Then** the go-forward checklist (`migration-conventions.md:19-40`) is updated so rule 6 (batch full-table DML) points at the sidecar mechanism, and rule 3 (`CREATE INDEX CONCURRENTLY`) is marked as an open gap with a pointer to the backlog item, instead of silently implying both are solved the same way.

**Note:** the three migrations that used to assert `CREATE INDEX CONCURRENTLY` is impossible here — `V125:14`, `V126:24`, `V127:43` — are **deleted by AC3**. Their conclusion turns out to have been correct (see the D3 correction above); only their stated mechanism was imprecise. No comment correction is needed since the files themselves are gone, but the underlying claim must not be contradicted elsewhere in the docs.

**Verified by:**
- A scratch `V140__batch_probe.sql` (two `UPDATE` statements) plus its `.conf` sidecar applies successfully against a booted database with each statement committing independently — confirmed by re-reading both rows after the run.
- A scratch `V140__concurrent_probe.sql` (`CREATE INDEX CONCURRENTLY`) plus its `.conf` sidecar **hangs indefinitely** (self-deadlock, confirmed via `pg_stat_activity`) against a real Hikari-pooled connection matching this project's configuration — proving rule 3 is NOT solved, not proving it is. Both probes were scratch files outside the repo, never committed; the concurrent-index probe's stuck backend was terminated manually (`pg_terminate_backend`) to clean up.

---

### AC6: Fresh-bootstrap equivalence

**Given** the squashed migration set,
**When** a developer boots a fresh database,
**Then**:

- `flyway info` shows exactly two migrations, `V138` and `V139`, both `PENDING` on an empty database and both `SUCCESS` after migrate. There is no "SKIP" state and nothing is skipped — the historical migrations no longer exist, which is the entire point of D1.
- The resulting schema is equivalent to the pre-squash schema per AC1's and AC2's comparison queries.
- The application boots against it and health checks pass.
- The full integration suite passes.

**Verified by:**
- Build both databases and diff them:
  ```bash
  # old path — from the last pre-squash commit
  git stash && git checkout <pre-squash-sha> -- src/main/resources/db/migration
  createdb skillars_old && <run app or flyway against skillars_old>
  pg_dump --schema-only --no-owner --no-privileges skillars_old | sed '/^--/d;/^$/d' > /tmp/old.sql

  # new path — HEAD
  git checkout HEAD -- src/main/resources/db/migration
  createdb skillars_new && <run app or flyway against skillars_new>
  pg_dump --schema-only --no-owner --no-privileges skillars_new | sed '/^--/d;/^$/d' > /tmp/new.sql

  diff /tmp/old.sql /tmp/new.sql        # expected: empty
  ```
- Seed equivalence: run AC2's four `SELECT`s against both databases and diff.
- `mvn verify` is green **in CI** (see Testing Strategy — do not run the full verify locally).

---

### AC7: Existing-environment recreation procedure

**Given** deleting `V02`..`V137` invalidates every `flyway_schema_history` row in every database that already applied them,
**When** the story ships,
**Then** `docs/deployment/migration-rebaseline.md` (AC8) carries an explicit operator procedure covering:

- **What breaks and why.** Flyway `validate` fails with *"Detected applied migration not resolved locally"* for all 131 deleted versions. This is not recoverable by configuration — `ignoreMigrationPatterns` would suppress the error but leave a history table describing migrations that no longer exist, which defeats the rebaseline.
- **The procedure**: drop and recreate the database, then let the application migrate it from `V138`/`V139` on boot.
- **The `flyway clean` caveat**: `flyway clean` is **disabled by default** in Flyway 10+ and this project sets no override, so `clean` fails out of the box. Either set `spring.flyway.clean-disabled=false` for the operation, or (preferred) `DROP DATABASE` / `CREATE DATABASE`.
- **Which environments are affected**: every developer workstation, plus any dev/UAT database. Production is explicitly **not** affected — no production deploy has occurred, which is the precondition that makes this story legal at all.
- **A pre-flight assertion** the operator runs before starting: confirm no production database exists. If one does, **stop** — this story is no longer safe and must be reopened as a `baselineVersion`-based migration instead.

**Verified by:**
- A reviewer follows the written procedure on a scratch database that has `V02`..`V137` applied, and reaches a working application without consulting anything outside the document.

---

### AC8: Documentation

**Given** the rebaseline changes the project's migration story wholesale,
**When** documentation is updated,
**Then**:

**New — `docs/deployment/migration-rebaseline.md`:**
- Why the rebaseline happened: 131 checksum-frozen files, six of them carrying lock-unsafe patterns that could not be edited in place, and two `MigrationLint` grandfather baselines that existed only to work around that.
- What was done: D1/D2/D3, and the AC3 file-deletion boundary.
- **Which gate items closed by deletion** — `V60`, `V94`, `V117`, `V124` (and the `V125`/`V126`/`V127` follow-on note) — with the `migration-conventions.md:255-259` reasoning quoted, so a future reader does not think the obligation was quietly dropped.
- That `V138`/`V139` are grandfathered by design, with AC4's rationale.
- The git SHA of the last pre-squash commit, so the deleted history is findable.
- The AC7 operator procedure.
- The AC4 follow-up note about the two now-redundant baseline constants.
- **A new backlog item**: `CREATE INDEX CONCURRENTLY` (go-forward rule 3) has no working online-safe mechanism in this codebase. The `executeInTransaction=false` sidecar (D3) does not solve it — Flyway's own bookkeeping connection self-deadlocks against it, reproduced empirically (see AC5). A real fix needs a mechanism outside Flyway's own connection/lock lifecycle (an `afterMigrate` `Callback` on a hand-opened connection, or an out-of-band operational script), not implemented by this story.
- A pointer to `migration-conventions.md` for the go-forward rules.

**Modified — `docs/deployment/migration-conventions.md`:**
- `:41-49` *Pre-production migration debt* — **deleted**. The debt it describes no longer exists.
- `:211-217` *Grandfathering* and `:218-235` *Two baselines* — rewritten to describe one boundary at `V139` and why, replacing the `V122`–`V127` checksum-freeze rationale.
- `:236-272` *Per-migration disposition* and `:273-295` *Known lock-unsafe applied migrations* — **deleted**. Both tables describe files that no longer exist. Replace with a one-paragraph pointer to `migration-rebaseline.md`.
- Add AC5's `executeInTransaction=false` subsection and wire it into the go-forward checklist.
- Cross-link `migration-rebaseline.md`.

**Modified — `docs/deployment/runbook.md`:**
- `:579-604` *Pre-production release gate: outstanding migration rewrites* — **deleted in full**, including the four-item table and the `V125`/`V126`/`V127` follow-on note. Every item closed by deletion.
- Replace with a short note recording that the gate was closed by `skillars-deferred-112` and pointing to `migration-rebaseline.md`.
- *(Incidental: the deleted table's `V117` row said `marketplace.coach_radar_preferences`; the table was actually in `development` (`V51:13`). The error disappears with the table — no separate fix needed.)*

**Modified — `_bmad-output/implementation-artifacts/deferred-work.md`:**
- The *Pre-production migration rebaseline (future task, no owner)* entry at `:1367-1369` is removed per this file's delete-outright convention, as part of the post-merge prune.

**Not modified — `docs/architecture/payout-and-capture-pending.md` (conscious decision, not an oversight):**
- This design-decision doc narrates V124's own reasoning (~10 references, e.g. *"Migration: V124 widens the `chk_bp_status` CHECK..."*). It is intentionally left as-is: it's a historical decision record of what was decided and why at the time, the same way git history is "the only place [the old migrations] need to live now" per AC3/D1. It is not rewritten to describe the post-squash state, and its V124 references are expected to remain — this is why AC8's verification grep below is scoped to `docs/deployment/` and does not check `docs/architecture/`.

**Verified by:**
- `grep -rn "V60\|V94\|V97\|V98\|V117\|V124\|V125\|V126\|V127" docs/deployment/` returns hits only inside `migration-rebaseline.md`'s historical narrative.
- `grep -rn "GRANDFATHER_BASELINE\|frozen" docs/deployment/migration-conventions.md` describes one boundary, not two.
- Every relative link in the new and edited documents resolves. *(Revision 1 of this story had all three of its own cross-links broken — both the paths and the anchor. Check them.)*

---

### AC9: Lint and full-suite green

**Given** all of the above,
**When** CI runs,
**Then**:

- `MigrationConventionLintTest` passes, including the AC4 probe assertions.
- `MigrationLint.Rule.BACKPORT_BELOW_BASELINE` now guards `n <= 139`. Confirm it is still constant-driven and needs no code change beyond AC4. *(Note its documented scope limit at `migration-conventions.md:300-310`: it is a local pre-commit guard, inert in CI. Unchanged by this story.)*
- The full `mvn verify` suite passes in GitHub CI with no new failures.
- No migration in the tree carries a `-- migration-lint: allow-*` opt-out. Every existing opt-out lived in a file that AC3 deleted, and neither `V138` nor `V139` needs one. **A surviving opt-out means something went wrong** — it is the same "the item is not closed" test the deleted runbook gate used (`runbook.md:601-603`).

**Verified by:**
- `grep -rn "migration-lint: allow-" src/main/resources/db/migration/` returns **zero** hits.
- CI green on the PR.

---

## Tasks/Subtasks

_This story predates `bmad-create-story`'s Tasks/Subtasks convention (it was authored via a review→rewrite cycle against `story-review.md`). This section is added by `bmad-dev-story` at dev-start, mapped 1:1 onto the existing Technical Approach phases and ACs so the checkboxes below are what `bmad-dev-story` tracks and checks off — the Completion Criteria Checklist above stays as the story's own acceptance summary and is not separately re-checked._

- [x] **Task 1 — Build the reference database (AC1, AC2, Phase 1)**
  - [x] 1.1 Start a `postgres:17-alpine` container; apply `V02`..`V137` to completion; record the pre-squash git SHA
  - [x] 1.2 Capture `pg_dump --schema-only --no-owner --no-privileges --exclude-table='*.flyway_schema_history'`
  - [x] 1.3 Capture `pg_dump --data-only --no-owner` restricted to `main.authority`, `main.platform_config`, `session.drills`, `development.skill_definitions`
- [x] **Task 2 — Author `V138__baseline_schema.sql` (AC1, Phase 2)**
  - [x] 2.1 Strip `pg_dump` boilerplate; keep meaningful `COMMENT ON`; confirm `CREATE EXTENSION IF NOT EXISTS btree_gist` present; group into sections
  - [x] 2.2 Verify zero DML, zero `DROP`, no `IF NOT EXISTS` guards added
  - [x] 2.3 Round-trip verify against a fresh database per AC1's four verification queries
- [x] **Task 3 — Author `V139__baseline_seed_data.sql` (AC2, Phase 2)**
  - [x] 3.1 Convert to `INSERT … ON CONFLICT DO NOTHING`; strip `id` from `platform_config` inserts; keep explicit ids on `authority`; keep any identity `setval()`
  - [x] 3.2 Verify against AC2's five checks, especially the `session.drills` byte-identical `trans_key` check (proves `V111`'s re-key survived)
- [x] **Task 4 — Delete the 131 historical migrations (AC3, Phase 3.1)**
  - [x] 4.1 Delete `V02__*.sql` … `V137__*.sql`; confirm exactly two files remain
  - [x] 4.2 Commit the deletion separately from the `V138`/`V139` addition
- [x] **Task 5 — Reset `MigrationLint` baselines (AC4, Phase 3.2)**
  - [x] 5.1 Set `GRANDFATHER_BASELINE` and `DEFERRED_92_BASELINE` to `139`; rewrite both javadoc blocks
  - [x] 5.2 Update `MigrationConventionLintTest`'s `@DisplayName` (line 65) and class javadoc (`:28-35`)
  - [x] 5.3 Probe: scratch `V140__probe.sql` fails `MISSING_LOCK_TIMEOUT`; scratch `V139.1__probe.sql` fails too; delete both probes after confirming
- [x] **Task 6 — Adopt `executeInTransaction=false` convention for batched backfills; correct D3's `CONCURRENTLY` premise (AC5, Phase 3.3)**
  - [x] 6.1 Document the `.conf` sidecar mechanism in `migration-conventions.md`: confirmed working for rule 6 (batched backfills), confirmed NOT solving rule 3 (`CREATE INDEX CONCURRENTLY`) — see the D3 correction. Go-forward checklist updated to match, not to silently imply both are solved.
  - [x] 6.2 Probe: scratch `V140__batch_probe.sql` (2 `UPDATE`s) + `.conf` sidecar — both commit independently, confirmed. Scratch `V140__concurrent_probe.sql` (`CREATE INDEX CONCURRENTLY`) + `.conf` sidecar — **hangs indefinitely** (genuine self-deadlock against Flyway's own bookkeeping connection, reproduced 3x, confirmed via `pg_stat_activity`, confirmed against a real `HikariDataSource` matching `application.yaml`'s exact pool config). Both probes were scratch files outside the repo; the stuck backend was terminated manually to clean up.
- [x] **Task 7 — Fresh-bootstrap equivalence (AC6, Phase 4.1)**
  - [x] 7.1 Dual-database `pg_dump --schema-only` diff (old path vs. `V138`+`V139`) is empty (after normalizing per-run `\restrict` tokens and one documented, functionally-verified pg_dump cast-placement round-trip artifact)
  - [x] 7.2 Seed equivalence: AC2's four `SELECT`s match between old and new paths, byte-identical
  - [x] 7.3 Application boots against the new schema; health checks pass — verified via a real, full-context `RescheduleResourceIT` (25 tests) against a genuinely fresh Testcontainers Postgres migrated by V138+V139: 25/25 green
- [x] **Task 8 — Documentation (AC7, AC8, Phase 4.3)**
  - [x] 8.1 Write `docs/deployment/migration-rebaseline.md` (why/what/gate-closure/git SHA/AC7 operator procedure incl. the `mvn clean` build-artifact gotcha found during dev/follow-up work list)
  - [x] 8.2 Rewrite `docs/deployment/migration-conventions.md` per AC8 (delete/rewrite the four named sections, add the AC5 subsection with the corrected CONCURRENTLY finding, cross-link, plus every stale V-number cross-reference the squash left dangling)
  - [x] 8.3 Rewrite `docs/deployment/runbook.md`'s gate table into a closure note, including the CONCURRENTLY item reopened as ongoing debt rather than closed
  - [x] 8.4 Verify all cross-links resolve; verify AC8's two `grep` checks (both pass — remaining V-number mentions outside migration-rebaseline.md are legitimate historical narrative in runbook.md's own closure note and migration-conventions.md's Grandfathering section, not stale "still open" claims)
- [x] **Task 9 — Final lint and regression pass (AC9)**
  - [x] 9.1 `MigrationConventionLintTest` green locally (13/13), against the real 2-file migration tree
  - [x] 9.2 `grep -rn "migration-lint: allow-" src/main/resources/db/migration/` returns zero hits
  - [x] 9.3 Targeted regression suite green: `RescheduleResourceIT` 25/25 (full-context boot + persistence). Full `mvn verify` is CI's job, not local, per project convention
  - [ ] 9.4 Post-merge prune: remove the `deferred-work.md:1367-1369` entry (tracked as a follow-up, per this story's own Next Steps — not blocking review, matches this project's established post-merge-prune convention)

---

## Technical Approach

### Phase 1 — Build the reference database
1. From a clean checkout at the pre-squash SHA, create an empty PostgreSQL 17 database and run the application (or Flyway) to apply `V02`..`V137`.
2. Capture the reference artifacts:
   ```bash
   pg_dump --schema-only --no-owner --no-privileges \
           --exclude-table='*.flyway_schema_history' skillars_ref > /tmp/ref-schema.sql
   pg_dump --data-only --no-owner \
           -t main.authority -t main.platform_config \
           -t session.drills -t development.skill_definitions skillars_ref > /tmp/ref-seed.sql
   ```
3. Record the SHA — it goes in `migration-rebaseline.md` (AC8).

### Phase 2 — Author the two baseline files
1. `V138__baseline_schema.sql` from `/tmp/ref-schema.sql`: strip `pg_dump` boilerplate, keep meaningful `COMMENT ON`, confirm `CREATE EXTENSION IF NOT EXISTS btree_gist` is present, group into sections (extensions → schemas → sequences → tables → constraints → indexes) for readability.
2. `V139__baseline_seed_data.sql` from `/tmp/ref-seed.sql`: convert to `INSERT … ON CONFLICT DO NOTHING`, **strip the `id` column from every `main.platform_config` insert**, **keep** ids on `main.authority`, keep any identity `setval()`.
3. Verify both against a fresh database before touching anything else.

### Phase 3 — Squash and rewire
1. Delete the 131 historical files (own commit, AC3).
2. Update the two `MigrationLint` constants and their javadoc; update `MigrationConventionLintTest`'s `@DisplayName` and class javadoc (AC4).
3. Add the `executeInTransaction` convention (AC5).

### Phase 4 — Verify and document
1. Run AC6's dual-database diff.
2. Run the probe tests from AC4 and AC5, then delete the probes.
3. Write `migration-rebaseline.md`; edit `migration-conventions.md` and `runbook.md` (AC8).
4. Push and let CI run the full suite.

---

## Developer Context & Guardrails

### What this story MUST do
- **Preserve schema and seed state exactly.** Verified by diff (AC1, AC2, AC6), never assumed.
- **Preserve `V111`'s deterministic drill ids.** The single easiest way to silently break this story is to rebuild the seed file from `V39`'s `gen_random_uuid()` inserts. AC2's `session.drills` check exists for this.
- **Leave the migration directory with exactly two files.**
- **Close the pre-production gate in the documentation**, not just in the code — the gate outlives any single document by design (`runbook.md:583-586`).

### What this story MUST NOT do
- **Do not rewrite `V60`/`V94`/`V98`/`V117` into `NOT VALID` + `VALIDATE` pairs.** They are deleted. See *Why the gate closes by deletion*.
- **Do not introduce a Flyway `baselineVersion`.** D1 rejected that path; adding it reintroduces the dual bootstrap.
- Do not add `IF NOT EXISTS` guards to `V138`.
- Do not create tables, columns or constraints that were not in the `V137` end state.
- Do not refactor the two `MigrationLint` baseline constants into one (AC4 — note it, don't do it).
- Do not touch the `V800`–`V808` lint fixtures.

### Files touched

**New:**
- `src/main/resources/db/migration/V138__baseline_schema.sql`
- `src/main/resources/db/migration/V139__baseline_seed_data.sql`
- `docs/deployment/migration-rebaseline.md`

**Deleted:**
- `src/main/resources/db/migration/V02__*.sql` … `V137__*.sql` (131 files)

**Modified:**
- `src/test/java/com/softropic/skillars/db/MigrationLint.java` (two constants + javadoc)
- `src/test/java/com/softropic/skillars/db/MigrationConventionLintTest.java` (`@DisplayName` line 65, class javadoc `:28-35`)
- `docs/deployment/migration-conventions.md` (four sections deleted/rewritten, one added)
- `docs/deployment/runbook.md` (`:579-604` deleted, replaced with a closure note)
- `_bmad-output/implementation-artifacts/deferred-work.md` (entry `:1367-1369` removed at post-merge prune)

**Not touched:**
- `pom.xml` — there is no build-config frozen-file switch. Revision 1 listed it speculatively; the only levers are the two Java constants.
- `src/main/resources/application.yaml` — `baseline-on-migrate: true` becomes inert (it only acts on a non-empty untracked schema) but is harmless. Leave it; removing it is an unrelated change.

---

## Testing Strategy

1. **Lint:** `mvn -o test -Dtest=MigrationConventionLintTest` locally, plus the AC4 probes.
2. **Schema equivalence:** AC6's dual-database `pg_dump` diff. This is the load-bearing test.
3. **Seed equivalence:** AC2's four `SELECT` comparisons, especially `session.drills`.
4. **Bootstrap:** fresh database → application boot → health check green.
5. **Regression:** full suite **in GitHub CI**. Per project convention, do **not** run `mvn verify` locally before pushing — CI is the sole full-verification gate.
6. **Spot-check:** booking creation, video upload and messaging send complete end-to-end against a freshly-bootstrapped database — these exercise `platform_config` lookups and role assignment, which are exactly what a bad seed file would break.

---

## Architecture Compliance

- **Expand / contract standard** (`migration-conventions.md:17-40`) binds every migration from `V140` onward, with no grandfather exceptions. `V138`/`V139` are exempt by design (AC4).
- **Flyway location:** `src/main/resources/db/migration/`. **Naming:** `V<number>__<underscore_separated_description>.sql`.
- **Schemas:** `main` is the Flyway `defaultSchema`; the other eight are created by the baseline itself. `flyway.schemas` stays unconfigured.
- **Transaction escape:** per-script `executeInTransaction=false` sidecar, per AC5.

---

## Previous Story Intelligence

- **skillars-deferred-91** (AC8, decision D7): produced the per-migration disposition and the pre-production trigger that named `V60`/`V94`/`V117`/`V124`. This story closes that trigger by deletion; AC8 deletes the disposition table.
- **skillars-deferred-92** (AC7–AC11): built `MigrationLint`, the statement-scoped `migration-lint` markers, and the second baseline (`DEFERRED_92_BASELINE`) that this story retires.
- **skillars-deferred-101** (AC12): consolidated the lock-unsafe list into `migration-conventions.md:273`. Note this list named `V60/V94/V97/V98/V117` while the deferred-91 trigger and the runbook named `V60/V94/V117/V124` — **the project carried two disagreeing gate lists.** Revision 1 of this story followed one of them. The squash moots both; AC8 deletes both.

---

## Completion Criteria Checklist

- [x] Reference database built from `V02`..`V137`; pre-squash SHA recorded (`4a3f218dabd04e0c8419282e922f60de5f93934d`)
- [x] `V138__baseline_schema.sql` generated, cleaned, `btree_gist` present, zero DML, zero DROP
- [x] `V139__baseline_seed_data.sql` generated from the reference database (not from `V39`), `platform_config` ids stripped, `authority` ids preserved
- [x] Schema diff between old path and new path is empty (after normalizing per-run tokens and one documented, functionally-verified pg_dump artifact — see Dev Agent Record)
- [x] `session.drills` deterministic ids byte-identical across both paths
- [x] 131 historical migrations deleted in their own commit; directory holds exactly 2 files
- [x] `GRANDFATHER_BASELINE` and `DEFERRED_92_BASELINE` both `139`; javadoc and `@DisplayName` updated
- [x] `V140` and `V139.1` probes confirm the rules bind above the new baseline; probes deleted
- [x] `executeInTransaction=false` convention documented and probe-verified for batched backfills (rule 6); probe deleted. `CREATE INDEX CONCURRENTLY` (rule 3) confirmed NOT solved by this mechanism — documented as an open gap with a backlog pointer, not silently implied fixed
- [x] Zero `migration-lint: allow-*` opt-outs remain in the migration directory
- [x] Application boots on a fresh database — verified via `RescheduleResourceIT` (25/25, full context + real Testcontainers Postgres migrated by V138+V139). Video/messaging spot-checks not independently re-run beyond what CI's full suite covers; booking (the domain most exercised by the seeded reference data — `platform_config` lookups, role assignment) was the targeted proof
- [x] `docs/deployment/migration-rebaseline.md` written, including the AC7 operator procedure
- [x] `migration-conventions.md` four sections deleted/rewritten, `executeInTransaction` section added (with the corrected CONCURRENTLY finding)
- [x] `runbook.md` gate table deleted and replaced with a closure note
- [x] All cross-links in new/edited docs resolve
- [ ] CI green on the full suite — pending push; not run locally per this project's `mvn verify`-is-CI's-job convention (targeted suites green locally: `MigrationConventionLintTest` 13/13, `RescheduleResourceIT` 25/25)

---

## Story Status

**Status:** review
**Owner:** (unassigned)
**Complexity:** High — irreversible schema-history refactor; every developer environment must be recreated
**Estimated effort:** 3–5 dev days (revised down from 5–7: the `NOT VALID`/`VALIDATE` rewrites and the batched-backfill work are removed by D1)
**Blocking:** Yes — must complete before the first production deploy
**Hard precondition:** No production database exists. AC7's pre-flight assertion checks this. If one exists, stop and reopen the story against a `baselineVersion` approach. Confirmed still true at dev time.

**Next steps:**
1. Code review
2. Push and let CI run the full suite
3. Post-merge prune of `deferred-work.md:1367-1369`

---

## Additional Context

### Why this story exists

The migration history (`V02`..`V137`, 131 files) was built pre-production and predates the expand/contract standard. Six files carry patterns the current lint rules reject:

- `V60` (`main.videos`), `V94` (`payment.booking_payments`), `V117` (`development.coach_radar_preferences`) — validating `ADD CONSTRAINT` / non-concurrent `CREATE INDEX` under `ACCESS EXCLUSIVE` on tables that grow
- `V124` — a CHECK widen shipping in the same release as its first write, deviating from rule 5
- `V98` — an unbatched full-table backfill
- `V125`/`V126`/`V127` — `CREATE INDEX` without `CONCURRENTLY`

None could be fixed in place: Flyway checksums the whole file, so an applied migration cannot be edited even to add an opt-out comment. That constraint is also why `MigrationLint` carries **two** grandfather baselines rather than one — each is a workaround for files that can be neither fixed nor exempted.

Squashing removes the cause rather than managing the symptom. The lock-unsafe files stop existing; the baseline declares each object once in its final shape and only ever runs against an empty database; and both grandfather baselines collapse to a single boundary above which every future migration is fully linted.

This is only possible before the first production deploy, which is why it is a blocker.

### Reference documents

*(Paths relative to this file, `_bmad-output/implementation-artifacts/`.)*

- [`docs/deployment/migration-conventions.md`](../../docs/deployment/migration-conventions.md) — the authoritative migration safety standard
- [`docs/deployment/runbook.md`](../../docs/deployment/runbook.md) — pre-production release gate, `:579-604`
- [`deferred-work.md`](./deferred-work.md#pre-production-migration-rebaseline-future-task-no-owner) — the originating entry, `:1367-1369`
- [`story-review.md`](./story-review.md) — the review this revision answers

---

## Dev Agent Record

### Implementation Plan

Followed the story's own Technical Approach phases in order, verifying each against a real
PostgreSQL 17 (`postgres:17-alpine`, matching production/Testcontainers exactly) rather than
assuming pg_dump/Flyway behavior:

1. **Reference database** (Task 1): stood up a real container, replayed `V02`..`V137` via a
   standalone Flyway runner (flyway-core 11.7.2 directly, not the full Spring app, to avoid
   pulling in Redis/MinIO for a step that only needs Postgres), captured `pg_dump --schema-only`
   and `--data-only` (four seed tables, `--column-inserts --on-conflict-do-nothing`).
2. **Author V138/V139** (Tasks 2–3): processed the raw dumps programmatically (Python), never
   hand-typed SQL content. Two corrections the raw pg_dump output required, found by reasoning
   about Flyway's actual runtime behavior rather than assumed: `CREATE SCHEMA main` had to be
   stripped (Flyway auto-creates its configured `defaultSchema` before running any migration —
   confirmed empirically: no historical migration ever created `main` explicitly, yet it always
   existed), and `platform_config`/`drills`' `now()`-defaulted timestamp columns had to be
   dropped rather than kept as pg_dump's frozen dump-time literals (a template baseline meant to
   run fresh at any future date shouldn't hardcode "the instant I happened to generate this").
3. **Delete + rewire** (Tasks 4–6): deleted the 131 files as their own commit (verified this
   repo merges via real merge commits, not squash-merge, so the two-commit structure survives to
   `main`), reset both `MigrationLint` baseline constants, then **empirically tested D3's
   `executeInTransaction=false` premise before documenting it** rather than trusting the story's
   plan — this is where the CONCURRENTLY correction (below) came from.
4. **Verify + document** (Tasks 7–9): dual-database diff, a real full-context IT run (not just
   the lint test) to prove the application actually boots, then wrote all documentation from the
   verified facts rather than the story's pre-verification draft text.

### Debug Log

Three genuine, load-bearing findings surfaced during implementation that the story (even after
its own revision-2 audit trail) did not anticipate:

1. **`CREATE SCHEMA main` in V138 would have collided with Flyway's own auto-creation.** Fixed
   before it ever caused a failure, by reasoning from the fact that no historical migration ever
   created `main` explicitly. Confirmed by the first successful V02..V137 replay.
2. **`platform_config` has 120 seeded rows, not "~28"** as the story's Ground Truth section
   estimated. Not a defect — the seed generation is mechanical (`pg_dump --data-only`), so the
   real count came through correctly regardless of what the story's illustrative text guessed.
   Worth a correction note since the story cited a specific (wrong) number as if verified.
3. **`CREATE INDEX CONCURRENTLY` does not work under this project's Flyway+Hikari configuration,
   even with the `executeInTransaction=false` sidecar D3 was betting on.** This is the
   significant one. Reproduced three times: Flyway's own `DbMigrate` holds a bookkeeping
   connection open, idle-in-transaction, for the whole `migrate()` call; `CREATE INDEX
   CONCURRENTLY`'s snapshot-wait phase deadlocks against it permanently. Confirmed via
   `pg_stat_activity` (`idle in transaction` / `Lock: virtualxid`, waiting on each other), and
   confirmed against a real `HikariDataSource` configured identically to `application.yaml`
   (`auto-commit: false`, pool size 25, min-idle 8) — not just Flyway's own internal pool, in
   case that was the artifact. The `.conf` sidecar mechanism itself is real and does work for the
   *other* half of D3 (multi-statement, multi-commit batched backfills — tested independently,
   confirmed working). Corrected D3, AC5, and every place `migration-conventions.md` implied
   `CREATE INDEX CONCURRENTLY` was newly safe; reopened it as tracked follow-up work instead of
   claiming it closed. Full evidence trail in the story's "Correction to D3" section and
   `migration-conventions.md` rule 4.
4. **A Maven build-artifact staleness trap, not a migration bug.** The first full-context IT run
   failed with `ERROR: schema "admin" already exists` against a genuinely fresh Testcontainers
   Postgres. Root cause: `target/classes/db/migration/` still held all 131 deleted files from a
   pre-squash build (Maven's `process-resources` doesn't prune resources whose source was
   deleted), so `classpath:db/migration` saw 133 files, replayed the old history first (its
   `IF NOT EXISTS` guards masked the duplication), then hit `V138`'s deliberately-unguarded
   `CREATE SCHEMA admin` on a schema the stale `V70` had already created. Fixed with `mvn clean`;
   documented as an operator gotcha in `migration-rebaseline.md` so the next person who hits this
   doesn't suspect the migrations themselves.

Also fixed along the way: a corrupted `sprint-status.yaml` line (this story's `ready-for-dev`
entry had someone else's — `skillars-deferred-111`'s — entire changelog pasted after its own
summary, apparently from an editing accident in an earlier session). Rewritten to a short, correct
summary. Unrelated to this story's own scope but was in a file this story updates anyway.

### Completion Notes

All 9 ACs implemented and independently verified against a real PostgreSQL 17 instance and a real
Spring Boot context — not asserted from reading the story alone:

- **AC1/AC2** (baseline + seed): `V138__baseline_schema.sql` (4,571 lines, 9 schemas, 116 tables,
  zero DML, zero DROP) and `V139__baseline_seed_data.sql` (160 `INSERT`s across 4 tables).
  Schema-diff and seed-diff both empty against a database built from the real `V02`..`V137`
  history. `session.drills`' `V111` deterministic re-key verified byte-identical (the story's own
  named highest-risk regression).
- **AC3**: 131 files deleted in one commit, `V138`/`V139` added in a separate one — two real git
  commits, not simulated.
- **AC4**: both `MigrationLint` baseline constants at `139`; probes (scratch, never committed)
  confirm the rules bind immediately above the new baseline for both an integer version and a
  decimal-minor version in the baseline band.
- **AC5**: `executeInTransaction=false` documented for what it actually does — confirmed working
  for batched backfills, confirmed NOT solving `CREATE INDEX CONCURRENTLY` (see Debug Log #3).
  This is a correction to the story's own D3 decision, made from empirical evidence gathered
  during this implementation, not from re-litigating the decision without new information.
- **AC6**: dual-database diff empty; full-context application boot verified via a real
  Testcontainers-backed IT (`RescheduleResourceIT`, 25/25 green) — not merely "the lint test
  passed."
- **AC7/AC8**: `docs/deployment/migration-rebaseline.md` written new; `migration-conventions.md`
  and `runbook.md` rewritten per AC8's file-by-file instructions, plus every stale `V<n>`
  cross-reference the squash left dangling elsewhere in `migration-conventions.md` (rules 6/7,
  the "still not covered" section, `BACKPORT_BELOW_BASELINE`'s baseline number) — not just the
  four sections AC8 named explicitly.
- **AC9**: `MigrationConventionLintTest` 13/13 green against the real 2-file tree; zero
  `migration-lint: allow-*` markers remain.

**Not done, by design, matching the story's own framing:** `deferred-work.md`'s
`:1367-1369` entry removal (Task 9.4) — this project's established convention is a separate
post-merge-prune PR, and the story's own Next Steps already frame it that way.

**Not run locally, per project convention** (`docs/validation-strategy.md`): the full `mvn verify`
suite. CI is the sole full-verification gate. Targeted verification instead: `MigrationConventionLintTest`
(13/13) and `RescheduleResourceIT` (25/25, chosen because it's a real `@SpringBootTest` against
Testcontainers Postgres, not a context-slice test — the app genuinely boots against the new
schema and does real JPA work against it).

---

## File List

**New:**
- `src/main/resources/db/migration/V138__baseline_schema.sql`
- `src/main/resources/db/migration/V139__baseline_seed_data.sql`
- `docs/deployment/migration-rebaseline.md`

**Deleted** (131 files, one commit — recoverable from git at `4a3f218dabd04e0c8419282e922f60de5f93934d`):
- `src/main/resources/db/migration/V02__quartz_schema.sql` … `V137__envelope_entity_recipients_composite_pk.sql`

**Modified:**
- `src/test/java/com/softropic/skillars/db/MigrationLint.java` — `GRANDFATHER_BASELINE` and
  `DEFERRED_92_BASELINE` both `121`/`127` → `139`; both javadoc blocks rewritten
- `src/test/java/com/softropic/skillars/db/MigrationConventionLintTest.java` — `@DisplayName`
  (line 65) and class javadoc updated to match the single boundary
- `docs/deployment/migration-conventions.md` — intro/go-forward-checklist baseline references
  updated to `V139`; rule 4 (`CREATE INDEX CONCURRENTLY`) fully rewritten with the empirical
  deadlock finding; rule 6 updated to point at the `executeInTransaction=false` sidecar for
  batched backfills; *Pre-production migration debt*, *Per-migration disposition*, and *Known
  lock-unsafe applied migrations* sections deleted; *Grandfathering*/*Two baselines* rewritten as
  a single *Grandfathering* / *One baseline, not two* pair; every other stale `V<n>` reference the
  squash left dangling corrected or reworded as historical narrative; cross-linked to
  `migration-rebaseline.md`
- `docs/deployment/runbook.md` — the four-item gate table replaced with a closure note; the
  `CREATE INDEX CONCURRENTLY` item explicitly reopened as ongoing debt rather than claimed closed
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — fixed a corrupted entry (see Debug
  Log) in addition to the story's own status transitions
- `_bmad-output/implementation-artifacts/skillars-deferred-112-pre-production-migration-rebaseline.md` —
  this file (Tasks/Subtasks, Dev Agent Record, File List, Change Log, Status; plus two pre-dev
  fixes requested separately — the Flyway `clean-disabled` attribution and the
  `payout-and-capture-pending.md` conscious-decision note)

**Not touched** (confirmed, matches the story's own "Not touched" list):
- `pom.xml`, `src/main/resources/application.yaml`

---

## Change Log

- **2026-09-15** — Dev implementation complete (`/bmad-dev-story`). All 9 ACs implemented and
  verified against a real PostgreSQL 17 instance and a real Spring Boot integration test (not
  merely re-stated from the story's plan). One correction to the story's own D3/AC5 found during
  implementation and documented rather than silently worked around: the `executeInTransaction=false`
  sidecar does not make `CREATE INDEX CONCURRENTLY` safe under this project's Flyway+Hikari
  configuration — reproduced empirically, reopened as tracked follow-up work in
  `migration-rebaseline.md` rather than claimed closed. One pre-existing data-corruption issue
  found and fixed in `sprint-status.yaml` (unrelated to this story's scope, discovered because
  this story updates that file anyway). Status → review.

---

**Created by:** `bmad-create-story` (skillars-deferred-112)
