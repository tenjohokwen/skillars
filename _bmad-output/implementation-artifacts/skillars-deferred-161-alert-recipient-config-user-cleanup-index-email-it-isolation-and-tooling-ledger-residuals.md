# Story skillars-deferred-161: Alert recipient config, email-IT isolation, Quasar toolchain and ledger residuals

Status: ready-for-dev

<!-- REVISED 2026-10-25 after story-review.md (see "Review corrections"). Created 2026-10-24 (/bmad-create-story). User-requested bundle mined from deferred-work.md on the
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
- **All other sub-decisions: the recommended option is confirmed** — D161-B time-boxed Quasar change
  (**re-scoped 2026-10-25 to the smaller AE 2.0.0 + vitest 4 hop, no app-vite bump — owner may override
  back to the app-vite 3 hop**) with revert-and-annotate stop-loss; D161-C(1) `BoundedKey` closed by decision, no code;
  D161-D(1) fix `ConfigService.refreshCache()` at the root; D161-E(1) trim to latest 5
  `prior last_updated` lines + convention note.

## Review corrections (2026-10-25, from `story-review.md`, each re-verified against HEAD)

- **AC2's premise was false → closed by verification.** `DatabaseResetTestExecutionListener`
  (order 3000, registered on `AbstractIntegrationTest`) truncates every table except
  `flyway_schema_history`/`main.shedlock`/`qrtz_*` before every test method, before `@BeforeEach`.
  Foreign `envelope_entity` rows cannot exist, and the "foreign row" case already exists
  (`retryScheduler_unrelatedStrayFailedRow_doesNotAffectScopedAssertion`, ~L362).
- **D161-B's "no compatible AE" claim was false.** AE `2.0.0` (verified via `npm view` + unpacked
  tarball) declares `compatibleWith('@quasar/app-vite','^2.4.0')`, peers `vitest ^4`, `quasar ^2.12.7`.
  A far smaller hop than app-vite 3 exists — see AC4.
- **AC6's test was vacuous** (fresh-cache hits never take the monitor) and the fix needed explicit
  semantics — see AC6.
- **AC1's code path is unreachable today** (`AlertEvaluationService.computeMetricValue` returns `-1.0`,
  so `AlertFiredEvent` is never published) and `platform.admin_alert_email` is seeded blank — the change
  is defensive, not a live SES-bounce fix.
- Factual fixes: context-count ceiling is **17** (not ≤24); `HAS_CODE_DEFAULT` has **23** keys (not 18);
  `# prior last_updated` lines are **73**; the AC7 bullet is in the deferred-**58** code-review section.

## Ledger → story mapping (so future passes do not re-pick these)

All bullets below live in `_bmad-output/implementation-artifacts/deferred-work.md` and have been
tagged `[PICKED UP by skillars-deferred-161]` there. On story completion the dev **deletes** closed
bullets (file convention) and re-annotates anything closed by decision rather than code.

| AC | Ledger bullet (lead text) | Section |
|----|---------------------------|---------|
| 1 | "`AlertNotificationListener` mails a mailbox that cannot exist" | ses-1-5 dev section (2026-10-24) |
| 2 | "`RegistrationEmailDurabilityIT`'s scheduler cases operate on global repository state" (closed by verification) | `ses-1-4-registration-email-durability` |
| 3 | *(dropped — bullet stays open, untagged)* `main."user"` cleanup-sweep index | code review of skillars-deferred-123 |
| 4 | "The Quasar Vitest App Extension is used library-only…" | skillars-deferred-104 |
| 5 | D2 "Not the full call-site migration" (`BoundedKey`, declined 4×) — tag sits mid-paragraph in a multi-closure bullet | code review of skillars-deferred-129 |
| 6 | D5 "`lock_timeout` bound is re-read … inside `erase()`'s outer transaction" | code review of skillars-deferred-129 |
| 7 | "`deferred-work.md` and `sprint-status.yaml` accumulate indefinitely…" **and** the untagged duplicate "`sprint-status.yaml`'s `last_updated` field has grown…" | code review of skillars-deferred-58 / code review of skillars-deferred-56 |
| 8 | *(dropped — marked `[DECIDED]` in ledger)* operator-level `Provider` detection CM-only | skillars-deferred-99 AC16 note |

## Verified state at drafting (HEAD 4317b019)

