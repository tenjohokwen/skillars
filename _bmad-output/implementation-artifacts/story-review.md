# Story Review: skillars-deferred-112-pre-production-migration-rebaseline

## Revision 3 — independent re-verification of story revision 2.0 (current)

**Reviewer:** senior-dev audit (grounded against current repo state, HEAD)
**Story file version reviewed:** 2.0 (the "true squash" rewrite: delete `V02`–`V137`, replace with `V138` + `V139`)

**Verdict: ready for dev.** I did not take the "Answered" annotations below on faith — I independently re-derived roughly 30 of the story's factual claims straight from the repo (exact line numbers in `MigrationLint.java` and `migration-conventions.md`, `INSERT` counts, table/schema names, git merge style, the resolved Spring Boot default for `spring.flyway.clean-disabled`) rather than trusting the story's citations. Every one checked out. Every Critical/High/Medium finding from revision 1/2 below is genuinely closed by the rewrite, not just asserted closed:

- **C1 (numbering)** — fixed. Story now correctly states `V02`..`V137` (verified: 131 files, no `V1`, highest is `V137`) and starts new files at `V138`.
- **C2 (schemas)** — fixed. Story's Ground Truth section lists all nine real schemas with correct creation sites; `platform` is correctly identified as config-key rows in `main.platform_config`, not a schema. Verified against every `CREATE SCHEMA` statement in the tree.
- **C3 (fresh-DB mechanism) / C6 (seed data loss)** — resolved by design, not patched: deleting the historical files (D1) means there's no SKIP-vs-replay ambiguity left to resolve, and `V139__baseline_seed_data.sql` (D2) explicitly carries `main.authority` (verified: ids 100–104 exactly matching `V21`/`V84`/`V92`), `main.platform_config` (verified: 35 source `INSERT`s, `V128`'s identity-column conversion correctly triggers "strip the `id` column" in the AC), and `session.drills` with the non-obvious `V111` re-key gotcha called out explicitly (verified against `V39`'s `gen_random_uuid()` and `V111`'s deterministic re-key by `trans_key`). This is the correct level of care for the trickiest part of this story.
- **C4 (V124 scope)** — resolved: V124 is explicitly named, and closed by deletion rather than by a rewrite that would have left it half-fixed.
- **C5 (baseline constants)** — fixed: correct file (`MigrationLint.java`), both `GRANDFATHER_BASELINE` and `DEFERRED_92_BASELINE` (verified at lines 90/97) set to `139` with a stated reason, and the AC4 probe tests are a genuinely good idea (prove the new baseline actually binds, not just that the constant changed).
- **C7 (`CREATE INDEX CONCURRENTLY` / per-batch commits impossible in a Flyway transaction)** — resolved: adopts `executeInTransaction=false` via a `.conf` sidecar (verified: `V125`/`V126`/`V127` all carry the "cannot be used" comment verbatim; they're deleted by AC3 either way).
- **H1–H7, M1–M6, N1–N3** — all moot or fixed: the migrations they pointed at (V60/V94/V97/V98/V117/V124) no longer exist in the rewritten story's approach, the `VALIDATE CONSTRAINT` syntax error can't recur since no `NOT VALID`/`VALIDATE` pair is being authored, `runbook.md` and `migration-conventions.md` are now in Files Touched with the stale gate tables deleted rather than edited-in-place, and cross-links now resolve (checked: `../../docs/deployment/...` from `_bmad-output/implementation-artifacts/` is correct; the `deferred-work.md` anchor now matches the real heading slug).

**Two non-blocking notes for the implementer, found fresh in this pass (not carried over from revision 1/2):**

1. **`docs/architecture/payout-and-capture-pending.md` is not in AC8's file list and references V124 by name** (its own design-decision narrative: *"Migration: V124 widens the `chk_bp_status` CHECK..."*, ~10 occurrences). AC8's verification grep is scoped to `docs/deployment/` only, so it won't catch this. That's probably fine to leave alone — it's a historical design-decision record describing what was decided and why, the same way git history is "the only place [the old migrations] need to live now" per the story's own reasoning — but worth a conscious "leave as-is, it's an ADR" decision rather than an oversight. Not blocking.
2. **The Ground Truth section attributes `flyway clean` being disabled to "Flyway 10+"** — checked against the resolved `spring-boot-autoconfigure-3.5.16.jar`'s configuration metadata: `spring.flyway.clean-disabled` defaults to `true` via **Spring Boot's** autoconfiguration, not Flyway core itself (raw Flyway defaults it to `false`). The operational conclusion in AC7 (needs `spring.flyway.clean-disabled=false` or drop/recreate) is correct either way — only the causal attribution is imprecise. Cosmetic; fix if convenient.

Also confirmed as a genuine (if implicit) strength of the rewrite: this repo merges PRs with real merge commits (`git log --merges` shows `Merge pull request #N from ...`), not squash-merge, so AC3's "deletion is its own commit, reviewable separately from the addition commits" is actually achievable in the merged history — it wouldn't be on a squash-merge repo.

---

## Revision 2 (superseded — retained as audit record)

**Revision:** 2 — verification pass over revision 1. Two findings corrected (C2, C4), three sharpened (C1, C5, M3), five new findings added (C6, C7, H6, H7, M4–M6). No revision-1 finding was withdrawn as a false positive.
**Status:** ✅ **Answered by story revision 2.0** (2026-09-15), confirmed independently in Revision 3 above. C3/C6/C7 resolved by owner decision (D1 true squash / D2 companion seed migration / D3 adopt `executeInTransaction=false`); all other findings folded into the rewrite. Retained as the audit record of what was wrong and why.

**Verdict (as of revision 1 of the story):** Not ready for dev as written. The central mechanism (how a fresh DB skips the historical migrations) is never resolved and the story contradicts itself on it; the proposed baseline silently discards every seeded row the application needs to boot; two ACs prescribe operations this project's own Flyway setup cannot execute as described; and several verification commands reference tables, schemas, filenames, enum values and SQL syntax that do not exist.

Every finding below was checked against actual file contents — migration files, `migration-conventions.md`, `runbook.md`, `pom.xml`, `application.yaml`, `MigrationLint.java`, `MigrationConventionLintTest.java`, and the resolved `flyway-core` jar — not inferred from the story text.

---

## Critical

### C1. Migration ceiling is wrong, and so is the floor: the range is V02..V137, not V1..V139
The story's premise throughout ("V1..V139 migrations applied", new files `V140`–`V143`) is wrong at both ends.

- **Ceiling:** the highest migration on disk is **`V137__envelope_entity_recipients_composite_pk.sql`**. V138 and V139 do not exist.
- **Floor:** there is **no `V1`**. The lowest migration is `V02__quartz_schema.sql`; `V01`, `V03`, `V05`, `V06`, `V08`, `V09` were never created.

Every occurrence of "V1..V139" (AC1, AC7, AC9, Technical Approach, Known Constraints, Testing Requirements, Additional Context) is wrong. The proposed new-file numbers `V140`–`V143` leave a two-version gap (V138, V139) that this same story later tells future developers never to leave — *"Create `V<next>__<description>.sql` in chronological order, no gaps"* (Dev Notes). Build the baseline from V137 and start new files at **V138**.

The missing `V1` also matters for the story's goal statement, *"enabling MigrationLint to bind from V1"* — see C5. There is nothing at V1 to bind from, and the history was never gapless to begin with, so "no gaps" cannot be stated as an existing invariant.

### C2. AC1/AC9 name four schemas; the project has nine — and one of the four is not a schema at all
AC1's `psql` count query and AC9's `docker run … flyway migrate -schemas=main,platform,booking,development` hard-code `main`, `platform`, `booking`, `development`.

**`platform` is not a schema.** There is no `CREATE SCHEMA platform` anywhere, and no object is ever created in it. The 42 `platform.*` occurrences are **configuration keys** — `platform.commission_rate`, `platform.video.approval.notification_enabled`, etc. — stored as rows in the table `main.platform_config` (see `V20__platform_config.sql`, `V105:3`, `V128:1`). The claim originates in the story itself — `grep -rn "main, platform, booking" docs/` returns nothing, so it was not inherited from any project document. The review's own first revision repeated it.

**The real schemas are nine.** Confirmed by `CREATE SCHEMA` statements plus schema-qualified `CREATE TABLE` counts across all 131 migration files:

| Schema | Created in | Tables |
| --- | --- | --- |
| `main` | Flyway `defaultSchema` (no `CREATE SCHEMA`) | 33 + 12 unqualified (quartz V02, security V10) |
| `marketplace` | `V26:1` | 9 |
| `booking` | `V29:2` | 6 |
| `session` | `V38:4` | 7 |
| `development` | `V46:4` | 14 |
| `payment` | `V61:4`, `V64:8` | 15 |
| `messaging` | `V65:1` | 4 |
| `reviews` | `V67:1` | 3 |
| `admin` | `V70:1` | 4 |

`session`, `reviews` and `admin` are missing from the story's list entirely, as are `marketplace`, `messaging` and `payment`. `payment.booking_payments` — the subject table of AC3 — lives in one of the excluded schemas, so AC9's command would not even manage the schema its own sibling AC is rewriting.

**Separately, the `-schemas=` flag is the wrong mechanism here.** `application.yaml:113-117` sets only `defaultSchema: main`; `flyway.schemas` is not configured at all. Every non-`main` schema is created by the migrations themselves via `CREATE SCHEMA IF NOT EXISTS`. Passing `-schemas=` on the CLI would make Flyway manage (and `clean`) a different set than the application does — a divergence between the AC9 verification and the real bootstrap path. AC9 should invoke Flyway the way the app does, not with a hand-written schema list.

### C3. No mechanism specified for making a fresh DB skip the historical migrations — and the story contradicts itself on whether it is possible
- AC1 "Verified by": *"Fresh database bootstrap: `flyway info` shows V1..V139 marked SKIP (already in baseline), V140 marked PENDING"*.
- Known Constraints, same document: *"Flyway will **not** skip V1..V139 automatically; a fresh database will run them all. The 'baseline' is just a documentation / process decision, not a Flyway configuration."*

Mutually exclusive, never reconciled. Checked against the project: `application.yaml:116` sets `flyway.baseline-on-migrate: true` with **no `baseline-version`** (defaults to `1`), and no `baselineVersion` appears anywhere in `src/`. `baselineOnMigrate` only auto-baselines a **non-empty, untracked** schema; it does nothing on a genuinely empty one — which is exactly the fresh-bootstrap case AC1 and AC9 test.

So on a truly empty database Flyway runs V02 through V143 in sequence, and V140 — a `pg_dump`-style DDL snapshot that AC1 explicitly forbids from carrying `IF NOT EXISTS` guards — tries to recreate every object V02..V137 already created and fails on the first `relation already exists`.

The story must pick one and write it down: ship V138+ as the only files new checkouts see and handle already-migrated environments separately with an explicit config change, or drop the SKIP claim from AC1/AC9 and describe what actually happens.

### C6. A DDL-only V140 destroys every seeded row the application needs to boot — *(new in revision 2)*
AC1 mandates that V140 contain *"exactly zero DML statements (zero `INSERT`, `UPDATE`, `DELETE`, `TRUNCATE`)"*, and Technical Approach Phase 1 specifies generating it from `pg_dump --schema-only`, which by definition emits no data. But the migration history carries **44 `INSERT` statements** of load-bearing seed data:

| Target | Statements | What is lost |
| --- | --- | --- |
| `main.platform_config` | 35 | ~28 distinct config keys (`platform.commission_rate`, moderation SLAs, payout config, retention windows, feature flags…) |
| `main.authority` | 3 | **All five security roles** — `ROLE_COACH`, `ROLE_PARENT` (`V21`), `ROLE_PLAYER` (`V84`), `ROLE_ADMIN`, `ROLE_LTD_ADMIN` (`V92`) |
| `session.drills` | 4 | The 20-drill platform catalog (`V39`, re-keyed by `V111`) |
| `development.skill_definitions` | 1 | The radar skill catalog (`PAC`/`SHO`/`PAS`/… — `V46`) |

Also lost: V103's availability-window timezone backfill, V111's deterministic drill-ID re-key, V135's coach-payout cutover backfill.

A fresh bootstrap from a DDL-only V140 produces a database with an empty `authority` table — **no role can be granted, so authentication is broken** — an empty `platform_config`, no drills and no skill definitions. AC9's own success criteria (*"The application can boot successfully against the new schema"*, *"All existing integration tests pass"*) would fail, while AC1's `pg_dump --schema-only` comparison would still pass, because that diff cannot see the missing rows.

This is a direct, unresolvable conflict between AC1's zero-DML rule and AC9's boot requirement. The story must either carve out an explicit seed-data exception in the baseline (a companion `V139__baseline_seed_data.sql`, or a repeatable `R__seed_reference_data.sql`, which is the cleaner fit for idempotent `ON CONFLICT DO NOTHING` catalogue seeds), or state that V140 is not self-sufficient and the historical seed migrations must still run. Neither option is currently acknowledged.

### C7. AC4 and AC5 prescribe operations this project's Flyway setup cannot execute — and the repo already says so — *(new in revision 2)*
Both ACs require breaking out of a single transaction, which is precisely the thing this codebase has documented as impossible three times:

- **AC4 / V143:** `CREATE INDEX CONCURRENTLY ix_crp_player_id_concurrent …`
- **AC5 / V98:** *"Each batch is committed in its own transaction (NOT inside a single wrapping transaction)"*

`V125__parent_credit_ledger_reference_dedup.sql:14`, `V126__outbox_next_attempt_at.sql:24` and `V127__narrow_pcl_reference_dedup.sql:43` each carry the same comment verbatim: *"this codebase runs Flyway migrations in a transaction so `CREATE INDEX CONCURRENTLY` [cannot be used]"*. `runbook.md:597-599` restates it as an open pre-production item. `CREATE INDEX CONCURRENTLY` errors out inside a transaction block, and `COMMIT` inside a Flyway-managed transaction fails the same way.

There **is** a mechanism: the resolved Flyway version is **11.7.2** (see M1), which supports per-script `executeInTransaction=false` — the property is present in `ConfigUtils` and `FluentConfiguration` in the resolved jar. But the story never mentions it, and the repo's existing comments assert the opposite, so a dev implementing AC4/AC5 literally will hit a hard failure and have no documented way forward. If `executeInTransaction=false` is the intended answer, say so, name the sidecar `.conf` file as a deliverable, and note that it also closes the V125/V126/V127 item (see M6). If it is not, AC4's CONCURRENTLY and AC5's per-batch commits must be dropped.

### C4. V124 is outside scope, but it re-applies the exact lock-unsafe pattern AC3 is fixing — and the project's two authoritative lists disagree about scope
**Correction to revision 1:** the story's five-migration scope is not arbitrary. `migration-conventions.md:273` *"Known lock-unsafe applied migrations (pre-production)"* — which the story's own Previous Story Intelligence cites as *"the authoritative reference"* (skillars-deferred-101 AC12) — names exactly **V60, V94, V97, V98, V117**. The story copied that list faithfully.

The problem is that **the project has two gate lists and they do not agree**:

| Source | Names |
| --- | --- |
| `migration-conventions.md:273` (deferred-101 AC12) | V60, V94, **V97, V98**, V117 |
| `migration-conventions.md:236` Per-migration disposition (deferred-91 D7) | V60, V94, V117, **V124** |
| `runbook.md:590-595` Pre-production release gate | V60, V94, V117, **V124** (+ V125/V126/V127 as a follow-on note) |

`runbook.md:588` states *"all four items below must be closed"* before the first production deploy, and line 595 is:

> `V124` (`CAPTURE_ABANDONED` CHECK widen) | The CHECK widen and its first write ship in the **same** release, deviating from convention rule 5 | Split into widen-then-write across two releases

`V124__booking_payment_capture_abandoned.sql:35-38` performs `DROP CONSTRAINT IF EXISTS chk_bp_status, ADD CONSTRAINT chk_bp_status CHECK (…)` — a full validating `ADD CONSTRAINT` on **the very constraint AC3 is rewriting for V94** — carrying its own opt-outs (`-- migration-lint: allow-validating-constraint`, `allow-enum-widen-same-release`) whose comment says verbatim: *"Before the first production deploy this must be split into widen-then-write across two releases."*

Net effect: implement AC3 exactly as written (V94 → `NOT VALID`, V142 → `VALIDATE`) and leave V124 alone, and `chk_bp_status` still takes a full blocking validating scan when V124 runs — immediately after V94/V142 in migration order. AC3's goal is not achieved.

The story must reconcile the two lists explicitly rather than silently following one. `runbook.md` is also absent from "Files touched" (M2), so even a correct implementation leaves the gate table stale and still listing V124 as outstanding.

### C5. Phase 3's "bind MigrationLint from V1" targets the wrong file, the wrong constant, and would break the build
Phase 3 step 1 says *"Update `MigrationConventionLintTest` baseline from V121 to V1"*. Three problems:

1. **Wrong file.** The baselines are not in `MigrationConventionLintTest.java`. They are `public static final int` constants in **`src/test/java/com/softropic/skillars/db/MigrationLint.java`**.
2. **There are two of them, not one.** `GRANDFATHER_BASELINE = 121` (line 90) and `DEFERRED_92_BASELINE = 127` (line 97). The second exists precisely because `V122`–`V127` are above the first baseline but already applied and checksum-frozen, so they *cannot* carry the opt-out markers the deferred-92 rules demand. The story mentions only one.
3. **Setting either to `1` re-breaks CI.** V02..V137 cannot be deleted — Flyway needs them on disk to validate checksums on every environment that already applied them — and cannot be edited to add opt-outs. Drop the baseline to 1 and `MigrationConventionLintTest` starts scanning V60/V70/V91/V94/V97/V98/V117 and V122–V127 on every CI run and fails the build on migrations nobody is allowed to fix. That is the reverse of "enabling MigrationLint."

The intended target is almost certainly *the new squashed-baseline version* (V137/V138 per C1), not literal V1. The story conflates two unrelated meanings of "baseline" — MigrationLint's rule-enforcement cutoff versus the new squashed-schema file — and must disambiguate them, then state both constants' new values explicitly.

Relatedly, Phase 3 step 3 (*"Remove the frozen-file constraint from build configuration (if any)"*) and the Files-touched entry for `pom.xml` are both chasing something that does not exist: there is no build-config frozen-file switch, only the two Java constants above.

---

## High

### H1. AC3's verification cites enum values that never existed
AC3 "Verified by": *"code that writes `PAYMENT_PENDING` / `PAYMENT_CAPTURED` / `PAYMENT_FAILED` / other pre-V94 values still succeeds."* Per `V62__session_payment_credit_wallet.sql:90`, the actual pre-V94 constraint is `CHECK (status IN ('CAPTURED', 'CHARGE_FAILED', 'FROZEN'))`; V94 adds `CAPTURE_PENDING`; V124 adds `CAPTURE_ABANDONED`. None of `PAYMENT_PENDING`, `PAYMENT_CAPTURED` or `PAYMENT_FAILED` appears anywhere in the codebase. The step is unexecutable as written — substitute the real values.

### H2. AC5's verification queries the wrong schema
AC5 "Verified by" and the Manual Verification section both use `psql -c "\d+ main.player_radar_composites"`. The table is in the **`development`** schema — `V98__player_radar_composites_distinct_coach_count.sql:6`: `ALTER TABLE development.player_radar_composites ADD COLUMN …`. The command queries a relation that does not exist.

### H3. AC6's exact grep command cannot return zero hits
AC6 specifies `grep -r "refund_eligibility\|refund_amount" src/main/java src/main/resources` must return zero hits. Run literally it returns **4 hits in 2 files** — 2 in `V32__booking_state_machine.sql` and 2 in `V97__drop_booking_refund_eligibility_and_amount.sql`, both under `src/main/resources`. `src/main/java` alone returns zero, which is the real intent. Scope the command to `src/main/java` plus non-migration resources, or exclude `db/migration/`.

(Note: `MigrationLint.REAL_SOURCE_ROOTS` has the same shape — `src/main/java` and `src/main/resources` — so whatever exclusion the AC settles on should match what `DROP_WITHOUT_PRIOR_RELEASE_PREP` actually does, or the two checks will disagree.)

### H4. AC2/AC3's "no separate DROP survives" is not implementable as written
Both ACs require the existing `DROP CONSTRAINT` + `ADD CONSTRAINT` pair be consolidated so that *"no separate `DROP` … call survives"*. But both constraints already exist when these migrations run:

- `chk_videos_operational_state` — created `V55:6`, dropped and re-added `V59:3-6`, again `V60:79-82`.
- `chk_bp_status` — created inline `V62:90`, dropped and re-added `V94:9-10`, again `V124:35-36`.

`ADD CONSTRAINT … NOT VALID` on a name that already exists fails with `constraint already exists`. A dev reading "no separate DROP survives" as "delete the DROP" breaks the migration on first run. State it explicitly: **keep the DROP**; only the re-`ADD` gains `NOT VALID`. Folding DROP+ADD into one `ALTER TABLE` is a cosmetic consolidation, not removal of the DROP.

### H5. AC4 does not account for V117's existing defensive DELETE
`V117__coach_radar_preferences_player_fk.sql:13-14` opens with an orphan cleanup before adding the FK — `DELETE FROM development.coach_radar_preferences crp WHERE NOT EXISTS (SELECT 1 FROM main.player_profiles p WHERE p.id = crp.player_id);` — explicitly *"mirrors V113's / V109's established pattern"* per its own comment. AC4's Given/When/Then covers only the `ADD CONSTRAINT` and `CREATE INDEX`. A dev rewriting the file against the literal bullet list could drop the DELETE; harmless in a clean pre-production DB, a real regression on any dev/staging database that has accumulated orphans through testing.

### H6. `VALIDATE CONSTRAINT <name> ON <table>` is not valid PostgreSQL — *(new in revision 2)*
AC2, AC3 and AC4 all specify the revalidation step in this form:

- AC2: `VALIDATE CONSTRAINT chk_videos_operational_state ON main.videos`
- AC3: `VALIDATE CONSTRAINT chk_bp_status ON booking_payments`
- AC4: `VALIDATE CONSTRAINT fk_crp_player_id ON development.coach_radar_preferences`

PostgreSQL has no such statement. `VALIDATE CONSTRAINT` is an `ALTER TABLE` action: `ALTER TABLE main.videos VALIDATE CONSTRAINT chk_videos_operational_state;`. All three occurrences need rewriting; as written, every new migration V141–V143 is a syntax error. AC3's form additionally omits the `payment.` schema qualifier (see C2).

### H7. AC6 names a file that does not exist and asserts syntax the real file does not use — *(new in revision 2)*
AC6 "Verified by": *"Read `V97__drop_old_booking_columns.sql` and confirm syntax is `DROP COLUMN IF EXISTS`."*

- The filename is wrong. The real file is **`V97__drop_booking_refund_eligibility_and_amount.sql`**.
- The syntax assertion is wrong. The file's entire contents are two statements with **no `IF EXISTS`**:
  ```sql
  ALTER TABLE booking.bookings DROP COLUMN refund_eligibility;
  ALTER TABLE booking.bookings DROP COLUMN refund_amount;
  ```

So AC6's verification step fails on a migration the story simultaneously (and correctly) concludes needs no change. The AC's actual claim — `DROP COLUMN` is catalog-only in PostgreSQL, no rewrite — holds; only the check written to prove it is wrong. Note the absent `IF EXISTS` is itself grandfathered: V97 sits below `GRANDFATHER_BASELINE = 121`, and the `DROP … without IF EXISTS` rule (added by the deferred-91 review) binds above it.

---

## Medium

### M1. Flyway version is misstated twice, inconsistently, and the wrong version props up a wrong conclusion
Project Context says *"Flyway: Version 9.x"*. Architecture Compliance says *"(Flyway 6.x does not have automatic baseline detection)"*. Resolved from the Spring Boot parent (`pom.xml:4-6`, version 3.5.16) whose BOM pins `<flyway.version>11.7.2`, the real version is **flyway-core 11.7.2**. Neither citation matches.

This is not cosmetic: the fabricated "Flyway 6.x" is used to justify the Known Constraints claim that Flyway cannot do the baseline skip. Flyway 11 has both `baselineVersion` and `baselineOnMigrate`; the real limiting factor is `baselineOnMigrate`'s semantics on an *empty* schema (C3), not the version. The same wrong-version reasoning also hides the `executeInTransaction=false` option that C7 depends on.

### M2. `runbook.md` is not in "Files touched" despite being the authoritative gate doc
`docs/deployment/runbook.md:579-604` holds the *"Pre-production release gate: outstanding migration rewrites"* table this story exists to close out, including its own verification clause: *"`MigrationConventionLintTest` passes with the corresponding `-- migration-lint: allow-*` opt-outs **removed** from the affected files. If a migration still needs its opt-out, the item is not closed."* The file is not listed as modified, so a fully-correct implementation still leaves that gate table stale — and, per C4, still listing V124 as outstanding.

### M3. All three "Reference documents" cross-links are broken — paths, not just anchors
The story lives at `_bmad-output/implementation-artifacts/`. Every link in Additional Context → Reference documents resolves to a non-existent file:

| Link as written | Resolves to | Actual location |
| --- | --- | --- |
| `../migration-conventions.md` | `_bmad-output/migration-conventions.md` ❌ | `docs/deployment/migration-conventions.md` |
| `../runbook.md` | `_bmad-output/runbook.md` ❌ | `docs/deployment/runbook.md` |
| `../../deferred-work.md#Pre-production-migration-rebaseline` | repo-root `deferred-work.md` ❌ | `_bmad-output/implementation-artifacts/deferred-work.md` (same directory — no `../` needed) |

The anchor is wrong too: the real heading is `### Pre-production migration rebaseline (future task, no owner)` (`deferred-work.md:1367`), which GFM slugs to `#pre-production-migration-rebaseline-future-task-no-owner`. AC7 requires the rebaseline doc to carry *"no file-path or line-number references that will rot immediately"* — the story should meet its own bar.

### M4. AC4's V143 index would be a duplicate, and its name bakes in the build method — *(new in revision 2)*
V117 creates `ix_crp_player_id`. AC4 proposes V143 create `ix_crp_player_id_concurrent` on the same table and column without dropping the first, leaving two identical indexes carrying full write-amplification cost forever. The `_concurrent` suffix also encodes *how it was built* into a permanent object name that says nothing useful once the migration has run. If the CONCURRENTLY rebuild is kept (subject to C7), it needs `DROP INDEX ix_crp_player_id` and should reuse the original name.

### M5. `runbook.md:594` puts `coach_radar_preferences` in the wrong schema — *(new in revision 2)*
The gate table reads *"`V117` (`marketplace.coach_radar_preferences` FK + index)"*. The table is in **`development`** — created at `V51__radar_display_correlation.sql:13` as `CREATE TABLE development.coach_radar_preferences`, and referenced as `development.` throughout V117. A pre-existing doc bug, but this story is already editing that gate table (M2) and should fix it in the same pass.

### M6. The V125/V126/V127 gate item sits in the same runbook block and is silently out of scope — *(new in revision 2)*
`runbook.md:597-599`, immediately below the four-item gate table: *"Also outstanding, and cheap to close at the same time: `V125`/`V126`/`V127` create indexes without `CONCURRENTLY` because Flyway runs migrations in a transaction here. Once a production database exists, either move those to a non-transactional Flyway callback or accept and schedule the lock."*

The story neither covers these nor explains why not. Given C7, they share a root cause with AC4 and AC5 and would be closed by the same `executeInTransaction=false` mechanism. Either pull them in or state the deferral explicitly so the runbook can be annotated rather than left ambiguous.

---

## Minor / nits

### N1. Proposed filenames break the project's naming convention
All 131 existing migrations use underscore-separated descriptions (`V137__envelope_entity_recipients_composite_pk.sql`). The story's proposed files use hyphens (`V140__initial-schema-baseline.sql`, `V141__revalidate-videos-operational-state-constraint.sql`, …). Flyway accepts both — `validateMigrationNaming: true` governs the `V<n>__` prefix, not the description — so this is cosmetic, but it is inconsistent with 100% of prior art.

### N2. AC8's migration list is internally inconsistent — *(new in revision 2)*
AC8 opens *"Given the five rewritten migrations (V60, V94, V97, V98, V117, V140–V143)"* — six named migrations, of which one (V97) is explicitly *not* rewritten per AC6, plus four new files. Should read: four rewritten (V60, V94, V98, V117), one audited-unchanged (V97), four new.

### N3. AC5's timing criterion contradicts the story's own premise
AC5 "Verified by": *"backfill runs in <30 seconds on a populated test database"*, while Known Constraints states *"No production data exists yet"* and the Dev Notes call the lock-timeout values *"conservative for a pre-production database with no data."* Either specify the row count the populated test fixture must carry, or drop the timing criterion.

---

## What checks out

Verified correct; no finding is claimed against these.

- V60, V94 and V117 do perform validating `ADD CONSTRAINT` / plain `CREATE INDEX` under `ACCESS EXCLUSIVE` — matches the files exactly.
- V97's "already safe" conclusion is correct: two `DROP COLUMN` statements, catalog-only in PostgreSQL, no rewrite needed. (Only AC6's *verification wording* is wrong — see H7.)
- V98's "single unbatched `UPDATE … FROM (aggregate)`" description matches `V98:8-16` exactly.
- The "no production data exists yet" premise, and its use to justify pre-production-only fixes, is consistent with `migration-conventions.md`'s own grandfathering rationale (`:240-253`) and is a genuinely load-bearing project fact.
- AC4d's decision-gate framing (grow vs. bounded table) is a reasonable, low-risk way to defer a real judgment call to dev-story time — though its "yes" branch is blocked by C7.
- The Flyway checksum-immutability constraint is stated correctly, and is exactly why C5's baseline-to-1 change would be destructive.

---

## Recommended next step

Send back for a rewrite pass before `/bmad-dev-story`. In dependency order:

1. **C6 and C3 first** — they determine whether the baseline approach is viable at all. Decide how seed data survives, and decide (and write down) the actual fresh-bootstrap mechanism. Everything else is downstream.
2. **C7** — settle whether `executeInTransaction=false` is adopted. It gates AC4's CONCURRENTLY, AC5's per-batch commits, and M6.
3. **C4** — reconcile the two gate lists; decide V124 in or out; add `runbook.md` to Files touched either way (M2, M5, M6).
4. **C1, C2, C5** — renumber against V137/V138, correct the schema list to the nine real ones and drop `platform`, and disambiguate the two `MigrationLint` baseline constants with explicit target values.
5. **H1–H7, M1, M3, M4, N1–N3** — mechanical corrections to verification commands, SQL syntax, filenames, enum values, versions and cross-links. All one-liners; fold them into the same pass.

The story's underlying intent is sound and the pre-production gate is real. What it needs is grounding against the repository as it actually is.
