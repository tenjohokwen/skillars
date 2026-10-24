# Story Review — skillars-deferred-161

- **Story:** `skillars-deferred-161-alert-recipient-config-user-cleanup-index-email-it-isolation-and-tooling-ledger-residuals`
- **Audit date:** 2026-10-24
- **Real HEAD:** `967c7359 docs(deferred-161): sprint-status note`
- Citations were checked against this HEAD. The story says it was verified at `4317b019`; that was not trusted. No `src/` file differs between the two commits; only the ledger and sprint-status changed.
- **Method:** four parallel read-only verification layers (citations, ledger/precedent, mechanism, corner cases), followed by my own adversarial re-read of every surviving finding (Step 4b). For AC4, npm metadata was queried live and the AE 2.0.0 and 3.0.0 tarballs were unpacked to read their `compatibleWith` guards.

## Summary of blocking issues (fix the story before dev)

| # | AC | Issue | Severity |
|---|----|-------|----------|
| B1 | 2 | The premise is false. `DatabaseResetTestExecutionListener` truncates `envelope_entity` before every test method, so foreign rows from other tests cannot exist. The "foreign row" demonstration test already exists. | HIGH |
| B2 | 4 | "There is **no** AE release compatible with app-vite 2.4.x + a current vitest" is false. AE **2.0.0** guards `@quasar/app-vite ^2.4.0` and peers `vitest ^4`. A much smaller hop exists that D161-B never offers. | HIGH |
| B3 | 6 | The AC6 test ("slow `findAll()` does not block cache hits") already passes on current code: fresh-cache hits never take the monitor. The fix shape also conflicts with existing synchronous read-after-invalidate tests unless it specifies single-flight semantics. | HIGH |
| B4 | 1 | The code path cannot run today. `AlertEvaluationService.computeMetricValue` always returns `-1.0`, so `AlertFiredEvent` is never published. Also, the default `platform.admin_alert_email` is seeded `''`. | MED (it does not block AC1; it reframes the justification and the tests) |

---

## 1. Citation verification

