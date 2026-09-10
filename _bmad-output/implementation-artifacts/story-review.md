# skillars-deferred-104 Story Audit

**Reviewed:** 2026-09-09 | **Status:** Ready for dev (minor clarifications noted)

---

## Summary

This is a well-structured story. The hard constraint (keep Vitest out of `mvn verify`) is clearly stated and correctly enforced throughout. Decision documentation (D1–D4) is thorough. Most ACs are precise and defensive. No blockers found. Two minor clarifications and one potential edge case noted below.

---

## Confirmed Strengths

- **Hard constraint enforcement:** The mechanism is load-bearing and unambiguous—Maven's `npm test` calls the stub script, Vitest is reachable only via `test:unit` (AC3, AC4). No path for accidental wiring into `mvn verify`.
- **Decision documentation:** D1–D4 capture rationale and trade-offs (manual + PR label for CI, Quasar AE for config stability, reference specs chosen carefully, happy-dom for performance).
- **Defensive checks:** AC1 verifies no transitive bumps of production deps; AC2 diff-validates `quasar.config.js` safety; AC5 label-gates the workflow; AC7 forbids weakening assertions; AC10 has a targeted sibling grep sweep.
- **Isolation:** AC8 acknowledges module-level state isolation via `cleanup()` and `vi.resetModules()`.
- **Ledger hygiene:** AC10 specifics (line numbers, deletion vs. annotation, dated deferred section template) are clear.

---

## Issues Found

### 1. **AC8 — `readSessionExpiryFromCookie()` export status unclear** (Minor)

**The issue:**  
AC8's first test case requires calling `readSessionExpiryFromCookie()` to test the stale-`rint` parse behavior. The AC lists it in the References as an internal function alongside `computeTimeUntilExpiry` and `tick`, and says "extend the export list **only if** a needed function is private". 

If `readSessionExpiryFromCookie()` is **not** currently exported from `sessionManager.js`, the test cannot call it without:
- Modifying `sessionManager.js` to add it to the `export { … }` block (explicitly forbidden by "Do NOT change `sessionManager.js` itself"), or  
- Using `vi.importActual()` + private access (non-standard pattern, fragile)

**Why it matters:**  
The stale-`rint` parse test case is one of the three required scenarios (AC8). If the function isn't accessible, the dev either skips the test (with a filed deferred item per the fallback guidance) or hits a runtime error during AC8 implementation.

**Recommendation:**  
Verify in advance that `readSessionExpiryFromCookie()` is in the `export { … }` block at `src/frontend/src/plugins/sessionManager.js:290-295`. If it's private (only used internally), note in the Dev Agent Record and file a follow-up deferred item for a sessionManager export when needed.

---

### 2. **AC7 — SkillsRadarChartSpec.js might not run on `happy-dom`** (Medium risk, not a blocker)

**The issue:**  
AC7 assumes `SkillsRadarChartSpec.js` will run and pass under `happy-dom` (D4). The specification is chosen for performance over full DOM fidelity. If `SkillsRadarChart.vue` uses DOM APIs that `happy-dom` doesn't support (e.g., `getBoundingClientRect()`, specific CSS layout queries, or obscure DOM methods), the test will fail or hang.

**Why it matters:**  
The AC gives an escape hatch (AC2: "override `// @vitest-environment jsdom` per-file"), but it requires adding `jsdom` to `devDependencies` "only if/when that happens — not in this story". If the radar spec fails under `happy-dom`, the dev has three options:
1. Modify the spec to work around `happy-dom` limits (acceptable per AC7, "fix the spec to match component if drifted")
2. Add `jsdom` and override environment for that file (out of scope for AC1, but feasible)
3. File a deferred item if the component itself has DOM assumptions that don't belong in a unit test

**Recommendation:**  
When running AC7, if the test fails with a `happy-dom` error (check the stack trace for "happy-dom" or "not implemented"), note the specific API in the Dev Agent Record. This informs whether D4's choice holds for the codebase's components.

