# pitchside-core

The project-agnostic half of the Pitchside UI test framework: a Playwright + TypeScript kernel that
knows nothing about any application. An app package (for this repository, the forthcoming
`skillars-e2e`) implements its contracts and holds every spec file.

**The rule:** nothing in `src/` may import from, or name, an application. Three mechanisms hold the
line, because a "reusable core" stops being reusable about three sprints after nobody checks:

1. an ESLint `no-restricted-imports` pattern that rejects any `*-e2e` package import;
2. `test/unit/portability.test.ts`, which fails on app names in source and on any import other than
   Node built-ins, Playwright, axe-core and the kernel's own modules;
3. the CI `portability` job, which copies this directory out of the workspace on its own, with every
   app package absent, and requires it to install, build and pass its unit tests.

## What is in the box

| Area             | Module                                        | Contract                                                                                                                                              |
| ---------------- | --------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- |
| Suite definition | `config/defineSuite.ts`, `config/SuiteEnv.ts` | One `SuiteEnv` object describes the app; `defineSuite(env)` validates it and returns `{ test, expect, scenario, grep, use }`.                         |
| Routing          | `config/routing.ts`                           | `hashRouting` / `historyRouting`, `interpolate`, `routePattern`, `waitForSettledRoute` (waits past async-guard redirects).                            |
| Run policy       | `config/playwright.ts`                        | `baseRunPolicy()` (traces/video on failure, 1 CI retry, `forbidOnly`), `laneProjects()` (parallel + serial lane for `@destructive`/`@config`).        |
| Fixtures         | `fixtures/base.ts`                            | Gated `context`, `actor(role)` for multi-persona tests, `gotoRoute`, `settledRoute`, `seed`, `mail`, `a11y`, `api`, `ids`, `names`, quarantine guard. |
| Sessions         | `fixtures/sessions.ts`                        | One storage state per role per worker; failed logins are not cached; `invalidate()` after session-revoking tests.                                     |
| Auth             | `auth/AuthStrategy.ts`                        | `ApiSessionStrategy` (default), `UiLoginStrategy` (for the auth suite), `CookieSessionStrategy`, `withPriming`.                                       |
| Mail             | `mail/*`                                      | `MailSink` search contract; `MailpitSink` (REST) and `LogDirSink` (a directory of RFC 822 dumps, pluggable parser).                                   |
| HTTP             | `http/ApiClient.ts`                           | Base-URL joining, bounded retry of idempotent calls on 502/503/504, a request id on every call, errors carrying method/URL/status/body.               |
| Data             | `data/*`                                      | `Seeder` + `SeedSpec` contract, `ContextSeeder`, `sqlTruncateResetter` (protected tables), worker-scoped `IdAllocator` and `UniqueNames`.             |
| Screens          | `screens/Screen.ts`, `selectors/testid.ts`    | Thin screen objects that never assert; the one selector policy (`data-testid`, lower-kebab-case).                                                     |
| Gates            | `gates/*`, `net/intercept.ts`                 | Console gate, network allow-list gate, axe gate with per-screen baselines, masked screenshot policy, horizontal-overflow check.                       |
| Network stubs    | `net/stubs.ts`                                | `stubJson`, `failFirst` (transient outage), `holdRequests` (pending states).                                                                          |
| Time             | `time/Clock.ts`                               | `CalendarDate`, relative dates, `dateOfBirthForAge` (never on a birthday), `window()` (end derived from start).                                       |
| Tags             | `tags/*`                                      | Four-axis taxonomy (scope, persona, domain, trait), `scenario()` details with priority annotations and time-boxed quarantine, `buildGrep`.            |

## Minimal adapter

```ts
import {
  ApiSessionStrategy,
  defineSuite,
  defineTaxonomy,
  hashRouting,
  MailpitSink,
  requiredEnv,
} from 'pitchside-core'

const baseUrl = requiredEnv('E2E_BASE_URL')

export const app = defineSuite({
  name: 'my-app',
  baseUrl,
  apiBaseUrl: `${baseUrl}/api`,
  routing: hashRouting(baseUrl),
  auth: new ApiSessionStrategy({
    apiBaseUrl: `${baseUrl}/api`,
    loginPath: '/auth/login',
    credentials: (role) => ({
      username: `${role}@example.test`,
      password: requiredEnv('E2E_PASSWORD'),
    }),
  }),
  taxonomy: defineTaxonomy({ persona: ['@guest', '@member'], domain: ['@billing'] }),
  ids: { base: 9_000_000_000n },
  emailDomain: 'example.test',
  mail: new MailpitSink({ baseUrl: requiredEnv('E2E_MAILPIT_URL') }),
})

export const { test, expect } = app
```

```ts
test(
  'a member sees their invoices',
  app.scenario({ id: 'BIL-01', priority: 'P0', tags: ['@smoke', '@member', '@billing'] }),
  async ({ actor, suite }) => {
    const { page } = await actor('member')
    await new InvoicesScreen(page, suite).open()
  },
)
```

`test/browser/toySuite.ts` is a complete, working adapter for the toy app the self-tests run against.

## Gates

Every test gets a console gate and a network gate on every page of every context it opens (the default
`page`, and each `actor`). Modes are `strict` (fail), `warn` (attach to the report) or `off`; set them
per env (`consoleGate`, `networkGate`), per test file (`test.use({ consoleGateMode: 'warn' })`), or per
run (`PITCHSIDE_CONSOLE_GATE`, `PITCHSIDE_NETWORK_GATE`). Gates only fail a test whose body passed, so the
real failure is always reported first.

The console gate ignores the browser's own `Failed to load resource: … 4xx/5xx` messages by default —
the browser emits one for every non-2xx response, which would fail every legitimate negative test.
Assert HTTP outcomes explicitly instead.

## Running

```sh
cd e2e
npm ci
npm test -w pitchside-core                 # unit tests (vitest, no browser)
npm run test:browser -w pitchside-core     # self-tests against the toy app (Playwright)
```

Local host quirks, both opt-in:

- `PITCHSIDE_BROWSER_CHANNEL=chrome` — use an installed Google Chrome where the bundled Chromium does
  not launch.
- `PITCHSIDE_VIDEO=off` — where Playwright ships no ffmpeg for the OS (e.g. macOS 13).

`PITCHSIDE_A11Y_UPDATE=1` rewrites a11y baselines from the current findings instead of failing.

## Maintaining the lockfile

Regenerate `e2e/package-lock.json` with npm 11 (`npx npm@11 install`): npm 10's resolver crashes on a
fresh tree containing vitest's optional peer set. `npm ci` from the committed lockfile works on both.
