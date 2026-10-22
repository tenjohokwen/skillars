# Story deferred-158: GDPR Booking-Erasure Label Completeness & i18n Spec Guard Hardening

Status: done

## Story

As an **operator triaging GDPR erasure alerts, and as a developer relying on the i18n key-resolution guard spec**,
I want **the four open items from `skillars-deferred-157`'s code review closed in one pass**: the untested PARENT-branch `CHILD_BOOKING_CONTENDED` path, the booking-statement catch that labels only `PessimisticLockingFailureException`, the regex-heuristic comment blanking in the i18n spec, and a ledger bullet that is already stale,
so that **every booking-schema failure mode has a pinned, documented alert label and the i18n guard spec can no longer be silently blinded by a string literal that looks like a comment**.

## Source

A **deliberately small, single-origin story**. Every item comes from `_bmad-output/implementation-artifacts/deferred-work.md`, section **"Deferred from: code review of skillars-deferred-157-booking-erasure-reason-codes-availability-block-scrub-and-test-tooling-hygiene (2026-10-21)"** (the last section in the file, four bullets). Citations re-verified against `master` @ `7121ace5` (the `skillars-deferred-157` merge, PR #283) at story-creation time. Line numbers drift fast; grep the symbol if one looks wrong.

**Companion brainstorming doc is exhausted. Re-verified 2026-10-22 at story creation.** Every idea in `_bmad-output/brainstorming/brainstorming-session-2026-10-14-19-55-07.md` traces to a shipped story: Priorities 1–4/4b → `skillars-12-1` … `12-5`; Converge #14 → `12-3`; Converge #16 and Deferred #17 → `skillars-deferred-155`; Pattern #5 (feed-forward) and #6 (focus thread) are delivered by the Session Prep Card (`12-3`). The doc's stale status lines were already struck through by `skillars-deferred-157` AC10. **No brainstorm mining is needed; do not re-open it.**

### Ledger item → AC map (so future mining passes do not re-pick these)

| # | Ledger bullet (deferred-157 code-review section) | Verified at HEAD | AC |
|---|---|---|---|
| M1 | "i18n template-key spec comment-blanking remains a regex heuristic" | `i18nTemplateKeyResolutionSpec.js:174-205` (`blankCommentSpans`, `BLOCK_COMMENT_RE`, `HTML_COMMENT_RE`, `SFC_CODE_BLOCK_RE`), `:207-211` (`stripLineComment`); module header `:1-31` | AC1, AC2 |
| M2 | "`DYNAMIC_KEY_CALL_SITES` hygiene check does not detect stale `at` entries" | **Already implemented**: the `it('every entry in the exception list still corresponds to a real, currently-detected dynamic call site')` at `i18nTemplateKeyResolutionSpec.js:350-357` landed with **skillars-deferred-156** (`56803337`, per `git blame`), not deferred-157, and does exactly what the bullet asks. The deferred-157 review bullet was simply mistaken when written (deferred-157's own AC11(a) already cites this inverse check). | AC3 (ledger correction only, no code) |
| M3 | "No test for the PARENT-branch `CHILD_BOOKING_CONTENDED` path" | `GdprErasureServiceTest.java`: PLAYER timeout `:750`, PLAYER contended `:766`, PARENT timeout `~:802`. No PARENT contended case. Production catch at `GdprErasureService.java:1024-1036` | AC4 |
| M4 | "Booking-statement catch labels only `PessimisticLockingFailureException`" | `GdprErasureService.java:1495-1509` (inner try, `CannotAcquireLockException` / `PessimisticLockingFailureException` → `BookingSchemaLockException`) | AC5 |

### Explicitly NOT in this story (and why)

- **`"Unknown Player"` fallback** — owner `Decided 2026-10-20: accept`. **PDF non-Latin glyph drop** — owner `DECIDED 2026-10-21: out of scope`. Do not touch either bullet.
- **`markUsedByTokenHash`/`markAllUsedByUserId` unguarded against transient DB failure** (deferred-143) — the bullet's own "when picked up" says only if observed in production. Not observed.
- **deferred-154 `(\d+)` route-constraint specs** — scoped "revisit only if it reaches production". **`/api/disputes`, review-flag, messaging report UI** — distinct features, not hygiene. **fr-FR/de-DE native review, deferred-140 browser walkthrough** — human-only. **`main."user"` cleanup-sweep index** — owner-blocked on production evidence. **`[deferred-19]` reset-mean drift** — runner variance, watch-only.

## Acceptance Criteria

### i18n spec: parser-backed comment detection (M1)

1. **Comments are located by a real parser, not by regex.** Replace the regex heuristic in `blankCommentSpans` (`BLOCK_COMMENT_RE` / `HTML_COMMENT_RE` / `SFC_CODE_BLOCK_RE` and the naive `stripLineComment`) with parser-derived comment ranges, using only what `vue` already ships (no new dependency, no new `package.json` entry):
   - `.vue`: `import { parse, babelParse } from 'vue/compiler-sfc'` (resolves under vitest; `vitest.config.mjs` has no `vue` alias). `parse()` yields the `<template>`, `<script>`, `<script setup>`, `<style>` blocks with source offsets. For script blocks, `babelParse(code, { sourceType: 'module', plugins: ['typescript'] })` returns `.comments` with `start`/`end` **relative to the block content**, so add the block's content start offset to get file offsets (verified: it ignores a `"/*"` inside a string literal and reports only the real comments). Gate the `typescript` plugin on `lang="ts"` if convenient; it is harmless otherwise (no scanned file uses it today).
   - Template HTML comments: use `parse(src, { templateParseOptions: { comments: true } })` and read the comment nodes (type 3, absolute offsets) from `descriptor.template.ast`. **The `comments: true` option is required**: Vue drops template comment nodes under `NODE_ENV=production`, which would make the blanking silently do nothing in such a shell. If the AST route proves awkward, an offset-scoped `<!-- -->` regex applied only inside the template block is acceptable.
   - **Top-level SFC HTML comments** (outside every descriptor block): the old code blanked these too. Either keep an offset-scoped regex for the regions outside all descriptor blocks, or state the gap in the header comment. No instance exists today; pick one deliberately.
   - `.js`: `babelParse` over the whole file, same `.comments` ranges.
   - Known gaps, to be stated accurately in the header comment (neither old nor new design covers them; no instances today): JS comments inside template expressions (`{{ /* $t('x') */ }}`, `// …` inside an `@click`), and `<style>` blocks (the old code blanked `/* */` there; only matters if a style block ever contains a `t()`-looking call).
   - Removing `stripLineComment` is safe once parser comment ranges are applied (a scan with it removed was observed to give identical counts); its only live effect today is `CoachProfileBuilderPlaceholderPage.vue:107`, a `//` comment which the parser also covers. Removing it also stops mis-treating `// …` inside templates as a comment.
2. **Line numbers must not move.** `DYNAMIC_KEY_CALL_SITES` is keyed by `file:line`. Blank each comment range by replacing every non-newline character with a space (reuse `blankPreservingNewlines`). Keep the existing `expect(lines).toHaveLength(...)` regression style.
   - **Parse-failure policy:** if a file cannot be parsed (`babelParse` throws, or `parse()` returns `errors`), the spec must **fail loudly naming the file**, never fall back to scanning it raw. A silent fallback recreates exactly the silent-blindness this story removes. (All 171 currently scanned files, 121 `.vue` and 50 `.js`, parse cleanly, so this will not break CI.)
   - **Count equivalence is necessary but NOT sufficient.** The deferred-157 baseline is **1571 literal / 26 dynamic / 0 wrapped**, and the counts must still match, but no current file has a translation call inside a comment, so even an implementation that blanks nothing at all also yields 1571 / 26 / 0. **The fixtures below are the real guard.** The spec does not print the counts, so record them with a temporary `console.log` (literal / dynamic / wrapped) before and after, and remove it afterwards.
   - Keep `MIN_LITERAL_CALL_SITES = 500` and its comment unchanged.
   - **Regression fixtures** (inline strings, in the style of the existing `accept="video/*"` one at `:307-326`). Each "live call stays scannable" fixture MUST also contain a later genuine closing delimiter in the same input, with a live call between the string/attribute and that delimiter. Without it the fixture passes on the old regex code too and proves nothing:
     - a `/*` inside a JS string literal, then a live `t('…')`, then a real `/* … */` later;
     - a `<!--` inside a template attribute value, then a live `$t('…')`, then a real `<!-- … -->` later;
     - a genuine `/* $t('old.key') */` and a genuine `<!-- $t('old.key') -->`, asserting both are blanked;
     - an **unparseable `.vue` input** (e.g. `</script>` inside a JS string literal, which the real SFC parser also treats as the end of the script block) asserting the scan **throws naming the file**. Do NOT write this one as "live calls stay scannable": it is not a valid SFC, and as a `.js` fixture it would pass on the old code too.
   - Update the module-header comment (`:1-31`), which currently says "a pragmatic regex-based static scan (not a real parser)". Be accurate: comment detection is parser-backed, call-site extraction is still line-based regex (that part is **not** being changed here).

### Ledger correction (M2)

3. **M2 is closed by annotation, not code.** Add a `[CLOSED by skillars-deferred-158 AC3 as ALREADY-IMPLEMENTED: …]` marker under the M2 bullet in `deferred-work.md` naming the existing test (`i18nTemplateKeyResolutionSpec.js:350-357`) and stating it landed with **skillars-deferred-156** (`56803337`), that the deferred-157 review bullet was mistaken when written, and that deferred-157's own AC11(a) cites this inverse check. Do **not** add a duplicate test. In Completion Notes, say plainly that this ledger bullet was stale and why.

### GdprErasureService: booking-statement coverage and labelling (M3, M4)

4. **PARENT-branch contended path is pinned.** Add to `GdprErasureServiceTest` a case mirroring `erase_parentUser_childBookingStatementLockTimeout_raisesChildBookingLockTimeoutReason_marksFailed` (`:800`), but stubbing `bookingRepository.anonymizeNotesAndPlayerRequestByPlayerId(child.getId())` to throw `CannotAcquireLockException`. This is the first test of bookings × contended (the PLAYER pair already covers bookings × lock-timeout and `session_completion_data` × contended). It must raise exactly one alert with reason `CHILD_BOOKING_CONTENDED` and mark the request `FAILED`. Also assert the child's tombstone is **not** saved: the mirrored template does not assert this today, so it is an addition, not a copy. Name it e.g. `erase_parentUser_childBookingStatementContended_raisesChildBookingContendedReason_marksFailed`. (`session_completion_data` × lock-timeout remains untested on both branches; optionally add it.)
5. **Decide and document the catch breadth (M4). Recommendation: document, do not widen.**
   - **Recommended (Option A): no behaviour change.** Facts: a non-lock `DataAccessException` (e.g. `QueryTimeoutException`, a `TransientDataAccessException`) is not a `PessimisticLockingFailureException`, so no catch in `GdprErasureService` matches it. It propagates out of `erase()`. The request is then routed to `markFailed` (via `GdprEventListener` after the transaction rolls back, or `retryFailedErasures`), which raises `UNCLASSIFIED_FAILURE`. A commit-time PLFE would read as `CHILD_CONTENDED`, but is close to unreachable (both booking statements are immediate bulk UPDATEs and the tombstone is flushed explicitly, so nothing is pending at commit). `UNCLASSIFIED_FAILURE` is an honest label for "something we did not predict"; widening to `DataAccessException` would mislabel e.g. a bad-SQL or connection error as a booking-lock problem.
     - Pros: no new reason code, no change to alert vocabulary tests, no mislabelling risk. Cons: a commit-time lock trip, if it ever occurs, is labelled `CHILD_CONTENDED` (development) rather than `CHILD_BOOKING_*`.
     - **Do not over-claim in the code comment.** Two caveats: (1) for the PARENT branch the rollback is **per child**, not whole-erasure: earlier children's work has already committed in its own `REQUIRES_NEW` transaction (accepted tradeoff (a), `GdprErasureService.java:~1277-1287`); (2) alert dedup is reason-blind (`insertErasureAlertIfAbsent`), so if an earlier child, or a previous run, already left an OPEN alert for this request, `markFailed`'s `UNCLASSIFIED_FAILURE` is suppressed and the operator sees the older reason.
   - Option B (not recommended): additionally catch `QueryTimeoutException` in the booking inner try and map it to `CHILD_BOOKING_LOCK_TIMEOUT`. Cons: this app sets `lock_timeout`, not `statement_timeout`, so the case is hypothetical; it conflates two causes under one code; `BookingSchemaLockException`'s constructor takes a `PessimisticLockingFailureException` and would need retyping; and it changes behaviour, not just labelling (a non-lock failure would go from full rollback on the PLAYER branch to skip-and-commit-partial + `FAILED`).
   - **Whichever is chosen, deliver:** (i) a short, accurate comment at the booking inner try in `deletePlayerDevelopmentDataInDedicatedPool` recording what is and is not labelled `CHILD_BOOKING_*` and why, honouring the two caveats above; (ii) a `GdprErasureServiceTest` case pinning the chosen behaviour for a non-lock `DataAccessException`; (iii) a `[CLOSED by skillars-deferred-158 AC5 …]` ledger marker stating the decision.
     - **Shape of the Option A pin (ii).** `erase()` does not call `markFailed`, so the test must NOT expect `UNCLASSIFIED_FAILURE` or `FAILED` from `erase()`. Use `assertThatThrownBy(() -> service.erase(REQUEST_ID, USER_ID)).isInstanceOf(QueryTimeoutException.class)` (stubbed from `bookingRepository.anonymizeNotesAndPlayerRequestByPlayerId`), plus `verify(adminAlertRepository, never()).saveAndFlush(any())` and no tombstone save. The `markFailed` → `UNCLASSIFIED_FAILURE` half is already pinned by `markFailed_requestPresent_noExistingAlert_savesUnclassifiedFailureAlert` (`:443`); do not duplicate it.
     - **Strict-stubbing trap.** `GdprErasureServiceTest` runs under `MockitoExtension` (strict stubs). `stubPlayerErasurePreamble` stubs `adminAlertRepository.findFirstByReferenceIdAndTypeAndStatus(...)` (`:741-743`), which this test never reaches, so reusing it unchanged raises `UnnecessaryStubbingException`. Use `lenient()` for that stub or build a trimmed preamble.
   - If the owner picks Option B instead, update AC text, tests and the `raiseErasureAlert` Javadoc / `AdminQueueService.buildSummary` reason lists in the same pass (deferred-157 F3: **every** reason-code prose list must stay complete).

6. **Ledger housekeeping.** Exactly four `[CLOSED by skillars-deferred-158 …]` markers in `deferred-work.md`, one per bullet: M1 and M3 here, M2 per AC3, M4 per AC5(iii). Do not write duplicates; do not delete the original text.

## Tasks / Subtasks

- [x] Task 1: Parser-backed comment blanking (AC: #1, #2)
  - [x] 1.1 Re-read `i18nTemplateKeyResolutionSpec.js` in full; capture the pre-change scan counts (expect 1571 / 26 / 0) before touching anything, via a temporary `console.log` that you remove afterwards.
  - [x] 1.2 Confirm `import { parse, babelParse } from 'vue/compiler-sfc'` resolves under vitest (it does under plain Node; check the vitest alias/`resolve` config in `vite.config`/`quasar.config` does not shadow `vue`).
  - [x] 1.3 Implement parser-derived comment ranges for `.vue` (script blocks via `babelParse`, template via AST or offset-scoped regex) and `.js`; keep `blankPreservingNewlines`; fail loudly on parse errors.
  - [x] 1.4 Remove `stripLineComment` only if the parser now covers `//` comments; otherwise keep it and say why. Re-run the counts and compare.
  - [x] 1.5 Add the four fixtures from AC1; update the module header comment.
- [x] Task 2: PARENT contended test + M4 decision (AC: #4, #5)
  - [x] 2.1 Add the PARENT contended test.
  - [x] 2.2 Read `erase()` (`:314-327`), the PLAYER branch's outer catches (`:~560-680`) and `markFailed` (`:773`), then add the non-lock `DataAccessException` pin test (`assertThatThrownBy` shape, mind the strict-stubs trap) and the explanatory comment.
- [x] Task 3: Ledger (AC: #3, #6)
  - [x] 3.1 Add the four `[CLOSED by skillars-deferred-158 …]` markers in `deferred-work.md`.

### Review Findings

Code review 2026-10-22 (/bmad-code-review). Three layers ran: Blind Hunter, Edge Case Hunter and Acceptance Auditor. 2 decision-needed (both resolved), 8 patch, 2 defer, 9 dismissed.

- [x] [Review][Decision] **Old comment-blanking coverage lost; "Known gaps" header is false.** RESOLVED 2026-10-22 (owner): option A, fix the wording only. Converted to the first [Review][Patch] below.
  - The new header (`i18nTemplateKeyResolutionSpec.js:189-191`) says neither the old regex design nor the new one covers three cases. The old design actually covered all three:
    - `/* */` inside `<style>`: `SFC_CODE_BLOCK_RE` matched `script|style`.
    - `<!-- -->` at the SFC top level: `HTML_COMMENT_RE` ran outside all blocks.
    - `// …` after whitespace on any line, template expressions and SCSS included: `stripLineComment`.
  - The new code drops all three. No call sits in any of these places today: Edge Case Hunter found per-line call counts identical across all 171 files. The failure mode is loud (a commented-out key would be scanned as live and would fail), not silent.
  - This traces back to amended AC1 line 41, which contradicts itself ("neither old nor new design covers them … the old code blanked `/* */` there").
  - Options:
    - (A) Fix the wording only and state these as deliberate regressions.
    - (B) Restore coverage: add offset-scoped ranges for `descriptor.styles` and for the text outside every descriptor block, plus an overlap guard in `blankCommentSpans` (`:245-248`).
- [x] [Review][Decision] **Unrelated planning changes in the same working tree.** RESOLVED 2026-10-22 (owner): option A, commit separately. No code change; apply at commit time.
  - The brainstorming doc (+27, now headed "AUDIT 2026-10-22 — NOT FULLY IMPLEMENTED"), `skillars-epics.md` (+203, Epic 12 gap map, stories 12.6–12.10) and five new `skillars-12-6…12-10` backlog entries in `sprint-status.yaml` are not this story's work.
  - The brainstorming change also contradicts this story's Source section ("brainstorming doc is exhausted … do not re-open it").
  - Options:
    - (A) Commit them in a separate commit or PR.
    - (B) Commit them with this story.
- [x] [Review][Patch] Rewrite the "Known gaps" header so `<style>` `/* */`, top-level SFC `<!-- -->` and `// …` outside script blocks are stated as deliberate coverage losses vs the old regex design (only template-expression `/* */` was never covered). Mirror this in the M1 ledger marker. [`i18nTemplateKeyResolutionSpec.js:189-191`]
- [x] [Review][Patch] The M1 ledger marker says `</script>` in a string "can no longer blank live calls". In `.vue` such a file now fails loudly; in `.js` it never mattered. Reword. [`deferred-work.md:3437`]
- [x] [Review][Patch] `lang="tsx"`/`lang="jsx"` blocks get only the `typescript` plugin, so a block containing JSX throws. Map lang to plugins: ts → `['typescript']`, tsx → `['typescript','jsx']`, jsx → `['jsx']`. [`i18nTemplateKeyResolutionSpec.js:232`]
- [x] [Review][Patch] Nothing tests a `.vue` file whose SFC parses but whose script Babel rejects; `Broken.vue` stops at the SFC `errors` branch. Add `blankCommentSpans('<script setup>\nconst = ;\n</script>', 'BrokenScript.vue')` toThrow `/BrokenScript\.vue/`. [`i18nTemplateKeyResolutionSpec.js:~425-431`]
- [x] [Review][Patch] The deferred-157 fixture title "comment blanking is region-scoped…" describes a regex design that no longer exists. Retitle it. [`i18nTemplateKeyResolutionSpec.js:346`]
- [x] [Review][Patch] Leftover alias `const line = raw` from removing `stripLineComment`; collapse it. [`i18nTemplateKeyResolutionSpec.js:~291`]
- [x] [Review][Patch] Story bookkeeping:
  - Task 1.5 says "five fixtures"; AC1 lists four, and four were delivered.
  - The File List omits `story-review.md`.
  - The `sprint-status.yaml` entry text for this story still reads only "CREATED …".
  [this file:80, :131; `sprint-status.yaml:395`]
- [x] [Review][Patch] The module doc says the scan covers "every `.js` file that calls `i18n.global.t(`", but `listSourceFiles` takes every `.js` under `src`. Every one must now parse with Babel and no plugins, which is a new constraint, so the doc should say so. [`i18nTemplateKeyResolutionSpec.js:20`, `:252-259`]
- [x] [Review][Defer] Nothing tests the PARENT loop continuing to child B after child A's `CHILD_BOOKING_*` skip. All PARENT booking tests use one child. [`GdprErasureServiceTest.java:~802-850`] — deferred, pre-existing.
- [x] [Review][Defer] Only a manual check pins the scan's 1571/26/0 counts. `MIN_LITERAL_CALL_SITES`, the exact `DYNAMIC_KEY_CALL_SITES` list and the fixtures are the standing guard. [`i18nTemplateKeyResolutionSpec.js:167`] — deferred, pre-existing design.

## Dev Notes

- **Backend test scope is a single unit-test class.** `GdprErasureServiceTest` is Mockito-only; no IT is needed. Per project rule (`feedback_no_local_mvn_verify`), do **not** run `mvn verify` locally. Run `mvn -q -Dtest=GdprErasureServiceTest test` at most; GitHub CI is the full gate.
- **Frontend gate:** `cd src/frontend && npx vitest run src/i18n` plus `npx eslint .` and `npx prettier --check` (the CI Prettier gate lives in `.github/actions/`, not `workflows/`; a failure blocks build-and-push).
- **Do not change** `localeKeyParitySpec.js`, any locale file, or the `GdprErasureService` reason-code constants unless Option B is chosen.
- `BookingSchemaLockException` is deliberately not a `PessimisticLockingFailureException` (see its Javadoc, `GdprErasureService.java:1663-1675`); keep it that way so it passes through the method's outer catches and the `requiresNewTemplate` boundary.
- Trap to avoid: moving any comment blank by even one character shifts `file:line` keys and flips `DYNAMIC_KEY_CALL_SITES` assertions. Newlines must survive intact.
- Related memory: the IT autocommit and REQUIRES_NEW row-lock notes are not relevant here (no new IT, no new write).

### Project Structure Notes

- Frontend spec lives under `src/frontend/src/i18n/__tests__/`; shared test helper `collectKeyPaths.js` is under `src/frontend/test/vitest/` (moved there by deferred-157). Backend test: `src/test/java/com/softropic/skillars/platform/admin/service/GdprErasureServiceTest.java`.

### References

- [Source: `_bmad-output/implementation-artifacts/deferred-work.md`, "Deferred from: code review of skillars-deferred-157-…" (2026-10-21)]
- [Source: `_bmad-output/implementation-artifacts/skillars-deferred-157-booking-erasure-reason-codes-availability-block-scrub-and-test-tooling-hygiene.md` (AC1, AC2, AC11 and its Review Findings)]
- [Source: `src/frontend/src/i18n/__tests__/i18nTemplateKeyResolutionSpec.js`]
- [Source: `src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java`]

### Story Review Disposition (`story-review.md`, 2026-10-22)

Applied: F1 (AC5 pin shape: `assertThatThrownBy`, no `UNCLASSIFIED_FAILURE` from `erase()`), strict-stubs trap, F2 (`</script>` fixture restated as fail-loudly), F3 (fixtures need a later genuine delimiter), F4 (count match is necessary not sufficient; `comments: true`; temporary count log), F5 (per-child PARENT rollback, reason-blind dedup), F6 (stale-check came from deferred-156), F7/F9 (top-level HTML comments and known gaps stated), F8 (AC4 stub target), F10 (Option B cost), F11 (marker count, line ranges, `typescript` plugin). Not actioned: none rejected as a false positive; L7a/L7c wording nits were left as is (no implementation impact).

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5.5 (claude-sonnet-5-5)

### Debug Log References

- Baseline scan counts (temporary `console.log`, removed): before 1571 / 26 / 0; after 1571 / 26 / 0.
- Red phase: the four new spec fixtures failed on the old regex code (7 passed | 4 failed), then passed after the swap (11/11).

### Completion Notes List

- **AC1/AC2:** `blankCommentSpans` is now parser-backed: `babelParse` for `.js` and `<script>`/`<script setup>` (block content offset added; `typescript` plugin only for `lang="ts"`), SFC template comment nodes via `parse(..., { templateParseOptions: { comments: true } })`. Parse errors throw naming the file. `stripLineComment` and the three comment regexes were removed (the parser covers `//`). Count equivalence held (1571/26/0) but is not discriminating on its own, so four fixtures guard it, each with a later genuine delimiter where relevant. Header comment updated, including known gaps (template-expression comments, `<style>`, top-level SFC HTML comments). `vue/compiler-sfc` is a public export of `vue`; no dependency added.
- **AC3:** the stale-entry check already existed; it landed with skillars-deferred-156 (`56803337`), not deferred-157. Ledger marker only, no duplicate test.
- **AC4:** added the PARENT bookings x contended test, which also asserts the child's tombstone is not saved.
- **AC5:** Option A (document, do not widen). Added a comment at the booking inner try (including per-child PARENT rollback and reason-blind dedup caveats) and a pin test using `assertThatThrownBy(QueryTimeoutException)` with no alert and no tombstone save; stubs inlined to avoid the strict-stubs trap. `UNCLASSIFIED_FAILURE` itself remains pinned by the existing `markFailed` test.
- **AC6:** four `[CLOSED by skillars-deferred-158 ...]` markers in `deferred-work.md`.
- **Validation:** `GdprErasureServiceTest` passes with 0 failures (`mvn -o -Dtest=GdprErasureServiceTest test`; no `mvn verify`). Frontend: full vitest 52 files / 414 tests pass, `eslint .` clean, Prettier clean on the spec. The full backend suite was not run locally (CI is the gate).

### File List

- src/frontend/src/i18n/__tests__/i18nTemplateKeyResolutionSpec.js (modified)
- src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java (modified, comment only)
- src/test/java/com/softropic/skillars/platform/admin/service/GdprErasureServiceTest.java (modified)
- _bmad-output/implementation-artifacts/deferred-work.md (modified)
- _bmad-output/implementation-artifacts/sprint-status.yaml (modified)
- _bmad-output/implementation-artifacts/skillars-deferred-158-gdpr-booking-erasure-label-completeness-and-i18n-spec-guard-hardening.md (this file)
- _bmad-output/implementation-artifacts/story-review.md (pre-implementation review, overwritten)

### Change Log

- 2026-10-22: Implemented deferred-158 (parser-backed i18n comment blanking, PARENT contended test, booking-catch decision documented and pinned, ledger markers). Status in-progress -> review -> done.
- 2026-10-22: Code review (/bmad-code-review: Blind Hunter, Edge Case Hunter, Acceptance Auditor).
  - Two owner decisions:
    - Accept the lost `<style>`/top-level/`//` comment coverage and document it.
    - Commit the unrelated Epic 12 planning changes separately.
  - All 8 patches applied: an accurate "not blanked" header, the M1 marker reworded, the `.js` scan-scope doc, lang→Babel plugin map (`tsx`/`jsx`), a BrokenScript.vue fail-loudly assertion, the deferred-157 fixture retitled, the `raw`/`line` alias collapsed, and story bookkeeping.
  - 2 items deferred to `deferred-work.md`.
  - `src/i18n` vitest 15/15; ESLint and Prettier clean.
  - Status review -> done.