- `AlertNotificationListener.java:61` still hardcodes `recipient.setEmail("ops@skillars.internal")`
  (only for `notificationChannel == EMAIL`; default is `LOG`). `VideoModerationEmailListener` already
  resolves `platform.admin_alert_email` via `ConfigService.find(...)`.
- `V138__baseline_schema.sql` has no index on `main."user"` supporting
  `UserRepository.findByActivatedFalseAndCreatedDateBeforeAndCleanupFailedAtIsNullOrderByIdAsc`
  (used by `UserAdminService` ~L384). Latest migration is `V168`.
- `RegistrationEmailDurabilityIT` calls `emailRetryScheduler.retryFailedEmails()` at ~L330/343/370; the
  scheduler selects eligible rows (`LIMIT 10 … ORDER BY deadline … FOR UPDATE SKIP LOCKED`). Isolation
  is already provided by the per-test table reset (see Review corrections).
- `@quasar/app-vite` is `2.4.1` in `node_modules`; `package.json` says `^2.1.0`; vitest `^3.2.4`, quasar
  `^2.16`, AE `^1.2.4` (its `vitest.config.mjs` imports `vite-tsconfig-paths`, which is only a transitive
  dependency of AE 1.2.4). npm (checked 2026-10-25): AE `2.0.0` guards `@quasar/app-vite ^2.4.0`, peers
  `vitest ^4`, `@vitest/ui ^4`, `quasar ^2.12.7`; AE `3.0.0` needs `app-vite ^3`, `quasar ^2.24`,
  `vitest ^4 || ^5`; `@quasar/app-vite` latest is `3.10.3`.
- `BoundedKey.defaultValue` exists for all 23 `HAS_CODE_DEFAULT` keys (deferred-133 AC2); call sites
  still re-type the literal. Declined a 4th time by deferred-135.
