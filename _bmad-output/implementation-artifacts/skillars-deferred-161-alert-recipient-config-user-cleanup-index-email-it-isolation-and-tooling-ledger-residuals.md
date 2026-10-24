# Story skillars-deferred-161: Alert recipient config, email-IT isolation, Quasar toolchain and ledger residuals

Status: ready-for-dev

<!-- Created 2026-10-24 (/bmad-create-story). User-requested bundle mined from deferred-work.md on the
     day of the full-file re-audit/prune. Every item below was re-verified against HEAD 4317b019
     before drafting (see "Verified state at drafting"). Ledger line numbers shift constantly; locate
     bullets by the quoted lead text, not by number. -->

## Story

As a maintainer of the Skillars platform,
I want the oldest still-open ledger residuals (an un-deliverable alert mailbox, a test that depends on global DB state, an outdated frontend toolchain, and several
partly-closed hazards) closed or explicitly re-decided,
so that SES bounce-rate risk, scheduler lock hold time and CI determinism stop depending on
"deferred" notes, and the ledger stops re-offering items nobody intends to build.

## Owner decisions (2026-10-24)

- **Dropped from this story:** the `main."user"` cleanup-sweep index (old AC3 / D161-A — stays open in the
  ledger, untagged) and the phone operator detection residual (old AC8 / D161-F — app is not
  Cameroon-targeted; ledger bullet marked `[DECIDED]`). AC numbers 3 and 8 are kept as "Dropped" so
  references stay stable.
- **All other sub-decisions: the recommended option is confirmed** — D161-B(1) time-boxed Quasar hop
  with revert-and-annotate stop-loss; D161-C(1) `BoundedKey` closed by decision, no code;
  D161-D(1) fix `ConfigService.refreshCache()` at the root; D161-E(1) trim to latest 5
  `prior last_updated` lines + convention note.

## Ledger → story mapping (so future passes do not re-pick these)

All bullets below live in `_bmad-output/implementation-artifacts/deferred-work.md` and have been
tagged `[PICKED UP by skillars-deferred-161]` there. On story completion the dev **deletes** closed
bullets (file convention) and re-annotates anything closed by decision rather than code.

| AC | Ledger bullet (lead text) | Section |
|----|---------------------------|---------|
| 1 | "`AlertNotificationListener` mails a mailbox that cannot exist" | ses-1-5 dev section (2026-10-24) |
| 2 | "`RegistrationEmailDurabilityIT`'s scheduler cases operate on global repository state" | `ses-1-4-registration-email-durability` |
| 3 | *(dropped — bullet stays open, untagged)* `main."user"` cleanup-sweep index | code review of skillars-deferred-123 |
| 4 | "The Quasar Vitest App Extension is used library-only…" | skillars-deferred-104 |
| 5 | D2 "Not the full call-site migration" (`BoundedKey`, declined 4×) | deferred-129/-130 |
| 6 | D5 "`lock_timeout` bound is re-read … inside `erase()`'s outer transaction" | code review of skillars-deferred-129 |
| 7 | "`deferred-work.md` and `sprint-status.yaml` accumulate indefinitely…" | skillars-deferred-52 story creation |
| 8 | *(dropped — marked `[DECIDED]` in ledger)* operator-level `Provider` detection CM-only | skillars-deferred-99 AC16 note |

## Verified state at drafting (HEAD 4317b019)

- `AlertNotificationListener.java:61` still hardcodes `recipient.setEmail("ops@skillars.internal")`
  (only for `notificationChannel == EMAIL`; default is `LOG`). `VideoModerationEmailListener` already
  resolves `platform.admin_alert_email` via `ConfigService.find(...)`.
- `V138__baseline_schema.sql` has no index on `main."user"` supporting
  `UserRepository.findByActivatedFalseAndCreatedDateBeforeAndCleanupFailedAtIsNullOrderByIdAsc`
  (used by `UserAdminService` ~L384). Latest migration is `V168`.