| # | Story citation | Verdict | Evidence at 967c7359 |
|---|---|---|---|
| 1 | `AlertNotificationListener.java:61` hardcodes `ops@skillars.internal`, EMAIL-only, default `LOG` | MATCH | `:53` `if ("EMAIL".equalsIgnoreCase(event.notificationChannel()))`; `:61` `recipient.setEmail("ops@skillars.internal")`. LOG default: `V138__baseline_schema.sql:492`, `AlertRuleAdminResource.java:66-67` |
| 2 | `VideoModerationEmailListener` resolves `platform.admin_alert_email` via `ConfigService.find(...)`; Task 1 names `adminEmail()` | MATCH (method name drifted) | `:172` `configService.find("platform.admin_alert_email").orElse("")`. No method named `adminEmail()`: it is a local variable in `adminAlertEnvelope(...)` `:170-186` |
| 3 | `V138` has no index for `findByActivatedFalseAndCreatedDateBefore…OrderByIdAsc`; `UserAdminService` ~L384; latest migration V168 | MATCH | `UserAdminService.java:384`; `UserRepository.java:50`. `main."user"` has only pkey/email/login/phone keys (`V138:2712-2734`). Latest is `V168__envelope_entity_email_template_check_ses_cutover_preflight.sql` (AC3 dropped; informational) |
| 4 | `RegistrationEmailDurabilityIT` `retryFailedEmails()` at ~L330/343/370 | MATCH (approximate) | `src/test/java/com/softropic/skillars/platform/notification/infrastructure/listener/RegistrationEmailDurabilityIT.java` calls at ~330/343/365-370. **No `@BeforeEach` exists** (story L143/L171 describes new work) |
| 5 | app-vite `2.4.1` installed / `^2.1.0` declared; vitest `^3.2.4`; quasar `^2.16` | MATCH | `src/frontend/package.json:39,52,27`. AE installed `1.2.4` (`:40`); `vitest.config.mjs` and `quasar.config.js` exist; no `quasar.extensions.json` |
| 6 | `BoundedKey.defaultValue` exists for all **18** `HAS_CODE_DEFAULT` keys | DRIFTED | `BoundedKey` is the nested record at `ConfigBounds.java:80`. `HAS_CODE_DEFAULT` (`ConfigBounds.java:432-455`) now has **23** keys |
| 7 | `GdprErasureService` lock_timeout hoisted per `eraseParentChildren`; `refreshCache()` `private synchronized` + `findAll()` | MATCH (incomplete) | `GdprErasureService.java:943-952`; `ConfigService.java:380` `private synchronized void refreshCache()`, `:385` `findAll()`. There are also in-transaction reads at `:468-469` (COACH) and `:607-608` (PLAYER), which the story does not mention |
| 8 | sprint-status ~419 KB, **72** prior lines, longest ~17.7 KB; deferred-work ~351 KB / 3.3k lines | MATCH (count +1) | 419,837 B; **73** `# prior last_updated` lines (HEAD's commit added one); longest 17,765 chars; deferred-work 351,446 B / 3,321 lines |
| 9 | `PhoneValidationProperties` / `Provider` / `CamMobileValidator`, `V146` | MATCH | All exist under `infrastructure/validation/` (AC8 dropped; informational) |
| 10 | Context-count gate "currently **≤24** / size < maxSize after deferred-160" | **WRONG** | `pr-build.yml:88` `assert-context-count.sh build.log 17`; script default `CEILING=17`. 24 was the deferred-160 AC target and 17 is the measured value. A second gate also exists: `IntegrationTestConventionTest:89` `EXPECTED_TEST_PROPERTY_SOURCE_COUNT = 4`, `:109` `EXPECTED_DISTINCT_CONTEXT_KEYS = 14` |
| 11 | `IntegrationTestConventionTest`, `migration-conventions.md`, `secrets-reference.md` exist | MATCH | All present |
| 12 | References list paths | MATCH | Every path exists |

## 2. Ledger & precedent attribution

Sources checked for each item: `deferred-work.md`, code comments/Javadoc, `git log --grep`, and story files.

| Claim | Sources / findings | Verdict |
|---|---|---|
| 6 bullets tagged `[PICKED UP by skillars-deferred-161]` | `grep -c` = 6 ✔. The sprint-status entry (`sprint-status.yaml:438`) still says "8 ledger bullets tagged PICKED UP", which is stale after `4bf7478f` | CONFIRMED (status note stale) |
| AC1 section "ses-1-5 dev section (2026-10-24)" | `deferred-work.md:3316` under `## Deferred from: ses-1-5 (2026-10-24)` | CONFIRMED |
| AC2 section `ses-1-4-registration-email-durability` | `:1942` under code review of ses-1-4… (2026-09-12) | CONFIRMED |
| AC4 section `skillars-deferred-104` | `:1278` under `skillars-deferred-104 implementation` | CONFIRMED |
| AC5 section "deferred-129/-130" | Under `## Deferred from: code review of skillars-deferred-129` (D2 bullet `:2769-2819`). The tag sits **mid-paragraph** at `:2816` inside a multi-closure bullet, so AC9 cannot simply "delete" it | CONFIRMED (location precision needed) |
| AC6 section "code review of skillars-deferred-129" | `:2821-2839` | CONFIRMED. The bullet's own `:587-588` citation is stale; reads are now `:468/608/951` |
| AC7 section "skillars-deferred-52 story creation" | Bullet at `:1082` sits under **code review of skillars-deferred-58** (section header `:1080`), not deferred-52 (`:1040`) | **WRONG label** |
| AC8 section "skillars-deferred-99 AC16 note" | Under `## Deferred from: skillars-deferred-92 story creation and implementation` (`:1170`). "deferred-99 AC16" is the `[CLOSED by …]` annotation it sits inside | **WRONG label** (AC dropped; table-only fix) |
| deferred-133 AC2 added `defaultValue`; deferred-135 declined a 4th time | Ledger `:2801`, `:2818`; deferred-133 story; deferred-135 story L37-40; `ConfigBoundsEnumCoverageTest:122` | CONFIRMED (key count is 23, not 18) |
| deferred-132 Fix 9 hoisted lock_timeout | Ledger `:2833` "[CLOSED by skillars-deferred-132 AC2 Fix 9, PARENT branch only]"; code `:944-952` | CONFIRMED |
| `refreshCache()` is the remaining hazard | AC6 bullet text names it explicitly ("mitigates frequency, not the underlying hazard class") | CONFIRMED |
| `committedRowFor` already fixed | IT `:89-97` Javadoc cites deferred-129 AC3; commit `1746965d` | CONFIRMED |
| "Bare jdbcTemplate writes don't commit — see project note" | `application.yaml:183` `auto-commit: false`, not overridden in `application-test.yaml`; documented in several IT comments. The "project note" is the user's memory file, not a repo doc | CONFIRMED |
| "~1 in 3 ledger items already closed" | No repo source; only the user's memory note | CONFIRMED as a heuristic (no repo evidence) |
| D161-A "convention: hot-table index = CONCURRENTLY, own file" | `docs/deployment/migration-conventions.md:34-37` and rule 4 (`:95-138`): CONCURRENTLY has **no working online-safe mechanism** in this codebase (Flyway self-deadlock, deferred-112) | **WRONG** (AC3 dropped; correct the option text before anyone revives it) |
| Untagged duplicate of AC7 | `:1074` (deferred-56 code review): "`sprint-status.yaml`'s `last_updated` field has grown into a single, unbounded YAML comment line…". It is not tagged, so AC9's "No other ledger text changed" would leave it behind | MISSED ITEM |

## 3. Mechanistic claims

1. **"AC1 … must not throw into the scheduling loop."**
   `AlertNotificationListener` is a plain synchronous `@EventListener(AlertFiredEvent.class)` (no `@Async`/`@Transactional`). Its only publisher is `AlertEvaluationService.evaluate()`, `@Scheduled(fixedDelayString="${alert.evaluation-interval-ms:30000}")`, which loops over rules and publishes inline.
   **CORRECT.** An exception escaping the listener aborts the remaining rules for that tick.
   **But:** `computeMetricValue` (`AlertEvaluationService.java:84-88`) is `return -1.0; // TODO`, and `evaluate()` `continue`s on `< 0`. The event is never published. **The listener is dead code today**, so the "SES bounce-rate risk" is latent, not live.

2. **"same shape as `VideoModerationEmailListener` (naming the key and metric)."**
   `VideoModerationEmailListener:174`: `log.error("platform.admin_alert_email config key is blank — admin alert NOT sent: {}", event.subject())`. It names the key and the subject, **not a metric**.
   **PARTIALLY CORRECT.** For AC1 that is harmless; the listener has `event.metricName()` to log.

3. **"`ConfigService.find(...)`" in the blank case.**
   `ConfigService.java:195-198` returns `Optional<String>`. Seeded value: `V139__baseline_seed_data.sql:96` `('platform.admin_alert_email', '', …) ON CONFLICT DO NOTHING`.
   **CORRECT.** But "blank" is the **default** in every environment, so post-AC1 behaviour is ERROR-and-drop until an operator sets the row. The key is not in `secrets-reference.md` or the runbook. It is a DB `platform_config` row, not a secret, so `secrets-reference.md` is arguably the wrong home.

4. **"`refreshCache()` … `findAll()` on the caller's connection … stall the JVM while waiting on a Hikari connection."**
   `ConfigService.java:374-393`:
   - `ensureFresh()` does an unsynchronized TTL check.
   - `refreshCache()` is `synchronized` on the singleton instance, with a TTL double-check, then `findAll()`, `putAll`/`retainAll`, and `lastRefreshed = now()`.
   - `PlatformConfigRepository.findAll()` inherits Spring Data's `@Transactional(readOnly=true)`, `REQUIRED`.

   **PARTIALLY CORRECT.**
   - A caller **inside** a transaction (e.g. GDPR `eraseTransactional`, `REQUIRES_NEW` at `GdprErasureService.java:370-371`) runs `findAll()` on the connection it already holds, so it does **not** wait on Hikari.
   - A caller **without** a transaction (schedulers, listeners, `open-in-view: false`) borrows a connection while holding the monitor, for up to `connection-timeout: 30000`.
   - The real hazard is the convoy: every stale-cache reader blocks behind that thread, including transaction-holding erasure threads.
   - "JVM-wide" is loose: the monitor is per-bean, effectively app-wide.

5. **AC6 test: "a slow `findAll()` does not block cache hits."**
   Fresh hits never reach `refreshCache()`.
   **WRONG as a test design.** It passes on today's code. The test must start from a **stale** cache (`invalidate()` or TTL 0) and prove stale-path readers are not serialised behind a slow loader.

6. **"`retryFailedEmails()` polls the whole `envelope_entity` table."**
   `EnvelopeEntityRepository.java:18`: `… WHERE e.retry = 'true' AND e.status = 'FAILED' ORDER BY e.deadline LIMIT 10 FOR UPDATE SKIP LOCKED`.
   **PARTIALLY CORRECT.** It is all *eligible* rows, capped at 10 and ordered by deadline. If isolation mattered, starvation by earlier-deadline rows would be the real risk, and the story never mentions it.

7. **"rows left behind by other tests" (AC2).**
   - `DatabaseResetTestExecutionListener.beforeTestMethod` (`:108`) runs before `@BeforeEach` (Javadoc `:43`) and truncates every table except `flyway_schema_history`, `main.shedlock`, `qrtz_*` (`isInfrastructureTable` `:494-497`, applied `:526`).
   - It is registered for all ITs at `AbstractIntegrationTest.java:106`.
   - There is a single forked JVM and no parallel test methods.

   **WRONG.** Foreign rows cannot exist at `@BeforeEach`. The listener predates the ledger bullet (b1d8c831 vs 0bf1fc96). Parking would always find an empty table. The requested demonstration already exists: `retryScheduler_unrelatedStrayFailedRow_doesNotAffectScopedAssertion` (`RegistrationEmailDurabilityIT.java:362`).

8. **"`quasar.config.js` registers the AE" (AC4a).**
   Quasar CLI's `ext add` records extensions in `quasar.extensions.json`, and none exists today.
   **PLAUSIBLE (medium confidence).** This rests on Quasar CLI convention and was not executed. The dev should confirm where `ext add` writes before making it the success check.

9. **"`npx quasar build` (used by `mvn verify`) or the Vitest suite" gate (D161-B).**
   - `pom.xml:596` `install --legacy-peer-deps`; `:635-641` `quasar build`.
   - Vitest is not run by Maven (`pom.xml:614-616`).
   - `frontend-unit-tests.yml:147` uses `npm ci` (no legacy flag), and its header says "NOT a required status check".

   **CORRECT, with a caveat.** A peer conflict can pass the Maven install yet fail `npm ci` in the Vitest job, and a red Vitest job does not block merge. "Green" has to be checked by hand on the PR.

10. **AC7 trim layout.**
    - `# prior last_updated` lines are contiguous at L3-75 (73 lines, ≈135 KB, newest-first).
    - `development_status:` starts at L92.
    - No script under `.claude/`, `_bmad/` or `.github/` parses the prior lines.
    - The `mto-story-review` skill only parses the first key in the `last_updated` comment.

    **CORRECT.** Keeping 5 removes ≈134 KB. `last_updated` is already 98 chars, so "shorten to ≤200" is a no-op. The trim must be a comment-preserving text edit, not `yaml.dump`.

## 4. Corner cases / false assumptions / missed flows (survived Step 4b)

**HIGH**

- **C1 (AC2) — false premise; the AC as written is a no-op.**
  - Evidence: mechanism #7.
  - Recommend closing AC2 *by verification*: re-annotate the bullet as "already isolated by DatabaseResetTestExecutionListener (Deferred-19); stray-row case exists at `:362`".
  - If a guard is still wanted, the only meaningful test seeds **>10** earlier-deadline foreign FAILED/retry rows to pin the `LIMIT 10 ORDER BY deadline` behaviour.
  - Note that "closed by verification" fits neither AC9 category; AC9 needs a third.

- **C2 (AC4) — the "no compatible AE" claim is false.**
  - `npm view @quasar/quasar-app-extension-testing-unit-vitest versions`: `… 1.2.4, 2.0.0, 3.0.0-alpha.0, 3.0.0-alpha.1, 3.0.0`.
  - 2.0.0 peers: `@quasar/app-vite ^2.4.0`, `vitest ^4.0.0`, `@vitest/ui ^4.0.0`, `quasar ^2.12.7`. Its `src/index.js:12` has `api.compatibleWith('@quasar/app-vite', '^2.4.0')`. By contrast, 3.0.0 `src/index.ts:11-12` requires `quasar ^2.24.0` and `app-vite ^3.0.0`.
  - So there is an option D161-B(0): keep app-vite 2.4.1 / quasar 2.16; bump `vitest`, `@vitest/coverage-v8` (`package.json:41`, `^3.2.4`) and `@vitest/ui` to 4 and the AE to 2.0.0. That is far smaller than the recommended full app-vite 3 hop.
  - AC4(b)'s prescribed re-annotation text would record a false blocker.
  - **The owner should re-decide D161-B.**

- **C3 (AC6) — the test proves nothing, and the fix needs explicit semantics.**
  - Evidence: mechanism #5, plus `ConfigServiceTest.getString_refreshesFromDbWhenCacheStale` (`:90-102`): `invalidate()`, then `getString("k")` must return `"v2"` with `times(2) findAll()`. That pins synchronous refresh for the refreshing caller.
  - ITs also do write → `invalidate()` → read and expect the new value; Layer 4 cited ~9 sites, e.g. `ConfigGuardIT`, `SubscriptionLifecycleIT`. I did not re-read each one.
  - "Load outside the monitor" without single-flight lets N stale readers run N concurrent `findAll()` calls, breaking "preserve stampede behaviour".
  - The AC must state the semantics: single-flight loader; the caller that triggered the refresh (or the one after `invalidate()`) still sees fresh data; other stale readers either wait or serve stale.

**MED**

- **C4 (AC1) — the config read must sit inside the listener's try.**
  - `find()` can throw when a stale refresh's `findAll()` fails: the listener thread has no transaction, so it borrows a connection.
  - `VideoModerationEmailListener:172` does the read outside any try. Copying that shape into `AlertNotificationListener` would let an exception escape into `evaluate()`'s rule loop.
  - Add a "find throws" unit test. AC1's "mailManager failure" case is mostly mock-only, since `MailManager.sendEmailSync` catches transport failures itself.

- **C5 (AC1) — the default is blank, and the docs are stale.**
  - Evidence: mechanism #3.
  - `docs/dev-docs/notification/index.html:83,127,296` (per Layer 4) still describe mail to `ops@skillars.internal`; no task updates them.
  - Task 1's "documented in secrets-reference.md" target is wrong: this is a DB config row.
  - `AlertEvaluationService` class Javadoc (`:23-24`, per Layer 4) says "No DB call is made during evaluation". A ConfigService refresh in the listener can now make one.

- **C6 (AC1) — reframe the justification.**
  - The listener is unreachable today (mechanism #1). The change is defensive, so the story should not claim it removes a live SES bounce risk.
  - Only direct listener unit tests can cover it, and no existing tests reference the class, so nothing breaks.

- **C7 (AC6) — lost-invalidation race.**
  - `invalidate()` (`:370-372`) is unsynchronized, and `refreshCache` sets `lastRefreshed = now()` *after* `findAll()`.
  - An `invalidate()` landing mid-refresh, with a snapshot taken before the writer's commit, is lost for up to 300 s. Moving `findAll()` outside the monitor widens this.
  - A generation/epoch counter closes it. This already exists today; the fix should not make it worse.
  - Do not move `findAll()` into `REQUIRES_NEW`: callers already in a transaction would hold two connections each.

- **C8 (AC4 option 1) — the full hop also breaks the hand-written config.**
  - `vitest.config.mjs:4` imports `vite-tsconfig-paths`. That package is not in `package.json`; it reaches the repo only transitively via AE 1.2.4.
  - AE 3.0.0's dependencies (unpacked tarball) do not include it, so the bump must add it explicitly or rewrite the config.

- **C9 (AC4 gate) — two install paths, and the suite is not a merge gate.**
  - Evidence: mechanism #9.
  - AC4(a) should require a green `frontend-unit-tests.yml` run, triggered and checked by hand.

- **C10 (AC7/AC9) — partial closure.**
  - The AC7 bullet covers **both** files. Its "one line already exceeds 40,000 characters" is stale: the longest lines are 17,765 chars in sprint-status and 2,545 in deferred-work.
  - Trimming only `sprint-status.yaml` and then deleting the bullet as "closed by code" would overclaim for `deferred-work.md`.
  - The untagged duplicate at `:1074` is out of AC9's allowed edit set.

**LOW**

- **C11 (Dev Notes):** the context-count ceiling is **17**, not ≤24. Also mention the `IntegrationTestConventionTest` pins (14 keys / 4 `@TestPropertySource`).
- **C12 (AC5 decision text):** "18" should be "23" `HAS_CODE_DEFAULT` keys. The 5 newer keys carry real defaults.
- **C13 (AC6 wording):** "or the erase path no longer triggers the refresh inside its transaction" is option D161-D(2), which the owner did not choose. Drop it, or make it an explicit fallback.
- **C14 (ledger hygiene):** the AC8 `[DECIDED …]` edit in `4bf7478f` replaced `**[PICKED UP…]**.]` with `. [DECIDED …]`. That removed the `]` closing the enclosing `` `[CLOSED by skillars-deferred-99 AC16 … `` span (`deferred-work.md:1202`).
  - The fragment at `:1197-1203` also reads as an orphan attached to the "W6's premise" bullet. Layer 2 attributes the missing lead line to earlier commit `f28a9818`; I did not independently confirm that commit.
- **C15 (AC7 numbers):** 73 prior lines, not 72. The line formats vary (`'2026-10-24'  #` vs `2026-10-04 --`), so the trim script must not key on one format.
- **C16 (sprint-status note):** `sprint-status.yaml:438` says "8 ledger bullets tagged PICKED UP"; it is 6 now.
- **C17 (AC2 side note):** `retryFailedEmails` has `@SchedulerLock(lockAtLeastFor="${email.retry.lock-at-least:PT10S}")`. A second call within one test is silently skipped. This is irrelevant today, since each case calls it once and the reset listener backdates ShedLock per test. Any new multi-call case would need `releaseSchedulerLock`.

## 5. What did not survive re-verification

- **"AC2 parking would mutate other tests' rows and break them" (Layer 4 angle):** killed. Truncate-per-method means no other test's rows exist at `@BeforeEach`. Parking is redundant, not harmful.
- **"`LIMIT 10` starvation is a live IT risk":** downgraded to "irrelevant under truncate". It is real only for a deliberately seeded >10-row test.
- **"`ConfigService.find` throws on a missing key":** killed. It returns `Optional.empty()` (`:195-198`); only a refresh failure throws.
- **"Shortening `last_updated` breaks the mto-story-review convention":** killed. The line is 98 chars and leads with the story key; ≤200 is safe if the newest key stays first.
- **"The AE 3.0.0 / vitest ^4 blocker text in AC4(b) is internally inconsistent with the ledger's `>=2.0.0 <2.4.0` pin":** killed as an inconsistency. Both are true facts about different AE majors. The defect is the omission of 2.0.0 (C2), not a contradiction.
- **"`adminEmail()` method doesn't exist, so Task 1 is wrong":** downgraded to a naming nit. The pattern exists as a local variable in `adminAlertEnvelope`.

## 6. Recommendation

The story is **not ready for dev as written**. Per-claim confidence:

| Area | Confidence | Basis |
|---|---|---|
| C1 (AC2 premise false) | High | Re-read `DatabaseResetTestExecutionListener:43,108,494-526` and `AbstractIntegrationTest:106` myself, plus the existing `:362` test |
| C2 (AE 2.0.0 exists, guards `^2.4.0`) | High | Re-ran `npm view` and read the unpacked `compatibleWith` lines myself |
| C3 (AC6 test vacuous; semantics needed) | High | Re-read `ConfigService:365-393` and `ConfigServiceTest:90-102` myself. The ~9 IT write→invalidate→read sites are Layer 4's count, not individually re-read |
| C4/C6 (listener unreachable; read inside try) | High | Re-read `AlertEvaluationService:50-88` myself |
| C5 (blank seed) | High; docs-staleness Medium | Re-read `V139:96` myself; the dev-docs line numbers are Layer 4's |
| C7 (lost invalidation) | Medium | Follows from the code read, but no reproduction |
| C8/C9 (toolchain wiring) | High | Re-read `vitest.config.mjs:4`, `package.json`, `pom.xml:596`, `frontend-unit-tests.yml:5,147` myself |
| Mechanism #8 (`quasar.extensions.json`) | Medium | Quasar CLI convention; not executed |
| C10–C17, ledger labels | High | Gate, key count, bracket diff and section headers re-checked directly; the `f28a9818` attribution was not |

Suggested story edits before `/bmad-dev-story`:

1. **AC2:** close it by verification, or rescope it to the >10-row `LIMIT 10` guard.
2. **D161-B:** re-decide with the new option (AE 2.0.0 + vitest 4, no app-vite bump), and fix AC4(b)'s blocker text.
3. **AC6:** test from a stale cache, specify single-flight / read-after-invalidate semantics, and address the lost-invalidation race.
4. **AC1:** put the read inside the try, add a "find throws" test, note the blank default and where to document it, update the dev-docs, and reframe the justification as defensive.
5. **AC7/AC9:** cover `deferred-work.md` or re-annotate the bullet instead of deleting it; include the `:1074` duplicate; add a "closed by verification" outcome.
6. Correct the gate (17), the key count (23), the AC7/AC8 section labels, the D161-A CONCURRENTLY wording, the AC8 bracket, and the sprint-status "8 bullets" note.
