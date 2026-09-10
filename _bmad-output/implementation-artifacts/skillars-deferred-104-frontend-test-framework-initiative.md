# skillars-deferred-104: Frontend Test Framework Initiative (Vitest + Vue Test Utils stand-up, opt-in CI)

**Status:** done | **Epic:** deferred | **Priority:** medium
**Story ID:** deferred-104
**Branch:** `story/deferred-104-frontend-test-framework-initiative`
**Created:** 2026-09-09
**Base:** master @ `b16ce4f3` (immediately after `skillars-deferred-106` merge, PR #163)

> **Numbering note:** slot `skillars-deferred-104` was reserved for this initiative by
> `skillars-deferred-103` decision D3 and re-affirmed by `skillars-deferred-105`'s header note.
> `skillars-deferred-98` AC3 formally deferred the Vitest stand-up to this dedicated story and
> recorded the consolidated backlog entry (`## Backlog: frontend-test-framework-initiative` in
> `deferred-work.md`, `frontend-test-framework-initiative: backlog` in `sprint-status.yaml`).

---

## Story Overview

As the Skillars platform team,
I want a real frontend unit-test runner (Vitest + `@vue/test-utils`) stood up for `src/frontend`,
wired to Quasar's Vite resolve aliases, with a `test:unit` script and a **dedicated, opt-in**
GitHub Actions job,
so that the ~8 recorded frontend coverage gaps can each be closed in their own follow-up story —
**without adding a single second to the `mvn verify` build that every push and every PR already
runs.**

### The hard constraint that shapes every AC

Frontend unit tests **must not** run as part of `mvn verify`, `ci.yml`'s `test` job, or
`pr-build.yml`'s `build`/`frontend-quality` jobs. They run **only**:

1. locally on demand — `cd src/frontend && npm run test:unit`; and
2. in CI on demand — a new standalone workflow triggered by **manual dispatch** or by adding a
   **`frontend-tests` label** to a PR. It is **never** a required status check and is **never**
   referenced by `ci.yml` or `pr-build.yml`.

The mechanism that keeps this true is simple and load-bearing: the Maven `frontend-maven-plugin`
`npm test` execution calls the npm script named `test`, and this story leaves that script as its
current no-op stub (`echo "No test specified" && exit 0`). Vitest is reachable only through the
**new** `test:unit` script, which nothing in `pom.xml` calls. **Do not rename `test` or point it at
Vitest.**

---

## Project-owner decisions (captured + signed off 2026-09-09 during story creation)

| # | Question | Decision (SIGNED OFF) |
|---|----------|----------------------|
| D1 | CI trigger model for the dedicated job — manual only, manual + PR-label, or manual + nightly? | **Manual + PR label.** `workflow_dispatch` (run any time from the Actions tab) **plus** `pull_request` restricted to `types: [labeled, synchronize, reopened]` and guarded so the job body only runs when the PR carries the label `frontend-tests`. Not added to branch protection; not a required check; not referenced by `ci.yml` / `pr-build.yml`. A contributor opts in per-PR by applying the label when a change actually touches frontend logic. |
| D2 | Vitest ↔ Quasar alias wiring — official Quasar App Extension, or a hand-rolled `vitest.config.mjs`? | **Official Quasar AE:** `@quasar/quasar-app-extension-testing-unit-vitest`. It derives the Vitest config from `quasar.config.js`, so the `src/`, `components/`, `stores/`, `layouts/`, `pages/`, `boot/`, `assets/` aliases, the Quasar Vite plugin, and SCSS-variable injection stay in sync with the app build automatically. Larger dep tree and a peer range that may lag Quasar 2.16 — acceptable: the repo's Maven `npm install` already runs with `--legacy-peer-deps` and `npm ci` in the new workflow tolerates the resolved lock. |
| D3 | Reference specs to ship in THIS story | **Radar + sessionManager.** (a) Make the pre-written `SkillsRadarChartSpec.js` run and pass — a real `@vue/test-utils` `mount()` (closes `skillars-5-4` W9). (b) Add a fresh **pure-logic** spec for `plugins/sessionManager.js` — faked `document.cookie` + `Date.now()`, no mount — covering the stale-`rint` parse, a warning-edge transition, and the unevaluated-state window that `deferred-90`/`-98` called out. Exercises both test styles so follow-up stories have a template for each. |
| D4 | DOM environment for mount-based specs | **`happy-dom`.** Faster startup and lower memory than `jsdom`; sufficient for Vue Test Utils component mounts in this codebase. A later spec that needs an API `happy-dom` stubs incompletely can override `// @vitest-environment jsdom` per-file (add `jsdom` as a dev dep only if/when that happens — not in this story). |

---

## Acceptance Criteria

### AC1: Add the test-runner dev dependencies and regenerate the lockfile

- **File:** `src/frontend/package.json` (`devDependencies`), `src/frontend/package-lock.json`
- **Add to `devDependencies`** (resolve each to its current stable at implementation time; pin with
  `^` to match the file's existing style):
  - `vitest` (v3.x)
  - `@vue/test-utils` (v2.4.x — Vue 3)
  - `@quasar/quasar-app-extension-testing-unit-vitest` (the AE from D2)
  - `happy-dom` (D3/D4)
  - `@pinia/testing` (store specs — `createTestingPinia`)
  - `@vitest/coverage-v8` (matched to the `vitest` major — powers `test:unit:ci`, non-gating)
- **Lockfile:** regenerate with the **same** invocation the Maven build uses so the two never
  diverge — `cd src/frontend && npm install --legacy-peer-deps` — then commit the updated
  `package-lock.json`. Do **not** run a bare `npm install` (peer-dep resolution differs from the
  build's).
- **Do NOT** add these to the root `pom.xml`, to `dependencies` (they are dev-only), or to any
  existing GitHub Actions job.
- **Verify:**
  - `git diff --stat src/frontend/package.json src/frontend/package-lock.json` shows only the new
    dev deps and their transitive closure — no change to `dependencies`, no bump of an unrelated
    existing dep.
  - `cd src/frontend && npx vitest --version` prints the installed version.
  - `mvn -DskipTests -DskipFrontend=false generate-resources` still succeeds locally is **not**
    required (project policy: CI is the gate); the PR CI `build` job green is the check.

### AC2: Generate the Vitest config via the Quasar AE + add a shared test setup

- **Invoke the AE** from `src/frontend`: `quasar ext add @quasar/testing-unit-vitest` (or
  `npx quasar ext add ...`). Accept its scaffold, then trim to exactly what this story needs:
  - **`src/frontend/vitest.config.mjs`** (AE-generated). Confirm it:
    - pulls Quasar's Vite config (aliases + `@quasar/vite-plugin` + SCSS variables) — the AE does
      this through its `quasar.config` bridge; do not hand-edit the alias list.
    - sets `test.environment` to `happy-dom`.
    - sets `test.include` to `['src/**/*.{spec,test}.{js,mjs}', 'src/**/__tests__/**/*.{js,mjs}']`
      so both the existing `__tests__/…Spec.js` layout and a future `*.spec.js` sibling layout are
      picked up.
    - sets `test.setupFiles` to `['./test/vitest/setup-file.js']` (next bullet).
    - sets `test.globals: false` — this project imports `describe/it/expect` explicitly (see the
      existing `SkillsRadarChartSpec.js`); keep that convention, do not enable globals.
  - **`src/frontend/test/vitest/setup-file.js`** (AE-generated stub — populate it):
    - installs the Quasar Vue plugin for mounted components (`installQuasarPlugin()` from
      `@quasar/quasar-app-extension-testing-unit-vitest`).
    - registers a global `$t` / `$tc` stub for `vue-i18n` (mirrors the inline mock the existing
      `SkillsRadarChartSpec.js` already carries in its `globalConfig.global.mocks`, so specs don't
      each re-declare it): `config.global.mocks.$t = (key, params) => params?.date ? \`...\` : key`.
      Keep it minimal — a spec that needs real translations can build its own i18n instance.
    - does **not** start Pinia globally — specs opt in with `createTestingPinia()` where needed.
  - **`src/frontend/quasar.config.js`** — the AE appends a `testing` boot/config hook. Keep only
    what the AE needs; if it adds a `boot` entry it must be dev-only. Re-run
    `git diff src/frontend/quasar.config.js` and confirm no change to `build`, `devServer`,
    `framework`, `pwa`, or the `i18n` vite-plugin `include` block.
- **`.quasar/`** is git-ignored generated output — confirm nothing under it is staged.
- **Verify:** `cd src/frontend && npm run test:unit` (defined in AC3) discovers and runs the two
  reference specs (AC7, AC8) and reports them green.

### AC3: Add `test:unit` npm scripts — leave the Maven-invoked `test` script untouched

- **File:** `src/frontend/package.json` (`scripts`)
- **Add:**
  ```jsonc
  "test:unit": "vitest run",
  "test:unit:watch": "vitest",
  "test:unit:ci": "vitest run --coverage"
  ```
- **Leave EXACTLY as-is:** `"test": "echo \"No test specified\" && exit 0"`. This is the script the
  `frontend-maven-plugin` `<execution><id>npm test</id></execution>` calls in the Maven `test`
  phase (`pom.xml:569-578`). Keeping it a no-op is the entire mechanism that keeps Vitest out of
  `mvn verify`. A one-line comment is not possible in `package.json`; the rationale lives in
  `pom.xml` (AC4) and `docs/frontend-testing.md` (AC9) instead.
- **Do NOT** add a `pretest` / `posttest` hook (npm would run it around the stub).
- **Coverage:** `test:unit:ci --coverage` uses `@vitest/coverage-v8` with **no thresholds** this
  story (report-only). Add `coverage/` to `src/frontend/.gitignore` if the AE didn't
  (the repo root `.gitignore` already covers `src/frontend/dist` / `.quasar` — check whether
  `coverage/` needs adding there or in a `src/frontend/.gitignore`; `src/frontend` has no
  `.gitignore` of its own today — prefer adding `src/frontend/coverage/` to the **root**
  `.gitignore` next to the other `src/frontend/*` build-output ignores).
- **Verify:** `cd src/frontend && npm test` still prints `No test specified` and exits 0;
  `npm run test:unit` runs Vitest.

### AC4: `pom.xml` — no wiring change; add a clarifying comment only

- **File:** `pom.xml` — the `frontend-maven-plugin` block (`pom.xml:533-597`), specifically the
  `<execution><id>npm test</id></execution>` at `:569-578`.
- **Change:** add an XML comment immediately above that execution (and/or update the existing
  summary comment at `pom.xml:35-38`) recording that the frontend **unit** suite is deliberately
  **not** run here — it runs via `npm run test:unit` locally and via the
  `.github/workflows/frontend-unit-tests.yml` on-demand job (AC5); wiring it into this `test`-phase
  execution would put it on every `mvn verify` (CI push + every PR), which
  `skillars-deferred-104` explicitly rejects. Reference this story id.
- **Do NOT** change the `<arguments>test</arguments>`, the phase, the `<skip>` wiring, or add a new
  execution.
- **Verify:** `git diff pom.xml` is comment-only; `mvn -B verify` (CI) behaves identically to base.

### AC5: New standalone workflow — `.github/workflows/frontend-unit-tests.yml`

- **New file.** A single job, `frontend-unit`, `runs-on: ubuntu-latest`,
  `permissions: { contents: read }`, `timeout-minutes: 10`.
- **Triggers (per D1):**
  ```yaml
  on:
    workflow_dispatch: {}
    pull_request:
      types: [labeled, synchronize, reopened]
  ```
- **Label gate:** the job runs its body only for a manual dispatch or a PR that carries the
  `frontend-tests` label:
  ```yaml
  jobs:
    frontend-unit:
      if: >-
        github.event_name == 'workflow_dispatch' ||
        contains(github.event.pull_request.labels.*.name, 'frontend-tests')
  ```
  (`types: [labeled, …]` still fires the workflow on every label add; the `if` is what keeps it a
  no-op unless the label is `frontend-tests`. `synchronize`/`reopened` keep an already-labelled PR's
  result fresh on new pushes.)
- **Steps:**
  1. `actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1  # v7.0.1`
  2. `actions/setup-node@249970729cb0ef3589644e2896645e5dc5ba9c38  # v6.5.0` with
     `node-version: '22.16.0'` (pinned to `<node.version>` in `pom.xml` — same value the
     `frontend-quality` composite uses), `cache: npm`,
     `cache-dependency-path: src/frontend/package-lock.json`.
  3. `npm ci` in `src/frontend` (`working-directory: src/frontend`).
  4. `npm run test:unit:ci` in `src/frontend` — the `:ci` variant adds `--coverage` (v8) so the
     uploaded artifact is populated. Local devs run the lighter `npm run test:unit`; CI runs
     `:ci`. Both are `vitest run` — identical specs and pass/fail semantics, coverage
     instrumentation is the only difference.
  5. `actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a  # v7.0.1`,
     `if: always()`, uploading `src/frontend/coverage/` (and reporter output if a
     `--reporter=junit --outputFile=…` is later added to `test:unit:ci`), name
     `frontend-unit-reports-${{ github.run_id }}`, `retention-days: 14`, `if-no-files-found: warn`.
     Coverage has **no thresholds** this story — report-only.
- **SHA-pin convention:** every `uses:` is a full commit SHA + trailing `# vX.Y.Z` comment
  (mirror `ci.yml` / `pr-build.yml`). Re-resolve each SHA against its tag before committing.
- **Do NOT:** add this workflow to branch-protection required checks; reference it from `ci.yml`
  or `pr-build.yml`; give it `packages: write` or any write permission; trigger it on `push`.
- **Verify:**
  - On this story's PR **without** the label: the workflow either does not appear or shows the
    `frontend-unit` job skipped.
  - After adding the `frontend-tests` label to the PR: the job runs `npm ci` + `npm run test:unit:ci`
    and passes (both reference specs green).
  - `grep -rn "frontend-unit-tests" .github/workflows/ci.yml .github/workflows/pr-build.yml` →
    no hits.

### AC6: ESLint + Prettier conformance for all new files

- **Prettier:** every new/edited file (`vitest.config.mjs`, `test/vitest/setup-file.js`, the two
  reference specs, `package.json`, `quasar.config.js`) must pass
  `cd src/frontend && npx prettier --check "**/*.{js,vue,scss,json}" --ignore-path ../../.gitignore`
  — this is exactly the check `.github/actions/frontend-quality` runs on every PR, and that job
  **does** still run on this PR. Run `npx prettier --write` on the new files before committing.
- **ESLint:** `cd src/frontend && NODE_ENV=production npx eslint .` must stay green (also enforced
  by `frontend-quality` on this PR). The existing `eslint.config.js` has **no** test-runner globals
  and this project imports `describe/it/expect/vi` explicitly — keep doing that in the new specs so
  no `no-undef` fires. If the AE's generated config or a spec trips a rule
  (e.g. `vue/one-component-per-file`, `no-unused-vars` on a helper), add a **narrowly-scoped**
  override block to `eslint.config.js` keyed to `files: ['**/*.spec.{js,mjs}', '**/__tests__/**']`
  rather than disabling a rule globally or sprinkling `eslint-disable` comments. Document any such
  block with a one-line comment referencing this story.
- **`vue/no-bare-strings-in-template`** is an **error** in this repo — the radar spec mounts a real
  component, not a template, so it is unaffected; just be aware if a future spec inlines a
  `<template>`.
- **Verify:** both commands above exit 0 locally; the PR's `frontend-quality` job is green.

### AC7: Reference spec #1 — make `SkillsRadarChartSpec.js` run and pass (closes `skillars-5-4` W9)

- **File:** `src/frontend/src/components/development/__tests__/SkillsRadarChartSpec.js` — **already
  exists**, already written against `vitest` + `@vue/test-utils`, and has never been runnable
  because neither dep was installed (`skillars-5-4` W9, ledger ~line 706).
- **Task:** with AC1/AC2 in place, run it and make it green. Expected: it already passes as written.
  If a test fails because of a real assertion drift in `SkillsRadarChart.vue` since the spec was
  authored, **fix the spec to match current component behaviour** (this story does not change
  component code) and note the drift in the Dev Agent Record. Do **not** weaken an assertion to
  force a pass — if the component looks genuinely wrong, record it as a new deferred item instead.
- **Keep the file where it is** (`__tests__/…Spec.js`) — `test.include` in AC2 covers that path.
  Renaming to `.spec.js` is optional and out of scope; if done, it is the only change and must be a
  pure `git mv`.
- **`happy-dom` fitness (checked at story creation):** `SkillsRadarChart.vue` is pure
  computed-SVG geometry — every coordinate is derived in JS from props (`CENTER_X`, `RADIUS`,
  `toPoint()`), and the spec asserts only on element presence and string attributes
  (`polygon.attributes('points')`, `table tbody tr` counts). No `getBoundingClientRect`, `getBBox`,
  `getContext`, `ResizeObserver`, `matchMedia`, or layout/measurement reads anywhere in the
  component. `happy-dom` covers this with room to spare. If a **future** spec mounts a component
  that does hit an unsupported API, that spec adds `// @vitest-environment jsdom` at its top and
  `jsdom` is added to `devDependencies` **then** (out of scope here).
- **Verify:** `npx vitest run src/components/development/__tests__/SkillsRadarChartSpec.js` → all
  cases pass; the run mounts the real component through `happy-dom` (proves the Quasar/i18n/alias
  wiring from AC2). If anything fails with a `happy-dom` "not implemented" error, record the exact
  API in the Dev Agent Record before working around it.

### AC8: Reference spec #2 — pure-logic spec for `plugins/sessionManager.js`

- **New file:** `src/frontend/src/plugins/__tests__/sessionManagerSpec.js` (mirror the existing
  `__tests__/…Spec.js` naming).
- **Test through the exported surface only — `sessionManager.js` is NOT modified.** The three
  functions that hold the logic under test — `readSessionExpiryFromCookie`, `computeTimeUntilExpiry`,
  `tick` — are **private** (plain `function`, not in the `export { … }` block). Do **not** add them
  to the exports. Drive them indirectly:
  - **`refreshExpiryState()`** (exported) calls `tick()` → `computeTimeUntilExpiry()` →
    `readSessionExpiryFromCookie()`. This is the entry point for cases 1 and 2.
  - **`startSessionMonitoring()`** (exported) calls `tick()` **synchronously** before arming its
    30 s interval — so there is no "before the first tick" window after it; the first evaluation
    happens inside the call. Case 3 targets the pre-any-call module default instead.
  - Observable state = the exported reactive refs: `showWarning`, `timeUntilExpiry`,
    `secondsRemaining`, `minutesRemaining`, `refreshFailed`. Other exports: `recordActivity`,
    `stopSessionMonitoring`, `refreshSession`, `cleanup`.
- **Style:** **no `mount`**. Uses the default `happy-dom` env only for a writable `document.cookie`;
  clock via `vi.useFakeTimers()` + `vi.setSystemTime(...)`. This is the template the ledger says
  every `sessionManager` finding reduces to ("a one-line unit test with a faked `document.cookie` +
  `Date.now()`", `deferred-90`/`-98`, ledger ~line 1126). Reproduce the module's constants as local
  literals in the spec (they are not exported): `WARNING_THRESHOLD = 5*60*1000`,
  `LEGACY_SESSION_TTL = 15*60*1000`, `MIN_PLAUSIBLE_EPOCH_MS = 1e12`, cookie name `rint`.
- **Cover these three (the three findings named in ledger ~line 1126), each a distinct `it()`:**
  1. **Stale-`rint` parse.** Set `document.cookie = 'rint=12345'` (a pre-1.7b fixed-delta / garbage
     value, `< MIN_PLAUSIBLE_EPOCH_MS`). Call `refreshExpiryState()`. Assert `timeUntilExpiry.value`
     is the **legacy fallback estimate** (≈ `LEGACY_SESSION_TTL`, since `lastActivityTime` was just
     set) — i.e. **not** a large negative and **not** `0`; the implausible value was rejected, not
     treated as an absolute epoch. Contrast case in the same `it()`: `document.cookie =
     'rint=' + (Date.now() + 10*60*1000)` → `refreshExpiryState()` → `timeUntilExpiry.value` ≈
     10 min (a plausible absolute `rint` **is** honoured).
  2. **Countdown-timer leak on leaving the warning band.** With fake timers: set `rint` to
     `Date.now() + 4*60*1000` (inside the 5-min `WARNING_THRESHOLD`), `refreshExpiryState()` →
     `showWarning.value === true` and the 1 s countdown interval is now armed (assert via
     `vi.getTimerCount()` rising, or advance 1 000 ms and see `timeUntilExpiry` tick down). Then set
     `rint` to `Date.now() + 10*60*1000`, `refreshExpiryState()` again → `showWarning.value === false`
     **and the countdown interval was cleared** (`vi.getTimerCount()` drops back; advancing time no
     longer re-runs `tick`). Pre-fix this interval was only ever cleared by `cleanup()` /
     `refreshSession()`, so it leaked for the rest of the session — that is the regression this
     asserts.
  3. **Unevaluated-state safe default.** On a freshly loaded module (`vi.resetModules()` +
     `await import('src/plugins/sessionManager')`), **before any function is called**:
     `showWarning.value === false`, `timeUntilExpiry.value === LEGACY_SESSION_TTL`, and
     `secondsRemaining` / `minutesRemaining` are sane (not `0`, not `NaN`). Plus: with no `rint`
     cookie and a fresh `lastActivityTime`, `startSessionMonitoring()` does **not** synchronously
     dispatch `session:expired` (spy `window.dispatchEvent`) — its first synchronous `tick()`
     returns `false` and the 30 s interval is armed. Then `stopSessionMonitoring()`.
- **Isolation — `afterEach`:** `cleanup()` (stops every timer, resets refs) → `vi.useRealTimers()`
  → `vi.restoreAllMocks()` → clear the cookie (`document.cookie = 'rint=; expires=Thu, 01 Jan 1970
  00:00:00 GMT'`) → clear `sessionStorage` key `skillars.session.rintSeen` (a valid `rint` sets it
  via `markRintSeen`, and it gates the `deferred-90` "torn down" branch). Case 3 additionally does
  `vi.resetModules()`; the other two can share one module import.
- **Do NOT** change `sessionManager.js`. If a test proves one of the three behaviours is actually
  broken at HEAD, ship that `it()` `.skip`-ped with a `// TODO(skillars-deferred-<n>)` comment and
  file a new `## Deferred from: skillars-deferred-104 implementation` item — the runner stand-up is
  the deliverable here, not a `sessionManager` fix.
- **Verify:** `npx vitest run src/plugins/__tests__/sessionManagerSpec.js` → green (or green with a
  documented, filed `.skip`).

### AC9: Documentation

- **New file `docs/testing/frontend-unit-tests.md`** (co-located with the existing backend testing
  docs — `docs/testing/readme.md`, `test-data-isolation.md`, `container-architecture.md`): how to
  run the suite (`npm run test:unit`, `:watch`, `:ci`), where specs live and how they're
  discovered, the Quasar-AE config model, the `$t` setup stub, `createTestingPinia` usage, and —
  prominently — **why the suite is decoupled from `mvn verify`** (the Maven-invoked `test` npm
  script stays a no-op) and how to run it in CI (manual dispatch or the `frontend-tests` PR label).
- **`docs/testing/readme.md`:** add a one-line pointer to the new doc in whatever
  index / list of testing topics it carries.
- **`docs/validation-strategy.md`:** add a short "Frontend unit tests" subsection stating they are
  opt-in, not part of the default `/bmad-dev-story` or CI gate, and pointing at
  `docs/testing/frontend-unit-tests.md`. Keep the existing backend guidance intact.
- **`_bmad-output/project-context.md`:** under "Testing Rules", add one bullet — frontend unit
  tests use Vitest + `@vue/test-utils`, live beside the code in `__tests__/`, run via
  `npm run test:unit`, and are **not** part of `mvn verify`. Bump `_Last Updated_`.
- **`src/frontend/README.md`:** this file already lists the frontend commands ("Lint the files",
  "Format the files", "Build the app for production"). Add a **"Run the unit tests"** section in the
  same style with `npm run test:unit` / `npm run test:unit:watch`. Do **not** touch `HELP.md` —
  it is untouched Spring-Initializr boilerplate with no command list and no frontend content.
- **Verify:** links resolve; `docs/testing/frontend-unit-tests.md` is referenced from
  `docs/testing/readme.md`, `validation-strategy.md`, and `project-context.md`; `src/frontend/README.md`
  shows the new section.

### AC10: Ledger housekeeping + sibling sweep

- **`_bmad-output/implementation-artifacts/deferred-work.md`:**
  > **Line numbers below are `~approximate` and age fast** (this file's own header says so). Locate
  > each target by `grep`-ing its story id + keyword (`skillars-5-4`, `deferred-17`,
  > `sessionManager`, `booking.store.js`, `PaymentMethodCard`, `frontend-test-framework-initiative`),
  > not by trusting the `~line`. If a target is already closed / moved / reworded, say so in the Dev
  > Agent Record and adjust — do not force an annotation onto a line that no longer matches.
  - **Delete** the `## Backlog: frontend-test-framework-initiative` section (lines ~1263–1282 at
    base) outright, per this file's stated delete-when-closed convention.
  - For each dependent coverage-gap line it enumerated, append a short
    `[FRAMEWORK AVAILABLE 2026-09-09 (skillars-deferred-104): Vitest runner now stood up — this
    line's coverage is now in-scope for its own follow-up story]` annotation (do **not** delete
    these — the coverage is still missing until each story writes it):
    - `skillars-5-4` W9 (~line 706) — **exception: this one IS closed** by AC7. Delete it and, if
      its section empties, remove the header.
    - `deferred-17` D6 (~918), `deferred-18` D6 (~934), `deferred-30`/`-37`/`-38`/`-43` D6,
      `deferred-91` note (~1157), `deferred-90`/`-98` `sessionManager` note (~1126 —
      **exception: partially addressed** by AC8; annotate that the three called-out findings now
      have a reference spec, remaining `sessionManager` coverage still open), `deferred-35`/`-36`/
      `-37`/`-38` `booking.store.js` notes (~1002, ~1006), `deferred-12` payment
      `PaymentMethodCard.vue` / `payment.store.js` (~657).
  - Add a new dated `## Deferred from: skillars-deferred-104 implementation (2026-09-09)` section
    only if the sweep or the two reference specs surface a genuinely new item (e.g. an AC7/AC8
    `.skip` with a filed follow-up).
- **`sprint-status.yaml`:** change `frontend-test-framework-initiative: backlog` →
  `frontend-test-framework-initiative: done` (keep the line as the historical record, matching how
  `skillars-deferred-97: withdrawn` etc. are kept) **and** add the
  `skillars-deferred-104-frontend-test-framework-initiative: ready-for-dev` entry; update
  `last_updated`.
- **Sibling sweep:**
  - `grep -rn "test-framework\|Vitest\|test:unit\|frontend test" _bmad-output/implementation-artifacts/*.md`
    — confirm no other story still points at a *backlog* item for this (they should all now point
    at `skillars-deferred-104`).
  - `grep -rn "npm test\|npm run test\|npm_test" pom.xml .github/` — confirm the only `npm test`
    invoker is the Maven `test`-phase execution and it still hits the stub; confirm no CI workflow
    calls `test:unit` except the new one.
- **Verify:** a reconstruction read of `deferred-work.md` shows only the intended deletion +
  annotations; `grep -c "frontend-test-framework-initiative" _bmad-output/implementation-artifacts/deferred-work.md`
  → 0.

---

## Tasks / Subtasks

- [x] **AC1** — add `vitest`, `@vue/test-utils`, `@quasar/quasar-app-extension-testing-unit-vitest`,
      `happy-dom`, `@pinia/testing`, `@vitest/coverage-v8` to `src/frontend/package.json`
      `devDependencies`; regenerate `package-lock.json` via
      `npm install --legacy-peer-deps`; commit both.
- [x] **AC2** — `quasar ext add @quasar/testing-unit-vitest`; trim scaffold to
      `vitest.config.mjs` (happy-dom, explicit `test.include`, `globals: false`) +
      `test/vitest/setup-file.js` (Quasar plugin install + `$t` stub, no global Pinia); verify
      `quasar.config.js` diff is test-only. **Deviation — see Completion Notes:** the AE is used
      library-only (scaffold placed by hand); `quasar.config.js` is left completely untouched.
- [x] **AC3** — add `test:unit` / `test:unit:watch` / `test:unit:ci` scripts; leave `test` stub
      byte-for-byte; ignore `src/frontend/coverage/`.
- [x] **AC4** — comment-only edit to `pom.xml` above the `npm test` execution explaining the
      decoupling; no wiring change.
- [x] **AC5** — new `.github/workflows/frontend-unit-tests.yml`: `workflow_dispatch` +
      label-gated `pull_request`, `npm ci` + `npm run test:unit:ci` in `src/frontend`, SHA-pinned
      actions, coverage artifact upload; not a required check, not referenced by `ci.yml`/`pr-build.yml`.
- [x] **AC6** — `prettier --write` + `eslint` clean on all new files; scoped `eslint.config.js`
      test-files override only if a rule genuinely trips. (One scoped `ignores: ['coverage/']`
      added to `eslint.config.js` — generated coverage output, not a rule disable.)
- [x] **AC7** — run `SkillsRadarChartSpec.js`; make green (fix spec to match component if drifted,
      never weaken assertions). One stale assertion fixed (confidence dot driven by
      `distinctCoachCount`, not `entryCount`); component unchanged.
- [x] **AC8** — new `src/plugins/__tests__/sessionManagerSpec.js`: stale-`rint` parse,
      countdown-timer leak on leaving the warning band, unevaluated-state safe default — pure logic
      via the **exported surface only** (`refreshExpiryState()` + reactive refs; the three logic
      functions are private and stay private), faked cookie + clock, per-test isolation.
      All 3 green; `sessionManager.js` not modified.
- [x] **AC9** — `docs/testing/frontend-unit-tests.md` (new) + `docs/testing/readme.md` pointer +
      `validation-strategy.md` subsection + `project-context.md` testing bullet +
      `src/frontend/README.md` "Run the unit tests" section (**not** `HELP.md`).
- [x] **AC10** — delete `## Backlog:` section for this initiative; annotate/close the
      dependent ledger lines; `sprint-status.yaml` → `done` + new story entry; sibling sweep.

---

## Dev Notes

### Files touched

| File | Type | Change | Risk |
|------|------|--------|------|
| `src/frontend/package.json` | UPDATE | +6 devDeps, +3 scripts; `test` stub untouched | Low — dev-only deps, `mvn verify` path unchanged |
| `src/frontend/package-lock.json` | UPDATE | regenerated (`--legacy-peer-deps`) | Low — verify no `dependencies` drift |
| `src/frontend/vitest.config.mjs` | NEW | AE-derived Vitest config | Low — not read by Quasar build |
| `src/frontend/test/vitest/setup-file.js` | NEW | Quasar plugin install + `$t` stub | Low |
| `src/frontend/quasar.config.js` | UPDATE | AE `testing` hook only | Med — **diff-review**: no `build`/`framework`/`i18n` change |
| `src/frontend/src/components/development/__tests__/SkillsRadarChartSpec.js` | UPDATE (maybe) | only if assertions drifted from component | Low |
| `src/frontend/src/plugins/__tests__/sessionManagerSpec.js` | NEW | pure-logic reference spec | Low — `sessionManager.js` not modified |
| `src/frontend/eslint.config.js` | UPDATE (maybe) | scoped test-files override, only if a rule trips | Low |
| `.gitignore` (repo root) | UPDATE | add `src/frontend/coverage/` | None |
| `.github/workflows/frontend-unit-tests.yml` | NEW | opt-in job; not a required check | Low — isolated, read-only perms |
| `pom.xml` | UPDATE | XML comment above `npm test` execution | None — comment-only |
| `docs/testing/frontend-unit-tests.md` | NEW | how-to + decoupling rationale | None |
| `docs/testing/readme.md` | UPDATE | one-line pointer to the new doc | None |
| `docs/validation-strategy.md` | UPDATE | "Frontend unit tests" subsection | None |
| `_bmad-output/project-context.md` | UPDATE | +1 testing bullet, bump date | None |
| `src/frontend/README.md` | UPDATE | "Run the unit tests" section (mirrors existing Lint/Format/Build sections) | None |
| `_bmad-output/implementation-artifacts/deferred-work.md` | UPDATE | delete backlog item; annotate dependents | None — ledger bookkeeping |
| `_bmad-output/implementation-artifacts/sprint-status.yaml` | UPDATE | backlog→done + new story entry + `last_updated` | None |

### Existing-code context the dev agent must preserve

- **`pom.xml` `frontend-maven-plugin` (`:533-597`)** — five executions:
  `install-node-and-npm`, `npm install --legacy-peer-deps`, `npm install -g @quasar/cli`,
  **`npm test`** (`:569-578`, phase `test`), `npx quasar build`. Plugin-level `<skip>${skipFrontend}</skip>`
  disables all five. The `npm test` execution calls the `test` npm script — **this story's whole
  premise is that that script stays a no-op.** Adding a `test:unit`-style call here (or renaming
  `test`) would put Vitest on every `mvn verify` — the exact outcome D1/AC4 forbid.
- **`.github/actions/frontend-quality/action.yml`** — the Prettier + ESLint composite, run by
  **both** `ci.yml` (push) and `pr-build.yml` (PR). It uses `npm ci`, `node 22.16.0`,
  `NODE_ENV=production`, `prettier --check "**/*.{js,vue,scss,json}" --ignore-path ../../.gitignore`,
  `eslint .`. Your new files go through this on the PR — AC6 is not optional. **Do not modify this
  composite** to add Vitest; it stays a lint/format gate.
- **`ci.yml` / `pr-build.yml`** — `test` job = `mvn -B verify` (+ context-count / container gates
  in pr-build). `build-and-push` `needs: [test, frontend-quality]`. Nothing here learns about the
  new workflow. Leave all three job graphs alone.
- **`SkillsRadarChartSpec.js`** — already carries a `globalConfig` with `stubs: { 'q-tooltip': true }`
  and a `mocks.$t` shim. The AC2 setup file provides a **global** `$t` stub so *future* specs don't
  repeat it; this file's local one is harmless overlap — leave it unless it conflicts.
- **`plugins/sessionManager.js`** — **this file is NOT modified by this story.** Confirmed against
  HEAD at story creation:
  - **Exported functions:** `recordActivity`, `refreshExpiryState` (calls `tick()`),
    `startSessionMonitoring` (calls `tick()` synchronously, then arms a 30 s interval),
    `stopSessionMonitoring`, `refreshSession`, `cleanup`.
  - **Exported reactive refs** (`export { … }` block near `:290`): `showWarning`, `timeUntilExpiry`,
    `secondsRemaining`, `minutesRemaining`, `isRefreshing`, `refreshFailed`, `warningThresholdSeconds`.
  - **Private — NOT exported, and AC8 must NOT export them:** `readSessionExpiryFromCookie`,
    `computeTimeUntilExpiry`, `tick`, `startCountdown`, `stopCountdown`, `markRintSeen`,
    `hasSeenRintThisTab`, `hasUserSession`. AC8 exercises their behaviour through `refreshExpiryState()`
    / `startSessionMonitoring()` and observes the exported refs.
  - **Constants** (module-private — reproduce as literals in the spec): `WARNING_THRESHOLD` = 5 min,
    `LEGACY_SESSION_TTL` = 15 min, `MIN_PLAUSIBLE_EPOCH_MS` = `1e12`, cookie name `rint`,
    `SESSION_CHECK_INTERVAL` = 30 s, `COUNTDOWN_INTERVAL` = 1 s.
  - The countdown/monitor use real `setInterval` — drive with `vi.useFakeTimers()`. Module-level
    `ref`s persist across tests in the same module instance — `cleanup()` in `afterEach` resets them;
    case 3 uses `vi.resetModules()` + dynamic `import()` for a genuinely fresh instance.

### Quasar / Vite alias reference (what the AE wires up — do not hand-maintain)

From `.quasar/tsconfig.json` `paths`: `src`, `src/*`, `app`, `app/*`, `components(/*)`,
`layouts(/*)`, `pages(/*)`, `assets(/*)`, `boot(/*)`, `stores(/*)`. App code imports via the
`src/…` prefix (e.g. `import { purchaseSessionPack } from 'src/api/payment.api'`). The Quasar AE's
`vitest.config.mjs` resolves these from `quasar.config.js`, which is why D2 chose it over a
hand-rolled map that would silently rot.

### Testing standards

- **Backend:** unchanged. `mvn -B verify` is the CI gate; do not run it locally (project policy,
  `docs/validation-strategy.md`).
- **Frontend unit (new):** Vitest + `@vue/test-utils`, `happy-dom` env, specs in `__tests__/`
  beside the code, `describe/it/expect/vi` imported explicitly (no globals). Run
  `npm run test:unit`. Never wired into `mvn verify`.
- **This story's own validation:** `cd src/frontend && npm run test:unit` green locally +
  `prettier --check` / `eslint .` green + the PR's `frontend-quality` job green + a labelled run
  of the new workflow green. The `mvn verify` CI job must show **no runtime delta** vs base
  (it never invokes Vitest).

### Project Structure Notes

- No backend package / module / naming change. All backend-side edits are comment-only (`pom.xml`).
- New frontend dirs: `src/frontend/test/vitest/` (setup), `src/frontend/src/**/__tests__/` (specs,
  co-located — matches the one pre-existing example).
- GitHub Actions pin convention: full commit SHA + `# vX.Y.Z` comment on every `uses:` — the new
  workflow must follow it (`ci.yml` is the reference).
- The new workflow is **not** a composite action and is **not** shared with `ci.yml`/`pr-build.yml`
  — that isolation is deliberate (D1).

### References

- [Source: _bmad-output/implementation-artifacts/deferred-work.md#Backlog: frontend-test-framework-initiative] — the consolidated backlog entry this story closes (deps list, dependent gaps).
- [Source: _bmad-output/implementation-artifacts/skillars-deferred-98-*.md#AC3] — formal deferral of Vitest to this initiative; backlog id created + cited.
- [Source: _bmad-output/implementation-artifacts/skillars-deferred-105-ci-build-warning-cleanup.md] — deferred-story file format; header numbering-reservation note.
- [Source: pom.xml#L533-L597] — `frontend-maven-plugin`, the five executions; `npm test` at L569-578 (phase `test`) calls the `test` npm script.
- [Source: pom.xml#L35-L38] — existing frontend-plugin summary comment (update alongside AC4).
- [Source: src/frontend/package.json] — `"test": "echo \"No test specified\" && exit 0"` (leave as-is); `scripts`, `devDependencies` style.
- [Source: .github/workflows/ci.yml] — `test` job = `mvn -B verify`; SHA-pin convention; `build-and-push` needs `[test, frontend-quality]`.
- [Source: .github/workflows/pr-build.yml#L11-L104, #L112-L122] — PR `build` job; `frontend-quality` job (runs on this PR).
- [Source: .github/actions/frontend-quality/action.yml] — Prettier `--check "**/*.{js,vue,scss,json}" --ignore-path ../../.gitignore` + `eslint .` with `NODE_ENV=production`, node `22.16.0`, `npm ci`.
- [Source: src/frontend/src/components/development/__tests__/SkillsRadarChartSpec.js] — pre-written vitest + `@vue/test-utils` spec, never runnable (AC7).
- [Source: src/frontend/src/plugins/sessionManager.js#L1-L60, #L78-L200, #L280-L295] — constants; `readSessionExpiryFromCookie` / `computeTimeUntilExpiry` / `tick` / `startCountdown` / `stopCountdown` are **private** (plain `function`); `startSessionMonitoring` calls `tick()` synchronously; `export { … }` block (`:290`) exports refs only + `cleanup` (AC8).
- [Source: src/frontend/src/components/development/SkillsRadarChart.vue] — pure computed-SVG geometry; no `getBoundingClientRect`/`getBBox`/`getContext`/`ResizeObserver` — happy-dom is sufficient for AC7.
- [Source: src/frontend/.quasar/tsconfig.json] — the `paths` alias set the AE reproduces for Vitest.
- [Source: src/frontend/eslint.config.js] — no test globals; `vue/no-bare-strings-in-template` is `error`; explicit-import convention.
- [Source: src/frontend/quasar.config.js#L56, #L73] — `extendViteConf` hook (commented), i18n vite-plugin `include` block (must not change).
- [Source: src/frontend/README.md] — existing frontend command list (Lint / Format / Build sections) — AC9 adds a "Run the unit tests" section here, not `HELP.md`.
- [Source: HELP.md] — untouched Spring-Initializr boilerplate, no command list, no frontend content — AC9 does **not** modify it.
- [Source: docs/testing/readme.md] — backend test-infrastructure docs index; AC9 adds the new doc alongside and links it here.
- [Source: docs/validation-strategy.md] — backend "no local mvn verify" policy; where the frontend subsection goes.
- [Source: _bmad-output/project-context.md#Testing Rules] — where the frontend-unit bullet goes; Node v22.16.0, Quasar 2.16 / Vue 3.5.22 / Pinia 3.0.1.
- External: Vitest 3.x docs (config `test.environment`, `test.include`, `test.setupFiles`, fake timers); `@vue/test-utils` v2 (`mount`, `config.global.mocks`); `@quasar/quasar-app-extension-testing-unit-vitest` (`quasar ext add`, `installQuasarPlugin`); `@pinia/testing` `createTestingPinia`; `happy-dom` vs `jsdom` trade-off; GitHub Actions `pull_request` `types: [labeled]` + `contains(github.event.pull_request.labels.*.name, …)` gating.

---

## Dev Agent Record

### Agent Model Used

claude-sonnet-5 (bmad-dev-story workflow)

### Debug Log References

- `cd src/frontend && npm install --legacy-peer-deps` — 85 packages added; `package-lock.json` diff is
  purely additive (0 deletions), no production-`dependencies` bump.
- `npx vitest --version` → `vitest/3.2.7`.
- `cd src/frontend && npm run test:unit` → `Test Files 2 passed (2) / Tests 12 passed (12)`.
- `cd src/frontend && npm run test:unit:ci` → same, plus V8 coverage report into `src/frontend/coverage/`, exit 0.
- `cd src/frontend && npm test` → still prints `No test specified`, exit 0 (stub untouched).
- `npx prettier --check "**/*.{js,vue,scss,json}" --ignore-path ../../.gitignore` → clean.
- `NODE_ENV=production npx eslint .` → exit 0, no findings.
- `git diff pom.xml` → comment-only.
- `grep -rn "frontend-unit-tests|test:unit" .github/workflows/ci.yml .github/workflows/pr-build.yml` → no hits.
- `grep -c "frontend-test-framework-initiative" _bmad-output/implementation-artifacts/deferred-work.md` → 0.

### Completion Notes List

**Version resolution (AC1).** AC1 pins `vitest` to v3.x; current stable is v5.x. Resolved to the v3
line to honour the AC: `vitest@^3.2.4` (installed 3.2.7), `@vitest/coverage-v8@^3.2.4`,
`@vue/test-utils@^2.4.6` (installed 2.5.0 — still the Vue-3 v2 line), `happy-dom@^15.11.6`
(matches the AE's own pin), `@pinia/testing@^1.0.3` (the 2.x line requires `pinia >=4`; this repo
is on pinia 3.0.4), `@quasar/quasar-app-extension-testing-unit-vitest@^1.2.4`.

**AE used library-only, NOT registered via `quasar ext add` (AC2 deviation, documented).**
The vitest-3.x-compatible AE line (`@quasar/…-vitest@1.x`) has an index runner that asserts
`@quasar/app-vite` `>=2.0.0 <2.4.0`; this repo is on `2.4.1`. Registering the extension in
`quasar.config.js` would fail that check on **every** `quasar dev` / `quasar build` — including
`mvn verify`'s `npx quasar build` step, i.e. exactly the regression `skillars-deferred-104`
forbids. So `vitest.config.mjs` is the AE's shipped scaffold **placed by hand** (byte-equivalent
to what `quasar ext add` renders for a no-typescript project, adapted per AC2), and
`installQuasarPlugin` is imported from the same package as a plain library helper.
**`quasar.config.js` is completely untouched** — which over-satisfies AC2's "no change to
`build`/`devServer`/`framework`/`pwa`/`i18n`" check. Alias sync with the app build is via
`vite-tsconfig-paths` reading `.quasar/tsconfig.json` (regenerated by `quasar prepare` on every
`npm install`), so the alias list is never hand-maintained. Note also that neither AE 1.x nor 2.x
actually derives config from `quasar.config.js` — both ship the identical hand-rolled
`@vitejs/plugin-vue` + `@quasar/vite-plugin` + `vite-tsconfig-paths` template; the "quasar.config
bridge" in decision D2 is an AE-3.x feature. Recorded as a residual in
`deferred-work.md` (`## Deferred from: skillars-deferred-104 implementation`): revisit when the repo
bumps `@quasar/app-vite` to v3 / `quasar` to 2.24+.

**Setup file needs a real (minimal) `vue-i18n` instance, not just a `$t` stub (AC2/AC7).**
`SkillsRadarChart.vue` calls `useI18n()` in `<script setup>` (for `locale`), which throws
"Need to install with `app.use` function" without an i18n instance on the app — the `$t` mock alone
is insufficient. `test/vitest/setup-file.js` installs `createI18n({ legacy:false, messages:{'en-US':{}}, missingWarn:false })`
on `config.global.plugins` (so `$t('some.key')` still returns the key verbatim, which is what
component specs assert), and *also* keeps the `$t`/`$tc` pass-through mocks per AC2.

**AC7 — one stale assertion fixed, component unchanged.** `SkillsRadarChartSpec.js` ran 8/9 after
the wiring. The failing case ("confidence dot filled for `entryCount >= 3`") is genuine assertion
drift: the component now drives the confidence dot from `node.skill.distinctCoachCount`, not
`entryCount` (there is an explicit NOTE comment in the component explaining why — a single coach
logging many assessments must not inflate confidence; `distinctCoachCount` is a real field on the
`SkillRadarEntry` DTO). Fixed by adding `distinctCoachCount` to the spec's `makeSkill` factory
(defaults to `entryCount`) and setting it explicitly in that one test. No assertion weakened;
`SkillsRadarChart.vue` not touched. `happy-dom` was sufficient throughout — the component is pure
computed-SVG geometry, no measurement APIs; no `happy-dom` "not implemented" errors.

**AC8 — all three cases green, `sessionManager.js` not modified.** Confirmed at HEAD that
`readSessionExpiryFromCookie` / `computeTimeUntilExpiry` / `tick` are private (plain `function`,
not in the `export {…}` block) and left them private. Cases 1 & 2 share a static import and drive
`refreshExpiryState()`; case 3 uses `vi.resetModules()` + dynamic `import('src/plugins/sessionManager')`
for a genuinely fresh instance and asserts the pre-any-call module defaults. Isolation in
`afterEach`: `cleanup()` → `vi.useRealTimers()` → `vi.restoreAllMocks()` → clear the `rint` cookie →
remove the `skillars.session.rintSeen` sessionStorage key.

**No new deferred items beyond the AE-version residual.** The ~6 dependent coverage gaps are
*unblocked*, not closed — each annotated in `deferred-work.md` with
`[FRAMEWORK AVAILABLE 2026-09-09 (skillars-deferred-104): …]` and still needs its own follow-up
story. `skillars-5-4` W9 deleted (closed by AC7). `deferred-90`/`-98` `sessionManager` note marked
partially addressed by AC8.

**Validation scope.** Per `docs/validation-strategy.md`, backend `mvn verify` was NOT run locally
(CI is the gate); this story adds zero backend runtime behaviour (`pom.xml` change is comment-only).
Frontend validation done locally: `npm run test:unit` (12/12), `npm run test:unit:ci` (coverage
report generated), `prettier --check`, `eslint .` — all green. The labelled run of
`frontend-unit-tests.yml` and the PR `frontend-quality` job are the CI-side checks (on the PR).

### File List

**New:**
- `src/frontend/vitest.config.mjs`
- `src/frontend/test/vitest/setup-file.js`
- `src/frontend/src/plugins/__tests__/sessionManagerSpec.js`
- `.github/workflows/frontend-unit-tests.yml`
- `docs/testing/frontend-unit-tests.md`

**Modified:**
- `src/frontend/package.json` (+6 devDeps, +3 `test:unit*` scripts; `test` stub byte-for-byte unchanged)
- `src/frontend/package-lock.json` (regenerated via `npm install --legacy-peer-deps`; additive only)
- `src/frontend/eslint.config.js` (scoped `ignores: ['coverage/']`)
- `src/frontend/src/components/development/__tests__/SkillsRadarChartSpec.js` (AC7 — one drifted assertion + factory field)
- `src/frontend/README.md` ("Run the unit tests" section)
- `.gitignore` (explicit `src/frontend/coverage/`)
- `pom.xml` (comment-only, above the `npm test` execution)
- `docs/testing/readme.md` (pointer + "no test runner" gap marked closed)
- `docs/validation-strategy.md` ("Frontend unit tests" subsection)
- `_bmad-output/project-context.md` (Testing Rules bullet; `_Last Updated_` bump)
- `_bmad-output/implementation-artifacts/deferred-work.md` (deleted the backlog section; annotated dependent lines; new `## Deferred from: skillars-deferred-104` residual section)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (`frontend-test-framework-initiative: backlog → done`; story entry → in-progress → review; `last_updated`)

### Change Log

| Date | Change |
|------|--------|
| 2026-09-09 | Story created (ultimate-story-context analysis). Owner decisions D1–D4 signed off: Manual + PR-label trigger, official Quasar Vitest AE, Radar + sessionManager reference specs, happy-dom env. Hard constraint captured: Vitest stays out of `mvn verify` — the Maven-invoked `test` npm script remains a no-op stub; Vitest is reachable only via the new `test:unit` script and the opt-in `frontend-unit-tests.yml` workflow. |
| 2026-09-09 | Review pass (`story-review.md`) applied. AC8 rewritten to test through the **exported surface only** — verified `readSessionExpiryFromCookie` / `computeTimeUntilExpiry` / `tick` are private at HEAD and must stay private; the three cases (stale-`rint`, countdown-timer leak, unevaluated-state default) now drive `refreshExpiryState()` / `startSessionMonitoring()` and observe exported refs. AC7 gains a verified `happy-dom`-fitness note (`SkillsRadarChart.vue` is pure computed SVG — no measurement APIs). AC5 CI step switched to `test:unit:ci` so the uploaded coverage artifact is populated. AC9 repointed: new doc at `docs/testing/frontend-unit-tests.md`, command section in `src/frontend/README.md` — `HELP.md` (Spring-Initializr boilerplate) is not touched. AC10 gains a "grep by id/keyword, don't trust `~line` numbers" guard. No AC intent changed. |
| 2026-09-09 | AC1–AC10 implemented. Version resolution held at the vitest-3.x line per AC1; the Quasar Vitest AE is used **library-only** (scaffold placed by hand, `quasar.config.js` untouched) because the vitest-3.x-compatible AE line's index runner rejects `@quasar/app-vite` 2.4.1 and would break `mvn verify`'s `quasar build`. Setup file installs a minimal real `vue-i18n` instance (required for `useI18n()` in specs). AC7: one drifted assertion fixed (confidence dot → `distinctCoachCount`), component unchanged, `happy-dom` sufficient. AC8: 3/3 green, `sessionManager.js` not modified. Ledger backlog section deleted; dependent lines annotated `[FRAMEWORK AVAILABLE 2026-09-09 …]`; `skillars-5-4` W9 removed (closed by AC7); new `## Deferred from: skillars-deferred-104` section records the AE-registration residual (revisit at `@quasar/app-vite` v3). `npm run test:unit` 12/12; prettier + eslint green; `npm test` stub and `pom.xml` wiring unchanged. Status → review. |
| 2026-09-10 | Code review triaged (see Review Findings → Patches). 2 comment-clarification patches applied (`SkillsRadarChartSpec.js` factory, `sessionManagerSpec.js` `afterEach`). 6 patches rejected as false positives / out of scope: the 2 Prettier findings (no repo gate covers `.github/**` or `.md`; `ci.yml`/`pr-build.yml`/all sibling docs fail `prettier --check`; AC6's list is explicit; AC5 says mirror `ci.yml`) — the review process had already `prettier --write`-n both files, so `frontend-unit-tests.yml` (double-quoted `node-version`) and `frontend-unit-tests.md` (embedded JS sample rewritten to double-quote+semi against repo `.prettierrc.json`, table padded) were **reverted** to house style; 1 rested on a misread (workflow runs `test:unit:ci`, coverage IS collected); 1 targeted a non-existent `.vitest/` dir (Vitest caches in `node_modules/.vite`); 2 argued against default-parameter usage / an unenforced invariant. `npm run test:unit` 12/12, prettier (AC6 scope) + eslint green. Status stays `review`. |

---

- Story created 2026-09-09. Slot `skillars-deferred-104` (reserved by `skillars-deferred-103` D3,
  cited by `skillars-deferred-98` AC3). Scope = stand up the runner + wiring + two reference specs
  + docs + ledger housekeeping; the ~6 remaining coverage gaps each reopen as in-scope for their
  own follow-up story once the runner exists (per the backlog entry's own closing note). No open
  questions — proceed with the ACs as written.

---

## Review Findings

### Decision Needed

(None — verified `readSessionExpiryFromCookie` is private and correctly exercised through exported `refreshExpiryState()` → `tick()` → `computeTimeUntilExpiry()` chain, per AC8 spec.)

### Patches

**Triage (2026-09-10):** 2 applied, 6 rejected as false positives / out of scope. Detail below.

- [x] [Review][Patch] **APPLIED.** Document null parameter cascade in `makeSkill()` factory. The
  factory already carried a comment on the `distinctCoachCount = entryCount` default; extended it
  to state explicitly that an unscored skill (`entryCount = null`) yields `distinctCoachCount = null`
  — intentional, matching the real `SkillRadarEntry` DTO ("null if no assessments"), read by the
  component as an empty low-confidence dot.

- [x] [Review][Patch] **APPLIED.** Documentation comment in `sessionManagerSpec.js`. Expanded the
  `afterEach` comment to spell out the isolation model — cases 1 & 2 share the static import and
  rely on `cleanup()`; case 3 additionally does `vi.resetModules()` + re-`import()` for a fresh
  module instance.

- [x] [Review][Patch] **REJECTED — false positive (defaults exist for this).** "Inconsistent test
  case parameterization — lines 98/108 call `makeSkill(..., 3)` relying on the default while line
  129 sets `makeSkill(..., 5, 3)`." Lines 98/108 are the *baseline-ghost-polygon* tests — they
  assert on `polygon[stroke-dasharray]` and never touch the confidence dot, so `distinctCoachCount`
  is irrelevant to them and the `= entryCount` default is exactly right. Line 129 spells it out
  only because that one test *does* check the dot and needs `distinctCoachCount ≥ 3` while showing
  it is distinct from `entryCount`. Forcing every unrelated call site to pass a param it does not
  use is the opposite of why a default parameter exists. No change.

- [x] [Review][Patch] **REJECTED — out of scope + invented invariant.** "Add a negative test for
  `distinctCoachCount > entryCount`." AC7's scope is "make the existing spec run and pass, do not
  change component behaviour." There is no `distinctCoachCount ≤ entryCount` invariant anywhere in
  the frontend — `confidenceDotFill()` reads only `distinctCoachCount`; `entryCount` is not even
  passed to it. A new data-integrity test for a rule the code does not enforce is scope creep for
  a runner-stand-up story. If such an invariant is ever wanted it is its own follow-up.

- [x] [Review][Patch] **REJECTED — false positive; the fix would violate AC5.** "Reformat
  `frontend-unit-tests.yml` for Prettier — AC6 requires Prettier compliance." AC6's Prettier list
  is `vitest.config.mjs`, `setup-file.js`, the two specs, `package.json`, `quasar.config.js` — not
  workflow YAML. The `frontend-quality` gate runs `prettier --check "**/*.{js,vue,scss,json}"` from
  `src/frontend` — it cannot see `.github/` and does not glob `.yml`. Nothing in the repo Prettier-
  checks `.github/**`; `ci.yml` and `pr-build.yml` themselves *fail* `prettier --check` and use
  single-quoted `node-version: '22.16.0'`. AC5 says "mirror `ci.yml` / `pr-build.yml`". The review
  process had already run `prettier --write` on the file (double-quoting line 45); **reverted** to
  single quotes to match the workflow corpus.

- [x] [Review][Patch] **REJECTED — false positive; the fix was actively harmful.** "Reformat
  `docs/testing/frontend-unit-tests.md` for Prettier — AC6 requires Prettier compliance." AC6 and
  the `frontend-quality` action *deliberately* exclude `.md` (the action comment says so). Every
  sibling doc in `docs/testing/` fails `prettier --check`; the repo does not Prettier docs. The
  review process had already run `prettier --write` from the repo root — which resolves **no**
  Prettier config (the real one is `src/frontend/.prettierrc.json`: `singleQuote`, `semi:false`),
  so it rewrote the embedded ` ```js ` sample to double-quotes + semicolons, i.e. the *opposite* of
  this repo's actual JS style and of the real `vitest.config.mjs`. **Reverted** the embedded sample
  and the padded table to house style.

- [x] [Review][Patch] **REJECTED — false premise.** "Add a comment to the workflow coverage step:
  the job runs `npm run test:unit` (not `:ci`), so coverage is not collected." The workflow step
  runs `npm run test:unit:ci` (line 55), which is `vitest run --coverage`. Coverage *is* produced;
  `if-no-files-found: warn` is ordinary defensiveness. Nothing to explain.

- [x] [Review][Patch] **REJECTED — speculative; no such directory.** "Add `.vitest/**` to
  `eslint.config.js` ignores." Vitest 3 caches under `node_modules/.vite` (already ESLint-ignored
  via the Quasar preset's `node_modules` ignore). No project-root `.vitest/` is created — verified
  after full runs. `coverage/` was ignored because it genuinely appears; adding ignores for a path
  that never materialises is cargo-culting.

### Deferred

- [x] [Review][Defer] Quasar AE version cliff on `@quasar/app-vite` — documented risk, not actionable in this story. The `@quasar/quasar-app-extension-testing-unit-vitest@1.x` runner rejects `@quasar/app-vite >= 2.4.0`. Current workaround: use AE library-only (scaffold by hand, `quasar.config.js` untouched). Revisit when `@quasar/app-vite` bumps to v3.

- [x] [Review][Defer] happy-dom vs jsdom trade-off edge cases — deferred to AC7 runtime. If `SkillsRadarChart.vue` uses DOM APIs that `happy-dom` doesn't support, add `// @vitest-environment jsdom` to that spec and `jsdom` to devDeps then.

- [x] [Review][Defer] npm test single point of failure governance — pom.xml comment is defensive; a developer could accidentally change `<arguments>test</arguments>` to `<arguments>test:unit</arguments>`, breaking the hard constraint. Governance issue, out of scope for this story.

- [x] [Review][Defer] Spec glob pattern doesn't catch `.ts`/`.mts`/`.spec.ts` — TypeScript not in use; low priority. When TypeScript is added, update `test.include` pattern.

### Dismissed (Noise / Already Handled)

- Coverage artifacts directory already in `.gitignore` (explicit `src/frontend/coverage/`)
- Node version pinning (`22.16.0`) is compatible with all dependencies

---

**Summary (as received):** 0 decision needed, 8 patches, 4 deferred, 2 dismissed.

**Post-triage (2026-09-10):** of the 8 patches, **2 applied** (comment clarifications in
`SkillsRadarChartSpec.js` and `sessionManagerSpec.js`) and **6 rejected as false positives / out of
scope** — 2 Prettier findings were not only wrong (no gate covers `.github/**` or `.md`; both fail
`prettier --check` repo-wide) but had been auto-applied by the review process in a way that broke
house style, and were **reverted**; 1 rested on a misread of the workflow (it runs `test:unit:ci`,
which does collect coverage); 1 targeted a Vitest cache dir that is not created; 2 argued against
the purpose of a default parameter / added an invariant the code does not enforce. The 4 Deferred
and 2 Dismissed items stand. `npm run test:unit` 12/12, `prettier --check` (AC6 scope) and
`eslint .` green after the applied edits and the reverts.
