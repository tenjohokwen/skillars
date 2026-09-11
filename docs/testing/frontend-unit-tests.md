# Frontend Unit Tests (Vitest + Vue Test Utils)

Stood up by `skillars-deferred-104`. This is the frontend counterpart to the backend
integration-test docs in this folder — but wired very differently on purpose.

---

## The one thing to know first: this suite is NOT part of `mvn verify`

`mvn verify` (every push to `master`, every PR) builds the frontend via
`frontend-maven-plugin`, but it does **not** run these tests. The Maven `npm test` execution
calls the `test` npm script, and that script is a permanent no-op stub:

```jsonc
"test": "echo \"No test specified\" && exit 0"
```

Vitest is reachable **only** through the separate `test:unit` scripts. Nothing in `pom.xml`
calls them. Do not rename `test` or point it at Vitest — keeping it a stub is the whole
mechanism that keeps the unit suite off the critical build path (`skillars-deferred-104`,
owner decision D1).

The suite runs in exactly two places:

| Where | How |
|---|---|
| Locally, on demand | `cd src/frontend && npm run test:unit` |
| CI, on demand | `.github/workflows/frontend-unit-tests.yml` — **manual dispatch** (Actions tab) or add the **`frontend-tests` label** to a PR |

The CI job is never a required status check and is not referenced by `ci.yml` / `pr-build.yml`.
Apply the `frontend-tests` label to a PR when a change actually touches frontend logic and you
want the suite to gate your own review.

### Adding the `frontend-tests` label to a PR

The label name the workflow checks is exactly **`frontend-tests`** (lowercase, hyphenated).
"Frontend Unit Tests" is the *workflow* name shown in the Actions tab, not the label. The
job-level `if` gate is `contains(github.event.pull_request.labels.*.name, 'frontend-tests')`.

**GitHub web UI:** open the PR → **Labels** (gear icon, right sidebar) → tick **`frontend-tests`**.

**`gh` CLI:**

```bash
gh pr edit --add-label "frontend-tests"        # current branch's PR
gh pr edit <number> --add-label "frontend-tests"
gh pr create --label "frontend-tests" ...      # set it at creation time
```

Because the workflow triggers on `pull_request: types: [opened, labeled, …]`, **adding the label
starts a run immediately** — no new commit needed — and `gh pr create --label` fires it at creation
in one step (the `opened` trigger, added by `skillars-deferred-109` AC13). Once the PR is labelled, `synchronize`
re-runs the suite on every subsequent push. Removing the label does not cancel an in-flight
run but stops future pushes from triggering it. The label must already exist in the repo
(it is defined as `frontend-tests`, colour `#1D76DB`); `gh label list` confirms it.

---

## Running it

```bash
cd src/frontend

npm run test:unit          # one-shot run (vitest run)
npm run test:unit:watch    # watch mode while developing
npm run test:unit:ci       # one-shot + V8 coverage report into src/frontend/coverage/
```

Coverage has **no thresholds** — it is report-only. `coverage/` is git-ignored.

---

## Where specs live and how they are discovered

Specs sit **beside the code**, in a `__tests__/` folder:

```
src/components/development/__tests__/SkillsRadarChartSpec.js
src/plugins/__tests__/sessionManagerSpec.js
```

`vitest.config.mjs` `test.include` picks up both layouts:

```js
include: [
  // sibling *.spec.js / *.test.js (future)
  'src/**/*.{spec,test}.{js,mjs}',
  // the existing __tests__/…Spec.js layout
  'src/**/__tests__/**/*.{js,mjs}',
]
```

`describe` / `it` / `expect` / `vi` are **imported explicitly** in every spec —
`test.globals` is `false` and `eslint.config.js` registers no test-runner globals. Keep doing
that; it is the house style.

---

## The config model

`vitest.config.mjs` is the scaffold shipped by the official Quasar Vitest App Extension
(`@quasar/quasar-app-extension-testing-unit-vitest`, owner decision D2), **placed by hand**
rather than registered through `quasar ext add`.

Why by hand: the AE's index runner pins `@quasar/app-vite` to `>=2.0.0 <2.4.0`, and this repo
is on `2.4.1`. Registering the extension in `quasar.config.js` would make **every**
`quasar dev` / `quasar build` — including `mvn verify`'s `npx quasar build` — fail that
compatibility check. So the AE is used purely as a library: this config plus
`installQuasarPlugin()` from the same package in the setup file. `quasar.config.js` is
untouched.

The config wires:

- `@vitejs/plugin-vue` + `@quasar/vite-plugin` (Quasar components, `$q`, SCSS variables from
  `src/css/quasar.variables.scss`).
- `vite-tsconfig-paths` — resolves the `src/`, `components/`, `stores/`, `boot/` … import
  aliases by reading `.quasar/tsconfig.json`, which `quasar prepare` regenerates from
  `quasar.config.js` on every `npm install`. The alias list is therefore never hand-maintained
  and stays in lockstep with the app build.
- `environment: 'happy-dom'` (owner decision D4 — faster startup / lower memory than `jsdom`;
  sufficient for the component mounts in this codebase). A future spec that needs an API
  `happy-dom` stubs incompletely can put `// @vitest-environment jsdom` at the top of that file
  and add `jsdom` as a dev dep at that point.

### `test/vitest/setup-file.js`

Runs once before each spec file:

- `installQuasarPlugin()` — registers the Quasar Vue plugin for mounted components.
- A minimal real `vue-i18n` instance on `config.global.plugins` — **required**, not just
  convenience: components that call `useI18n()` in `<script setup>` (e.g.
  `SkillsRadarChart.vue`) throw without an i18n instance on the app. `messages: {}` +
  silenced missing-key warnings means `$t('some.key')` returns the key verbatim, which is what
  component specs assert against.
- A `$t` / `$tc` pass-through stub on `config.global.mocks` (mirrors the inline mock the
  pre-existing radar spec carried).
- Pinia is **not** started globally. Specs that need a store opt in with `createTestingPinia()`
  from `@pinia/testing`.

---

## Two reference specs — templates for follow-up work

`skillars-deferred-104` shipped two specs, one of each style, so the ~6 recorded frontend
coverage gaps can each be closed in their own follow-up story against a known pattern:

- **`SkillsRadarChartSpec.js`** — a real `@vue/test-utils` `mount()` of a component. Exercises
  the Quasar / i18n / alias wiring end to end.
- **`sessionManagerSpec.js`** — **pure logic, no `mount`**. Faked `document.cookie` +
  `vi.useFakeTimers()` / `vi.setSystemTime()`, driving the module through its **exported
  surface only** (private functions stay private) and asserting on exported reactive refs.
  Per-test isolation via `cleanup()` + `vi.useRealTimers()` + `vi.restoreAllMocks()` in
  `afterEach`, and `vi.resetModules()` + dynamic `import()` where a genuinely fresh module
  instance is needed.

---

## Dependencies (all `devDependencies`, dev-only)

`vitest`, `@vitest/coverage-v8`, `@vue/test-utils`,
`@quasar/quasar-app-extension-testing-unit-vitest`, `happy-dom`, `@pinia/testing`.

None are in `pom.xml`, `dependencies`, or any existing GitHub Actions job.
