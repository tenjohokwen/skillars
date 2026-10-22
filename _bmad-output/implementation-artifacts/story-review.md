# Story Review — skillars-deferred-158-gdpr-booking-erasure-label-completeness-and-i18n-spec-guard-hardening

- **Audit date:** 2026-10-22
- **Real HEAD (checked at audit start):** `7121ace5` — feat(admin,booking): booking-erasure reason codes, availability-block scrub and test-tooling hygiene (skillars-deferred-157) (#283)
- Every citation was checked against this HEAD, not against the SHA the story names. The story's own claim ("`master` @ `7121ace5`") happens to match, but it was not trusted.
- **Method:**
  - Four parallel read-only layers: citations, ledger/precedent, mechanistic, corner-case.
  - Then a skeptical second pass by the orchestrator (Step 4b), re-reading the deciding lines for every surviving finding.
  - Layers 3 and 4 also ran real code: the current blanking functions and `vue/compiler-sfc` 3.5.29 under Node and vitest, and a full scan of all 171 scanned files. Scratch only; no repo files touched.

Files referenced below:
- `I18N` = `src/frontend/src/i18n/__tests__/i18nTemplateKeyResolutionSpec.js`
- `SVC` = `src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java`
- `TEST` = `src/test/java/com/softropic/skillars/platform/admin/service/GdprErasureServiceTest.java`

---

## 1. Citation verification

| # | Story citation | Verdict | Evidence / corrected location |
|---|---|---|---|
| C1 | `I18N:174-205`: `blankCommentSpans`, `BLOCK_COMMENT_RE`, `HTML_COMMENT_RE`, `SFC_CODE_BLOCK_RE` | MATCH | Comment block at :174. Regexes at :185-187. `function blankCommentSpans(content, file)` at :193-205. |
| C2 | `I18N:207-211` `stripLineComment` | MATCH | `:210 return line.replace(/(^\|\s)\/\/.*$/, '')` |
| C3 | `I18N:350-357` stale-entry `it(...)` | MATCH | `:355 const stale = …filter((at) => !detectedSites.has(at))`; `:356 expect(stale).toEqual([])` |
| C4 | `TEST` PLAYER timeout `:750`, PLAYER contended `:766`, PARENT timeout `~:802` | MATCH (±2 lines) | PLAYER timeout method at :749: PLFE from `bookingRepository`. PLAYER contended at :766: `CannotAcquireLockException` from `sessionCompletionDataRepository` (stub :770). PARENT timeout at :800: PLFE from `bookingRepository`. There is no PARENT contended test. |
| C5 | `SVC:1024-1036` PARENT catch | MATCH | :1024 `catch (BookingSchemaLockException e)`. :1028 `e.contended ? CHILD_BOOKING_CONTENDED : CHILD_BOOKING_LOCK_TIMEOUT`. The block ends ~:1034, `finally` at :1035. |
| C6 | `SVC:~1496-1509` booking inner try | MATCH | `try` :1495, SCD stmt :1497, bookings stmt :1503. `CannotAcquireLockException` → `(e, true)` :1504-1506. PLFE → `(e, false)` :1507-1509. |
| C7 | `SVC:~1663-1690` `BookingSchemaLockException` Javadoc | MATCH (range slightly off) | Javadoc :1663-1675, class :1676. The file ends at :1684, so "~1690" overshoots. |
| C8 | `SVC:~560-680` PLAYER outer catches and the `UNCLASSIFIED_FAILURE` path | Location MATCH; the premise is wrong (see §5 F1) | Catches at :600/:614/:638/:649. There is no catch-all. `UNCLASSIFIED_FAILURE` is raised only in `markFailed` (:773-776), which `erase()` never calls. |
| C9 | `I18N:307-326` `accept="video/*"` fixture | MATCH | :307 `it('comment blanking is region-scoped…')`; :310 fixture line |
| C10 | `I18N:1-37` module header | MATCH (range slightly off) | :18 "a pragmatic regex-based static scan (not a real parser)". The header is :1-31; imports start at :33. |
| C11 | `MIN_LITERAL_CALL_SITES = 500`, `blankPreservingNewlines`, `DYNAMIC_KEY_CALL_SITES` file:line keys, `toHaveLength` style | MATCH | :165, :189, :46-155 (26 entries), :321 |
| C12 | `src/frontend/test/vitest/collectKeyPaths.js`, `localeKeyParitySpec.js` | MATCH | Both exist. |
| C13 | `raiseErasureAlert` Javadoc and `AdminQueueService.buildSummary` reason lists | MATCH | `SVC:1058-1073`; `AdminQueueService.java:173-190` (`buildSummary` at :125) |

## 2. Ledger & precedent attribution

Sources checked for each item: `deferred-work.md`, nearby code comments/Javadoc, and `git log -S` / `git blame`. Story files were checked where named.

| # | Claim | Sources and findings | Verdict |
|---|---|---|---|
| L1 | The deferred-157 code-review section is the last in the ledger, with 4 bullets | Heading at `deferred-work.md:3434`, the last `##`. It has 4 top-level bullets, none closed. Added by 7121ace5. It mirrors the deferred-157 story's Review Findings at :262-266. | VERIFIED |
| L2 | M1–M4 wording | All four bold lead phrases match verbatim. | VERIFIED |
| L3 | M2 test "landed with deferred-157"; bullet "written against a pre-patch tree" | `git blame I18N:350-357` and `git log -S` both give **56803337 (skillars-deferred-156, #282)**. The deferred-156 story (:237) already describes the stale check. deferred-157's own AC11(a) (:198) cites "the inverse check". | **PARTIAL.** "Already implemented" is TRUE. The attribution is WRONG: the test came from deferred-156. The bullet was simply mistaken, not written against a pre-patch tree. Re-verified by orchestrator via `git blame`. |
| L4 | "Unknown Player" `Decided 2026-10-20: accept`; PDF glyph `DECIDED 2026-10-21: out of scope` | `deferred-work.md:3430`, `:3432` | VERIFIED |
| L5 | deferred-143 / deferred-154 / `main."user"` / `[deferred-19]` exclusions | `:3219-3225`, `:3262`, `:2264`/`:2418`, `:3231` | VERIFIED. "Watch-only" is a paraphrase; there is no formal DECIDED tag. |
| L6 | deferred-157 second-pass facts (QueryTimeoutException → `markFailed`/`UNCLASSIFIED_FAILURE`; commit-time PLFE reads as `CHILD_CONTENDED`) | deferred-157 story :273; ledger :3438-3441 | VERIFIED as an attribution. The *content* is only partly right for PARENT; see §5 F5. |
| L7a | "deferred-157 AC1 fixed mislabelling in the other direction" | deferred-157 AC1 (:54-60) fixed a wrong-*schema* label. The risk here is a wrong-*cause* label. | PARTIAL: loose wording, not false |
| L7b | "deferred-157 F3: every reason-code prose list must stay complete" | deferred-157 story :82, :427 | VERIFIED |
| L7c | "deferred-157 AC10 struck through stale brainstorm lines" | The strikes exist (brainstorm :169, :213, :214), but they came from the second-pass review patch under AC10, not from AC10's text. | PARTIAL, harmless |
| L7d | `collectKeyPaths.js` moved by deferred-157 | 7121ace5 diffstat; deferred-157 AC8 | VERIFIED |
| L8 | Baseline 1571 / 26 / 0 | deferred-157 story :380-382; `I18N:158-163`. Reproduced by running the scan at HEAD. | VERIFIED |
| L9 | Brainstorm doc exhausted | Priorities 1–4/4b → 12-1…12-5; Converge #14 → 12-3; #16 and #17 → deferred-155 | VERIFIED. Pattern #5/#6 → 12-3 is inferred via Pattern #9 and is not written in the doc. |
| L10 | No local `mvn verify`; the Prettier gate is in `.github/actions/` | Memory file; `.github/actions/frontend-quality/action.yml:33-36`; `ci.yml` `build-and-push needs: [test, frontend-quality]` | VERIFIED |

## 3. Mechanistic claims

| # | Claim | Deciding evidence | Verdict |
|---|---|---|---|
| K1 | Booking inner try maps `CannotAcquireLockException` → `BookingSchemaLockException(contended)` and PLFE → `(timeout)` | `SVC:1504-1509`. Catch order is correct (CALE is a PLFE subclass, confirmed via `javap` on Spring 6.2.19). Both statements are `booking`-schema. The GdprErasureIT real 55P03 test pins `CHILD_BOOKING_LOCK_TIMEOUT`. | CORRECT. The mapping is not backwards. |
| K2 | A non-lock `DataAccessException` (`QueryTimeoutException`) "escapes to `markFailed` as `UNCLASSIFIED_FAILURE` and rolls the whole erasure back" | `QueryTimeoutException extends TransientDataAccessException`, not a PLFE, so no catch in `SVC` matches it. It propagates out of `erase()` (:314-327 has only `try/finally`). `markFailed` is called only by `GdprEventListener:39` and `retryFailedErasures:868-869`. | **PARTIAL.** The propagation is right. "Whole erasure rolls back" is FALSE for PARENT (earlier children's REQUIRES_NEW work has committed, `SVC:1277-1287`). Whether `UNCLASSIFIED_FAILURE` is the label that surfaces is not guaranteed (F5). |
| K3 | Both booking statements are immediate bulk UPDATEs; the tombstone is flushed explicitly | `BookingRepository:338-341` and `SessionCompletionDataRepository:41-43` are `@Modifying @Query UPDATE`. `entityManager.flush()` at `SVC:1538`. | CORRECT |
| K4 | App sets `lock_timeout`, not `statement_timeout` | `setStatementLockTimeout` (`SVC:1554-1557`) uses `set_config('lock_timeout', ?, true)`. `statement_timeout` appears in `src/main` only in a Javadoc (`PessimisticLockRetryer.java:62`). | CORRECT |
| K5 | `BookingSchemaLockException` is not a PLFE, so it passes through the outer catches and the `requiresNewTemplate` boundary | `SVC:1676 extends RuntimeException` | CORRECT |
| K6 | The PARENT catch raises one alert, marks FAILED, and does not save the tombstone | Production: yes (:1024-1034, :699; tombstone save :1530 is after the inner try). The existing PARENT timeout test (:800-818) asserts one alert and FAILED but **not** tombstone-not-saved. | CORRECT for production. AC4's "mirror" test must add an assertion the template lacks. |
| K7 | A commit-time PLFE would be labelled `CHILD_CONTENDED` | It lands at the call-site catch `DeleteStatementLockTimeoutException \| PLFE` (:614, :991) and is not a DSLTE. | CORRECT (near-unreachable per K3) |
| K8 | The regex heuristic can blank live calls via `'/*'` in a string, `'</script>'` in a string, or `'<!--'` in an attribute | Ran the current `blankCommentSpans`. `'/*'` and `'<!--'` blank live calls **only if a genuine closing delimiter follows later**. `'</script>'` ends the script region early, so a later real `/* */` stays *unblanked*. That is a loud false positive, not blindness. | PARTIAL (see F2, F3) |
| K9 | `vue/compiler-sfc` exports `parse` and `babelParse`, and babelParse ignores `"/*"` in strings | vue 3.5.29. Re-run by orchestrator: `.comments` returns only the real block and line comments. `descriptor.template.ast` keeps comment nodes (type 3, absolute offsets), **except under `NODE_ENV=production`** (re-run by orchestrator: 1 → 0 nodes). Script comment offsets are block-relative. | CORRECT, with caveats (F4) |
| K10 | No vitest alias shadows `vue` | `vitest.config.mjs` aliases only `#q-app/wrappers` | CORRECT |
| K11 | (Implicit) dropping `stripLineComment` is safe | Parser-backed blanking gives 1571/26/0 with or without `stripLineComment`. Its only live effect today is `CoachProfileBuilderPlaceholderPage.vue:107` (a `//` comment containing `te(key) ? t(key)`). It also currently strips `// …` in templates, which is semantically wrong there. | CORRECT; removing it is safe |

## 4. Corner cases / false assumptions / missed flows (survived Step 4b)

### F1 — HIGH: AC5(ii)'s expected assertion cannot be produced by `erase()`

AC5(ii) says "Option A: expect `UNCLASSIFIED_FAILURE` and `FAILED`" for a non-lock `DataAccessException`.

**What the code does:**
- In a `GdprErasureServiceTest` that calls `service.erase(...)`, the `QueryTimeoutException` is **thrown out of `erase()`**. No alert is raised, and the request status stays `PROCESSING`.
- `UNCLASSIFIED_FAILURE` is only added by `markFailed` (`SVC:773-776`). Only `GdprEventListener.java:39` and `retryFailedErasures` (`SVC:868-869`) call it.
- If the dev reuses `stubPlayerErasurePreamble`, its strict `when(adminAlertRepository.findFirstByReferenceIdAndTypeAndStatus…)` stub (`TEST:741-743`, `MockitoExtension` at :90) goes unused. That raises `UnnecessaryStubbingException`.

**Mitigation:** the story does say "check what the real PLAYER-branch path does before asserting, do not assume". The expected outcome it names is still wrong.

**Fix:** pin (a) `assertThatThrownBy(() -> service.erase(...)).isInstanceOf(QueryTimeoutException.class)`, plus no `adminAlertRepository.saveAndFlush`, no tombstone save, and no downstream steps. The `markFailed` → `UNCLASSIFIED_FAILURE` half is already pinned by `markFailed_requestPresent_noExistingAlert_savesUnclassifiedFailureAlert` (`TEST:443`). Alternatively, (b) drive it end-to-end through `retryFailedErasures`.

### F2 — HIGH: AC1's `'</script>' inside a JS string literal` fixture is impossible in `.vue` and vacuous in `.js`

- **In `.vue`:** the real SFC parser also ends `<script>` at the first `</script>`. Re-run by orchestrator: `parse()` returns `errors: ['Invalid end tag.']`, script content is `"\nconst s='"`, and `babelParse` throws "Unterminated string constant". Under AC2's fail-loudly policy the fixture *throws*. It cannot "leave live t() calls scannable". Vue itself cannot compile such a file, so it is not a real-world hazard.
- **In `.js`:** the old code never treats `</script>` specially (`I18N:194-196`), so the fixture passes on old and new code alike and proves nothing.

**Fix:** restate it as "an unparseable `.vue` file fails loudly, naming the file" (which also directly tests the AC2 policy), and drop the "live calls stay scannable" expectation for it.

### F3 — MED: the `/*` and `<!--` fixtures only expose the old bug if a genuine closing delimiter appears later

A `'/*'` string with no later `*/`, or `title="<!--"` with no later `-->`, leaves the live call intact under the *old* code. A fixture written that way passes before and after the change and does not prove the fix.

AC1 should require each of these fixtures to contain a later genuine `/* … */` (resp. `<!-- … -->`) in the same input, with a live call between them.

### F4 — MED: AC2's equivalence proof cannot detect the regressions it is meant to catch, and the counts are not printed

- Scanning with **no comment blanking at all** also gives 1571 / 26 / 0. No current file has a translation call inside `/* */` or `<!-- -->`. So a parser implementation that silently blanks nothing (e.g. template comments lost under `NODE_ENV=production`, see K9) still "matches the baseline". Layer 4 ran this scan; the orchestrator did not re-run it.
- The fixtures are the only real guard. The story should say so explicitly, and should name `parse(src, { templateParseOptions: { comments: true } })` so template comment nodes survive regardless of `NODE_ENV`. CI sets `NODE_ENV: production` only on the ESLint step (`frontend-quality/action.yml:51`), not on vitest, so today's CI risk is low. A dev shell could still hit it.
- The spec does not log the counts. The dev needs a temporary `console.log` (or equivalent) to record them, and the story doesn't mention this. "Wrapped" is a real bucket (`wrappedCalls`, `I18N:~240, ~275`).

### F5 — MED: Option A's rationale overstates what the operator sees

AC5 says a non-lock exception "escapes to `markFailed` as `UNCLASSIFIED_FAILURE` and rolls the whole erasure back". Two problems:

- **Rollback is per-child for PARENT.** Earlier children's deletes, tombstones and alerts committed in their own REQUIRES_NEW transactions (`SVC:1277-1287`, "tradeoff (a)"). Only the current child and the outer transaction roll back.
- **Dedup is reason-blind.** `insertErasureAlertIfAbsent` Javadoc (`SVC:~1127-1140`; test `markFailed_priorAlertAlreadyOpenForRequest_doesNotRaiseSecondAlert`, `TEST:469`). Re-read by orchestrator. If an earlier child already raised e.g. `CHILD_VANISHED`/`CHILD_BOOKING_*`, or a previous run left an OPEN alert, `markFailed`'s `UNCLASSIFIED_FAILURE` is suppressed and the operator sees the older reason.

This doesn't change the Option A recommendation. It does mean the AC5(i) code comment must not repeat either claim as stated.

### F6 — MED: AC3's ledger marker would record the wrong history

The stale-entry check landed with **skillars-deferred-156 (56803337)**, not deferred-157 (see L3). The marker and Completion Notes should say: "landed with skillars-deferred-156 (56803337); the deferred-157 review bullet was mistaken when written. deferred-157 AC11(a) itself cites the inverse check." Correct the Source table's M2 row too.

### F7 — LOW-MED: HTML comments at the SFC top level lose coverage

The old code blanks `<!-- -->` anywhere outside script/style, including the SFC top level (by design, per the `I18N:182-183` comment). `descriptor.template.ast` covers only the inside of `<template>`, and the story's regex fallback is also scoped to the template block. There are no instances today. The dev should either handle the top level (e.g. offset-scoped regex outside all descriptor blocks) or state the gap in the header comment.

### F8 — LOW-MED: AC4's "whichever the PLAYER pair does not already cover" is muddled

- The PLAYER tests already exercise both statements: bookings × PLFE and SCD × CALE.
- The PARENT contended test should stub `bookingRepository.anonymizeNotesAndPlayerRequestByPlayerId(child.getId())` with `CannotAcquireLockException`. That covers bookings × contended for the first time.
- SCD × lock-timeout remains untested on both branches. Optional, but worth naming.
- AC4 asks to assert "not save the child's tombstone", which the mirrored PARENT timeout test does not do (K6). Fine to add; just be aware the template lacks it.

### F9 — LOW: scope gaps to state precisely in the new header comment

Neither the old nor the new design covers JS comments inside template expressions (`{{ /* $t('x') */ }}`, `@click="… // …"`). AC1 also doesn't mention `<style>` blocks, which the old code blanked `/* */` inside. No instances exist today. Dropping `stripLineComment` also stops treating `// …` in templates as a comment, which is more correct.

### F10 — LOW: Option B is under-scoped

The `BookingSchemaLockException` constructor takes a `PessimisticLockingFailureException` (`SVC:~1679`), so Option B needs a retype. Option B would also turn a non-lock failure from full rollback (PLAYER) into skip-and-commit-partial + `FAILED`, which is a behaviour change, not just a relabel. This doesn't matter if Option A is kept.

### F11 — LOW: housekeeping

- AC6 restates the M2/M4 markers already required by AC3/AC5(iii). There are four markers in total, consistent with Task 3.1, but there's a risk of writing duplicates.
- The header range is `:1-31`, not `:1-37`; the Javadoc range is `:1663-1675`, not `~1690`.
- The `typescript` babel plugin is harmless today: all 171 scanned files parse with 0 failures and 0 SFC errors, and none use `lang="ts"`. Gating it on `lang="ts"` would be slightly more faithful for plain JS.

## 5. What did not survive re-verification

- **"The CannotAcquireLockException → CONTENDED mapping might be backwards"** (raised as a probe in Layer 4's brief). Killed. `SVC:1504-1506` maps CALE (deadlock, 40P01) to `contended=true` → `CHILD_BOOKING_CONTENDED`, and the real-lock IT pins 55P03 as `CHILD_BOOKING_LOCK_TIMEOUT`. The story's AC4 is correct.
- **"Fail-loudly parse policy will break CI on existing files."** Killed. All 171 scanned files (121 `.vue`, 50 `.js`) parse with 0 babel throws and 0 `parse()` errors; there is no JSX, no `<script src>`, no non-JS `lang`.
- **"`vue/compiler-sfc` may not resolve under vitest / may be shadowed by an alias."** Killed. It resolves under vitest 3.2.7 with happy-dom, and `vitest.config.mjs` has no `vue` alias.
- **"`NODE_ENV=production` in CI will silently drop template comments."** Downgraded from HIGH to part of MED F4. The orchestrator re-read `frontend-quality/action.yml:43-52`: `NODE_ENV: production` is scoped to the ESLint step only, so vitest in CI runs with `NODE_ENV=test`. It remains a real local-shell and future-config hazard, so the explicit `comments: true` stays recommended.
- **"The ledger section isn't actually the last / has more than four bullets."** Killed. It is the last `##` with exactly 4 top-level bullets; bullet 4 has 2 sub-bullets.
- **L7a/L7c wording issues.** Kept as PARTIAL in §2 but not raised as findings: loose phrasing, no impact on implementation.

## 6. Recommendation

The story's diagnosis is sound. Every citation resolves at real HEAD within a few lines, and M1/M3/M4 are real open gaps. M2 really is already implemented, though by deferred-156, not deferred-157. **Amend before dev starts** on these points:

| Area | Confidence | Basis |
|---|---|---|
| F1 (AC5(ii) assertion is wrong) | High | Orchestrator re-read `erase()` (:314-327), `markFailed` (:773-776) and every `markFailed` caller |
| F2 (`</script>` fixture impossible in `.vue`) | High | Orchestrator re-ran `parse()` and `babelParse` on a minimal SFC |
| F6 (deferred-156, not 157) | High | Orchestrator re-ran `git blame` |
| F5 (PARENT partial commit; reason-blind dedup) | High | Orchestrator re-read the dedup Javadoc; the PARENT tradeoff comes from Layer 3's read of `SVC:1277-1287` |
| F3 (fixtures need a later real delimiter) | Medium-high | Layers 3 and 4 both ran the old function; not re-run by orchestrator |
| F4 (equivalence proof is non-discriminating) | Medium-high | Layer 4's no-blanking scan; not re-run by orchestrator. The `NODE_ENV` part was re-run. |
| F7–F11 | Medium | Single-layer findings, low impact; read but not independently executed |

**Not independently re-checked by the orchestrator:**
- The 171-file parse sweep.
- The three-variant count table (1571/26/0 for regex, parser with strip, parser without strip).
- Layer 2's brainstorm-doc line mapping.

These rest on one layer's run each.

**Suggested story edits:**
1. AC5(ii): replace "expect `UNCLASSIFIED_FAILURE` and `FAILED`" with the `assertThatThrownBy` shape in F1. Warn about the strict `stubPlayerErasurePreamble` stub.
2. AC5 Option A rationale and AC5(i) comment: drop "rolls the whole erasure back" for PARENT, and note reason-blind dedup.
3. AC1 fixtures: the `/*` and `<!--` cases must include a later genuine closing delimiter. Replace the `</script>` case with "unparseable `.vue` fails loudly naming the file". Name `templateParseOptions: { comments: true }` and the block-offset addition for script comments. Decide top-level SFC HTML comments (F7).
4. AC2: say the count match is necessary but not sufficient, and that the fixtures are the real guard. Mention a temporary count log.
5. AC3 and the Source table M2 row: attribute the stale-entry check to skillars-deferred-156 (56803337).
6. AC4: name `bookingRepository` + `CannotAcquireLockException` explicitly.