- `RegistrationEmailDurabilityIT` calls `emailRetryScheduler.retryFailedEmails()` at ~L330/343/370;
  the scheduler polls the whole `envelope_entity` table. (The `committedRowFor` helper part is already
  fixed.)
- `@quasar/app-vite` is `2.4.1` in `node_modules`; `package.json` says `^2.1.0`. npm (checked today):
  `@quasar/app-vite` latest `3.10.3`; `@quasar/quasar-app-extension-testing-unit-vitest` latest
  `3.0.0` with peers `@quasar/app-vite ^3`, `quasar ^2.24`, **`vitest ^4 || ^5`**. Repo is on
  vitest `^3.2.4`, quasar `^2.16`. There is **no** AE release compatible with app-vite 2.4.x + a
  current vitest, so "register the AE" is only reachable through a full app-vite 3 / vitest 4 hop.
- `BoundedKey.defaultValue` exists for all 18 `HAS_CODE_DEFAULT` keys (deferred-133 AC2); call sites
  still re-type the literal. Declined a 4th time by deferred-135.
- `GdprErasureService`: the `lock_timeout` read is already hoisted to once per `eraseParentChildren`
  (deferred-132 Fix 9); `ConfigService.refreshCache()` (`private synchronized`, `findAll()` on the
  caller's connection) is the remaining hazard.
- `sprint-status.yaml` is ~419 KB with 72 `# prior last_updated` comment lines, one up to ~17.7 KB;
  `deferred-work.md` ~351 KB / 3.3k lines after today's prune.
- Phone: `PhoneValidationProperties` is config-driven per market; `Provider.java` /
  `CamMobileValidator.java` (MTN/Orange/NextTel prefixes, `V146__user_phone_provider_column.sql`)
  stay Cameroon-only.

## Sub-decisions (RESOLVED 2026-10-24 — see Owner decisions above)

Kept for the option trade-offs; the chosen option is the one marked recommended, except A and F which are dropped.

**D161-A — DROPPED.** `main."user"` index shape (AC3). The ledger blocker was "need production row-count +
EXPLAIN evidence"; there is still no production deploy.
- *(1, recommended)* Ship the partial index
  `CREATE INDEX CONCURRENTLY idx_user_cleanup_sweep ON main."user" (id) WHERE activated = false AND cleanup_failed_at IS NULL`
  in its own migration (convention: hot-table index = `CONCURRENTLY`, own file). It matches the
  query's `ORDER BY id` + predicate exactly, is tiny (only unactivated rows), and costs almost nothing
  pre-data. Validate shape with `EXPLAIN` against seeded IT data (planner choice on a tiny table is
  weak evidence — say so in the story record).
  Pros: closes the item; negligible write overhead. Cons: shape not proven on real data.
- *(2)* Keep deferring until a production deploy exists. Pros: no guess. Cons: this has been
  re-deferred repeatedly; lock hold time keeps depending on a PK walk.

**D161-B — Quasar toolchain (AC4).**
- *(1, recommended)* Time-boxed hop: `@quasar/app-vite` 3.x + `quasar` ≥2.24 + `vitest` 4 +
  AE 3.0.0 + `quasar ext add`. Hard stop: if `npx quasar build` (used by `mvn verify`) or the Vitest
  suite cannot be made green within the box, **revert, leave the toolchain as is, and re-annotate the
  ledger bullet with the verified blocker versions above** (that outcome still closes the "unverified"
  part). Pros: finally retires the hand-placed `vitest.config.mjs`. Cons: largest-risk task in the
  story; frontend CI is the gate.
- *(2)* Skip the hop; only re-verify and refresh the annotation (no AE release supports app-vite 2.4).
  Pros: zero risk. Cons: bullet stays open indefinitely.
- *(3)* Pull it into its own story (`skillars-deferred-162`) and drop AC4 here. Pros: isolates risk.

**D161-C — `BoundedKey` call-site migration (AC5).** Declined four consecutive times.
- *(1, recommended)* Do **no** code; replace the bullet with a single `[DECIDED — permanent: call
  sites keep their deliberate literal; registry `defaultValue` is documentation, guarded only by the
  default∈[min,max] self-check]` entry so it stops being re-offered. Pros: ends the loop. Cons: drift
  between a call-site literal and the registry value stays uncaught.
- *(2)* Add a cheap drift guard (test that scans call sites for the 18 keys and compares literals to
  `defaultValue`). Pros: catches drift without touching every caller. Cons: brittle source scan.
- *(3)* Full migration (read default/min/max off `BoundedKey`). Pros: single source of truth. Cons:
  touches every caller; previously declined 4×.

**D161-D — Where to fix the `ConfigService` stall hazard (AC6).**
- *(1, recommended)* Fix the root: make `refreshCache()` run `findAll()` **outside** the monitor
  (load, then swap the cache under a short lock) so no caller can stall the JVM while waiting on a
  Hikari connection. Benefits every caller, not just GDPR erasure.
- *(2)* Pre-read/warm the bound before `erase()`'s transaction opens (pass as parameter). Pros:
  local. Cons: only protects this path; `refreshCache` hazard remains.

**D161-E — Ledger/status growth (AC7).**
- *(1, recommended)* Mechanical, low-risk trim: keep only the latest 5 `# prior last_updated`
  comment lines in `sprint-status.yaml` (git history keeps the rest), shorten `last_updated` to
  ≤200 chars, and add a one-line convention note at the top of both files. Do **not** rewrite
  per-story status comments.
- *(2)* Also move per-story completion narrative out to the story files. Pros: smaller file. Cons:
  broad edit across ~400 entries; high churn.

**D161-F — DROPPED.** Operator detection beyond CM (AC8).
- *(1, recommended)* Move operator prefixes into `PhoneValidationProperties` per market
  (`markets.<code>.operators[]`), keep the Cameroon list as the seeded default, and make
  `CamMobileValidator`/`Provider` resolve through the same `MarketResolver`; unknown market → no
  operator (null), never a wrong operator. Pros: removes the last CM hardcode. Cons: touches the
  `user.phone_provider` write path.
- *(2)* Document as intentionally CM-only and delete the residual. Pros: no code. Cons: blocks
  other markets later.

## Acceptance Criteria

1. **Alert recipient is configurable.** `AlertNotificationListener` no longer contains
   `ops@skillars.internal`. For `notificationChannel == EMAIL` it resolves
   `platform.admin_alert_email` via `ConfigService.find(...)`; if blank it logs at ERROR (naming the
   key and metric, same shape as `VideoModerationEmailListener`) and sends **nothing**; it must not
   throw into the scheduling loop. Unit tests cover configured, blank and `mailManager` failure.
2. **`RegistrationEmailDurabilityIT` scheduler cases are order-independent.** The three
   `retryFailedEmails()` cases (~L330/343/370) assert only on rows they created and cannot be
   changed by `FAILED`/`retry=true` rows left behind by other tests (e.g. `@BeforeEach` parks foreign
   retryable rows in a committed `transactionTemplate` — bare `jdbcTemplate` writes never commit,
   see project note — and the test restores nothing it does not own). No production-code change just
   for the test. Demonstrate by adding a deliberately foreign retryable row in one case.
3. *Dropped by owner decision (index on `main."user"`).*
4. **Per D161-B**: outcome is exactly one of (a) toolchain upgraded with `npx quasar build` and
   `npm run test:unit:ci` green, `quasar.config.js` registers the AE, the hand-placed config note in
   the ledger deleted; (b) reverted with the ledger bullet re-annotated with today's verified
   blocker versions (app-vite 2.4.1 vs AE 3.0.0 peers `^3`/vitest `^4`). Never a half-upgrade.
5. **Per D161-C**: outcome recorded in the ledger (and code only if option 2/3).
6. **Per D161-D**: no `ConfigService` caller can hold the JVM-wide monitor while waiting on a DB
   connection (or the erase path no longer triggers the refresh inside its transaction). Test: a
   concurrent-caller test proves a slow `findAll()` does not block cache hits.
7. **Per D161-E**: `sprint-status.yaml` shrinks materially (target < 100 KB of comments removed or
   whatever the trim yields), still parses as YAML, `development_status` entries untouched; convention
   note added to both ledger headers.
8. *Dropped by owner decision (operator detection).*
9. **Ledger hygiene.** All six in-scope bullets (AC 1, 2, 4, 5, 6, 7) deleted if closed by code, or re-annotated `[DECIDED …]`
   with date and story id if closed by decision. No other ledger text changed.

## Tasks / Subtasks

- [ ] Task 1 — Alert recipient (AC: 1)
  - [ ] Read `AlertNotificationListener`, `VideoModerationEmailListener.adminEmail()` pattern
  - [ ] Replace hardcode with `ConfigService.find("platform.admin_alert_email")`; blank → ERROR log, no send
  - [ ] Unit tests (configured / blank / mail failure); confirm `platform.admin_alert_email` is seeded
        in config migrations and documented in `docs/deployment/secrets-reference.md` (or runbook)
- [ ] Task 2 — IT isolation (AC: 2)
  - [ ] Park foreign retryable `envelope_entity` rows in `@BeforeEach` via `transactionTemplate`
  - [ ] Add a foreign-row regression case
  - [ ] Check `IntegrationTestConventionTest` / context-count gate unaffected (no new mocks/props)
- [ ] ~~Task 3~~ — dropped (index)
- [ ] Task 4 — Quasar toolchain (AC: 4), option D161-B(1); run frontend checks in CI only for full verify
  - [ ] Bump in a throwaway state first; stop-loss as in D161-B
- [ ] Task 5 — `BoundedKey` (AC: 5) — D161-C(1), no code
- [ ] Task 6 — `ConfigService` refresh (AC: 6) — D161-D(1)
  - [ ] Read `ConfigService` fully; preserve TTL/stampede behaviour; add concurrency test
- [ ] Task 7 — Ledger/status trim (AC: 7) — D161-E(1)
  - [ ] Scripted trim; verify YAML parses and story keys/statuses identical before/after
- [ ] ~~Task 8~~ — dropped (phone operators)
- [ ] Task 9 — Ledger hygiene (AC: 9), sprint-status entry to `done` on completion

## Dev Notes

- **Do not run `mvn verify` locally** (project rule); GitHub CI is the gate. Targeted single ITs/unit
  tests are fine.
- Any new `@MockitoBean/@MockitoSpyBean/@TestPropertySource` in an IT risks the Spring-context-count
  gate (`.github/scripts/assert-context-count.sh`, currently ≤24 / size < maxSize after deferred-160).
  Prefer plain beans/`transactionTemplate` in AC2 and AC6 tests.
- Bare `jdbcTemplate` writes in an IT method body do not commit (Hikari auto-commit=false) — wrap in
  `transactionTemplate`.
- CI catches Java-bytecode/dependency mismatches and stale SQL literals local runs can't; budget a
  CI round-trip for AC4.
- Re-verify each cited line against HEAD again at dev time; ~1 in 3 ledger items has historically
  been already closed by the time it is picked up.

### References

- `_bmad-output/implementation-artifacts/deferred-work.md` (bullets listed in the mapping table)
- `src/main/java/com/softropic/skillars/platform/notification/service/AlertNotificationListener.java`
- `src/main/java/com/softropic/skillars/platform/notification/infrastructure/listener/VideoModerationEmailListener.java`
- `src/main/java/com/softropic/skillars/platform/security/service/UserAdminService.java`
- `src/test/java/.../notification/infrastructure/listener/RegistrationEmailDurabilityIT.java`
- `src/frontend/package.json`, `src/frontend/vitest.config.mjs`
- `src/main/java/com/softropic/skillars/infrastructure/validation/{PhoneValidationProperties,Provider,CamMobileValidator}.java`
- `docs/deployment/migration-conventions.md`

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List