**Expected outcome:** AC7 likely passes as-is (radar charts typically don't need advanced DOM APIs), but this is a discovered risk, not a false assumption.

---

### 3. **AC5 — Workflow runs `test:unit` but artifact upload expects `coverage/`** (Not an issue, clarify if needed)

**The detail:**  
AC5 step 4 runs `npm run test:unit` (which is `vitest run` per AC3, with no coverage flag). AC5 step 5 uploads `src/frontend/coverage/` with `if-no-files-found: warn`.

Since `test:unit` doesn't generate coverage (only `test:unit:ci` does), the artifact upload will warn about missing `coverage/` on every run.

**Why it's OK:**  
The AC5 comment explicitly says "report-to-console is acceptable for this story" — console output is the deliverable for this iteration. Coverage artifacts are future-proofed (the script can later run `test:unit:ci` if coverage reporting is needed). The `if-no-files-found: warn` is appropriate for an optional artifact.

**No action needed** — this is a deliberate design choice, not a bug. The wording could be slightly clearer for future devs, but it's not a blocker.

---

## Minor Clarifications

### AC9 — HELP.md update is vague

**The line:** "add the `test:unit` line to the frontend command list."

**Issue:** HELP.md might not have an obvious "frontend command list" section, or the section might be formatted unexpectedly.

**Fix:** During AC9, search HELP.md for existing npm/yarn command examples (e.g., `npm run build`, `npm test`, etc.), then add `npm run test:unit` alongside them in a consistent format. If HELP.md doesn't document npm scripts, add a "Frontend Development" subsection.

---

### AC10 — Ledger line numbers might drift

**The line:** "For each dependent coverage-gap line it enumerated, append a short …annotation" with specific line ~numbers (~706, ~918, ~934, etc.).

**Issue:** `_bmad-output/implementation-artifacts/deferred-work.md` is a living document. Line numbers may have shifted since this story was authored (2026-09-09).

**Fix:** During AC10, open `deferred-work.md` and search for the referenced story IDs and keywords (e.g., `skillars-5-4`, `deferred-17`, `sessionManager`, `booking.store.js`) rather than trusting line numbers. Verify each line still references a coverage gap (not already closed), then append the annotation. If a referenced item has already been closed or moved, note it in the Dev Agent Record.

---

## Not Issues (Pre-empted by the AC)

- **Module state isolation (AC8):** AC8 explicitly addresses via `cleanup()`, `vi.restoreAllMocks()`, and `vi.resetModules()`. Guidance is slightly terse but acknowledged.
- **Test globals in ESLint (AC6):** AC6 confirms no globals are enabled and specs import `describe/it/expect/vi` explicitly. Pre-empted.
- **Quasar AE config integrity (AC2):** AC2 has explicit diff verification for `quasar.config.js` safety (no changes to `build`, `framework`, `i18n` blocks). Safe.
- **Lock file transitive updates (AC1):** AC1 explicitly checks `git diff` shows only dev deps and transitive closure, no production bumps. Covered.
- **Node version (AC5):** Version `22.16.0` is pinned to `pom.xml` — will be verified during PR build; no mismatch risk.

---

## Test Coverage Assumptions (Verified)

The test.include pattern `['src/**/*.{spec,test}.{js,mjs}', 'src/**/__tests__/**/*.{js,mjs}']` correctly discovers:
- ✓ `src/components/development/__tests__/SkillsRadarChartSpec.js` (via second pattern)
- ✓ `src/plugins/__tests__/sessionManagerSpec.js` (via second pattern)
- ✓ Future `.spec.js` or `.test.js` files (via first pattern)

---

## Recommendation: Proceed

No blockers. The two issues noted are discoverable during implementation (AC7 test runs immediately; AC8's function export is checkable upfront) and have fallback guidance in the AC itself. The story is ready for dev.

**Dev checklist priorities:**
1. Before AC8: verify `readSessionExpiryFromCookie()` is exported from `sessionManager.js`, or plan the fallback (file deferred item + `.skip()` the test case)
2. During AC7: note any `happy-dom` compatibility issues in the Dev Agent Record
3. During AC10: verify ledger line numbers haven't drifted; search by ID instead of trusting line ~numbers

---

**Review completed:** This audit found no false positives and no fatal gaps. Story is specification-ready.