- `GdprErasureService`: the `lock_timeout` read is already hoisted to once per `eraseParentChildren`
  (deferred-132 Fix 9); `ConfigService.refreshCache()` (`private synchronized`, `findAll()` on the
  caller's connection) is the remaining hazard.
- `sprint-status.yaml` is ~419 KB with 73 `# prior last_updated` comment lines (contiguous, newest-first,
  ~135 KB; longest line 17,765 chars); `deferred-work.md` ~351 KB / 3.3k lines, longest line ~2.5k chars.
- Phone: `PhoneValidationProperties` is config-driven per market; `Provider.java` /
  `CamMobileValidator.java` (MTN/Orange/NextTel prefixes, `V146__user_phone_provider_column.sql`)
  stay Cameroon-only.

## Sub-decisions (RESOLVED 2026-10-24 — see Owner decisions above)

Kept for the option trade-offs; the chosen option is the one marked recommended, except A and F which are dropped.

**D161-A — DROPPED.** `main."user"` index shape (AC3). The ledger blocker was "need production row-count +
EXPLAIN evidence"; there is still no production deploy.
- *(1, recommended)* Ship the partial index
  `CREATE INDEX CONCURRENTLY idx_user_cleanup_sweep ON main."user" (id) WHERE activated = false AND cleanup_failed_at IS NULL`
  in its own migration (NOTE: `docs/deployment/migration-conventions.md` rule 4 says CONCURRENTLY has no working online-safe mechanism here — Flyway self-deadlock, deferred-112; re-read it before reviving). It matches the
  query's `ORDER BY id` + predicate exactly, is tiny (only unactivated rows), and costs almost nothing
  pre-data. Validate shape with `EXPLAIN` against seeded IT data (planner choice on a tiny table is
  weak evidence — say so in the story record).
  Pros: closes the item; negligible write overhead. Cons: shape not proven on real data.
- *(2)* Keep deferring until a production deploy exists. Pros: no guess. Cons: this has been
  re-deferred repeatedly; lock hold time keeps depending on a PK walk.

**D161-B — Quasar toolchain (AC4).** (Rewritten 2026-10-25: the original text wrongly said no compatible
AE exists for app-vite 2.4.x.)
- *(0, recommended — new)* Keep `@quasar/app-vite` 2.4.1 / `quasar` 2.16. Bump `vitest`,
  `@vitest/coverage-v8` and `@vitest/ui` to 4.x and the AE to `2.0.0`, then register it with
  `quasar ext add`. Pros: removes the "AE not registered" gap without touching the app build chain.
  Cons: vitest 3→4 may need test/config adjustments; `vite-tsconfig-paths` must be added explicitly or
  `vitest.config.mjs` rewritten.
- *(1)* Full hop: app-vite 3 + quasar ≥2.24 + vitest 4/5 + AE 3.0.0. Pros: current toolchain.
  Cons: largest risk; `quasar build` (run by `mvn verify`) is in the blast radius.
- *(2)* Skip; re-verify and refresh the annotation only. Pros: zero risk. Cons: bullet stays open.
- *(3)* Own story. Pros: isolates risk.

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
- *(2)* (not chosen) Pre-read the bound before `erase()`'s transaction opens. Cons: only protects this path.

**D161-E — Ledger/status growth (AC7).**
- *(1, recommended)* Mechanical, low-risk trim: keep only the latest 5 `# prior last_updated`
  comment lines in `sprint-status.yaml` (git history keeps the rest), shorten `last_updated` to
  ≤200 chars, and add a one-line convention note at the top of both files. Do **not** rewrite
  per-story status comments.
- *(2)* Also move per-story completion narrative out to the story files. Pros: smaller file. Cons:
  broad edit across ~400 entries; high churn.

**D161-F — DROPPED.** Operator detection beyond CM (AC8).

## Acceptance Criteria

1. **Alert recipient is configurable (defensive hardening).** Note: today `AlertEvaluationService.computeMetricValue`
   returns `-1.0`, so `AlertFiredEvent` is never published and this listener is unreachable; the default
   `platform.admin_alert_email` is also seeded `''` (`V139`). The change removes a latent SES-bounce
   hazard and must not be described as fixing a live one.
   `AlertNotificationListener` no longer contains `ops@skillars.internal`. For `notificationChannel ==
   EMAIL` it resolves `platform.admin_alert_email` via `ConfigService.find(...)` **inside the existing
   try block** (a stale-cache refresh can throw; an escape would abort the remaining rules of that
   `evaluate()` tick). Blank → `log.error` naming the key **and `event.metricName()`**, send nothing.
   Direct listener unit tests (none exist today): configured, blank, `find()` throws, `mailManager`
   throws. Docs: update `docs/dev-docs/notification/index.html` (still describes mail to
   `ops@skillars.internal`) and the `AlertEvaluationService` class Javadoc ("no DB call is made during
   evaluation" is no longer strictly true); record the `platform.admin_alert_email` config row (blank by
   default) in the runbook / notification dev-doc — it is a `platform_config` row, not a secret, so not
   `secrets-reference.md`.
2. **Closed by verification — no code.** The premise (foreign `envelope_entity` rows leaking between tests)
   is false: `DatabaseResetTestExecutionListener` truncates all non-infrastructure tables before every
   test method, and `retryScheduler_unrelatedStrayFailedRow_doesNotAffectScopedAssertion` already covers
   the stray-row case. The dev re-confirms both at HEAD, then re-annotates the ledger bullet (AC9 outcome
   (c)). Do **not** add `@BeforeEach` parking. (Optional, only if wanted: a case seeding >10
   earlier-deadline foreign rows to pin `LIMIT 10 ORDER BY deadline`; a second `retryFailedEmails()` call
   in one test needs `releaseSchedulerLock` because of `@SchedulerLock(lockAtLeastFor)`.)
3. *Dropped by owner decision (index on `main."user"`).*
4. **Per D161-B (option 0)**: outcome is exactly one of (a) `vitest`/`@vitest/coverage-v8`/`@vitest/ui` at 4.x, AE
   at `2.0.0`, registered via `quasar ext add` (confirm where it writes — likely `quasar.extensions.json`,
   not `quasar.config.js`), `vite-tsconfig-paths` declared or the config rewritten, the ledger bullet
   deleted; **verified by a manually triggered green `frontend-unit-tests.yml` run** (it uses `npm ci`
   without `--legacy-peer-deps`, is not a required check, and Maven installs with `--legacy-peer-deps`
   — a peer conflict can pass Maven and still fail there) **and** a green `npx quasar build`; (b) reverted
   with the ledger bullet re-annotated with the *actual* blocker found (not the old "no AE" claim). Never
   a half-upgrade.
5. **Per D161-C**: outcome recorded in the ledger (and code only if option 2/3).
6. **Per D161-D (option 1)**: `ConfigService.refreshCache()` no longer holds the instance monitor while calling
   `findAll()`. Required semantics: **single-flight** loader (N stale readers → one `findAll()`); the
   caller that triggers a refresh, and any read after `invalidate()`, still sees fresh data (pinned by
   `ConfigServiceTest.getString_refreshesFromDbWhenCacheStale` and the IT write→`invalidate()`→read
   sites, e.g. `ConfigGuardIT`, `SubscriptionLifecycleIT`); cache is never observed empty; an
   `invalidate()` landing mid-refresh is not lost (generation/epoch counter — today's unsynchronized
   `lastRefreshed = now()` after `findAll()` already has this race; do not widen it). Do not wrap the load
   in `REQUIRES_NEW` (double-connection hazard for in-transaction callers). Tests start from a **stale**
   cache (a test that only exercises fresh hits passes on current code and proves nothing): a slow
   `findAll()` does not serialise concurrent stale-path readers, and exactly one load runs. In-transaction
   read sites (`GdprErasureService` ~:468, :608, :951) need no change.
7. **Per D161-E (option 1)**: `sprint-status.yaml` keeps only the latest 5 `# prior last_updated` lines (73 today,
   mixed formats — do not key on one), via a **comment-preserving text edit** (never `yaml.dump`);
   `development_status` untouched (diff of parsed YAML before/after equal); `last_updated` stays
   ≤200 chars with the story key first (already ~98 chars). Convention note added to the header of both
   ledger files. `deferred-work.md` itself needs no trim (longest line ~2.5k chars).
8. *Dropped by owner decision (operator detection).*
9. **Ledger hygiene.** Outcomes: (a) closed by code → delete the bullet; (b) closed by decision →
   re-annotate `[DECIDED …]` with date and story id (AC5: edit within the multi-closure D2 bullet, not a
   delete); (c) closed by verification → delete the bullet citing the evidence (AC2). Covers AC 1, 2, 4,
   5, 6, 7 **including the untagged duplicate** "`sprint-status.yaml`'s `last_updated` field has grown…"
   (deferred-56 section). No other ledger text changed.

## Tasks / Subtasks

- [ ] Task 1 — Alert recipient (AC: 1)
  - [ ] Read `AlertNotificationListener`; mirror `VideoModerationEmailListener.adminAlertEnvelope(...)`'s
        `configService.find("platform.admin_alert_email")` but **inside the try**
  - [ ] Blank → ERROR log (key + metric), no send; unit tests: configured / blank / `find()` throws / mail throws
  - [ ] Update `docs/dev-docs/notification/index.html`, `AlertEvaluationService` Javadoc, runbook/dev-doc config-row note
- [ ] Task 2 — Close AC2 by verification (AC: 2, 9c)
  - [ ] Re-confirm `DatabaseResetTestExecutionListener` truncation and the existing `:362` stray-row test; no code
- [ ] ~~Task 3~~ — dropped (index)
- [ ] Task 4 — Quasar toolchain (AC: 4), option D161-B(0)
  - [ ] Bump vitest family to 4 + AE 2.0.0 in a throwaway state first; `quasar ext add`; fix `vite-tsconfig-paths`
  - [ ] Verify with a manually triggered `frontend-unit-tests.yml` run and `npx quasar build`; stop-loss → revert + annotate actual blocker
- [ ] Task 5 — `BoundedKey` (AC: 5) — D161-C(1), no code; annotate within the D2 bullet
- [ ] Task 6 — `ConfigService` refresh (AC: 6) — D161-D(1)
  - [ ] Read `ConfigService` + `ConfigServiceTest` + the invalidate-then-read ITs first
  - [ ] Single-flight loader + epoch counter; stale-cache concurrency tests
- [ ] Task 7 — Status trim (AC: 7) — D161-E(1)
  - [ ] Scripted comment-preserving trim; parsed-YAML before/after equality check; header notes in both files
- [ ] ~~Task 8~~ — dropped (phone operators)
- [ ] Task 9 — Ledger hygiene (AC: 9) incl. the deferred-56 duplicate bullet; sprint-status entry to `done` on completion

## Dev Notes

- **Do not run `mvn verify` locally** (project rule); GitHub CI is the gate. Targeted single ITs/unit
  tests are fine.
- Any new `@MockitoBean/@MockitoSpyBean/@TestPropertySource` in an IT risks the Spring-context-count
  gate (`.github/scripts/assert-context-count.sh`, `CEILING = 17` per `pr-build.yml`; plus `IntegrationTestConventionTest` pins: 14 distinct context keys, 4 `@TestPropertySource`).
  Keep the AC1/AC6 tests plain unit tests (no new Spring contexts).
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
