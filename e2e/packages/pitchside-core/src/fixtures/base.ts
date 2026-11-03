import { test as base, type BrowserContext, type Page } from '@playwright/test'
import type { SuiteEnv } from '../config/SuiteEnv.js'
import {
  interpolate,
  waitForSettledRoute,
  type RouteMatcher,
  type SettledRouteOptions,
} from '../config/routing.js'
import { IdAllocator } from '../data/IdAllocator.js'
import {
  ContextSeeder,
  type SeedContext,
  type Seeder,
  type SeedSpec,
  type SqlExecutor,
} from '../data/Seeder.js'
import { UniqueNames } from '../data/UniqueNames.js'
import { A11yGate } from '../gates/a11y.js'
import { ConsoleGate } from '../gates/console.js'
import { parseGateMode, type GateMode } from '../gates/GateMode.js'
import { ApiClient } from '../http/ApiClient.js'
import type { MailSink } from '../mail/MailSink.js'
import { NetworkGate } from '../net/intercept.js'
import { ANNOTATION, isQuarantineExpired } from '../tags/scenario.js'
import { systemClock, type Clock } from '../time/Clock.js'
import { SessionStore, type SessionContextOptions } from './sessions.js'

export interface Actor {
  role: string
  context: BrowserContext
  page: Page
}

export interface PitchsideTestFixtures {
  /** The app's environment contract. */
  suite: SuiteEnv
  consoleGateMode: GateMode
  networkGateMode: GateMode
  consoleGate: ConsoleGate
  networkGate: NetworkGate
  /**
   * A logged-in browser context for a role, with gates attached and closed after the test. Call it
   * twice for a two-persona test (e.g. a coach and a parent acting on the same booking).
   * The default `page` fixture stays anonymous — a guest.
   */
  actor: (role: string) => Promise<Actor>
  /** Navigate the default page to an app route (hash or history handled by the env's routing). */
  gotoRoute: (route: string, params?: Record<string, string | number | bigint>) => Promise<void>
  /** Wait until the default page's route matches and stops changing. */
  settledRoute: (matcher: RouteMatcher, options?: SettledRouteOptions) => Promise<string>
  seed: <T>(spec: SeedSpec<T>) => Promise<T>
  mail: MailSink
  a11y: A11yGate
  clock: Clock
  /** Auto: fails a quarantined test whose quarantine has expired. */
  quarantineGuard: void
}

export interface PitchsideWorkerFixtures {
  ids: IdAllocator
  names: UniqueNames
  api: ApiClient
  workerSql: SqlExecutor | undefined
  seedContext: SeedContext
  seeder: Seeder
  sessions: SessionStore
}

async function primeAndGuard(
  context: BrowserContext,
  suite: SuiteEnv,
  consoleGate: ConsoleGate,
  networkGate: NetworkGate,
  role?: string,
): Promise<void> {
  const priming = (await suite.auth.priming?.(role)) ?? []
  if (priming.length > 0) await context.addCookies(priming)
  await networkGate.install(context)
  // After the gate: Playwright matches the most recently registered route first, so app stubs
  // registered here serve their hosts before the gate can block them.
  await suite.prepareContext?.(context)
  for (const page of context.pages()) consoleGate.attach(page)
  context.on('page', (page) => consoleGate.attach(page))
}

interface OpenedActor {
  role: string
  context: BrowserContext
  /** The role's invalidation epoch when this actor was created. */
  epoch: number
  /** False if setup failed part-way: such a context is closed but never written back. */
  ready: boolean
}

/**
 * Hands each role's session on to the next test, before the contexts close. If the app rotated its
 * refresh token during the test, the next test must start from the rotated one, never replay the
 * original (reuse detection treats that as theft). Three cases never write back:
 * - the test invalidated the role (logout, password reset): its explicit invalidate stands;
 * - two or more contexts used the role in one test: which one holds the current token is unknowable,
 *   so the role is invalidated and the next test logs in afresh;
 * - setup failed part-way: there is no session worth keeping.
 */
async function writeBackSessions(
  opened: readonly OpenedActor[],
  sessions: SessionStore,
  options: SessionContextOptions,
): Promise<void> {
  const byRole = new Map<string, OpenedActor[]>()
  for (const entry of opened) byRole.set(entry.role, [...(byRole.get(entry.role) ?? []), entry])
  for (const [role, entries] of byRole) {
    if (entries.length > 1) {
      sessions.invalidate(role)
      continue
    }
    const [only] = entries
    if (!only?.ready || sessions.epoch(role) !== only.epoch) continue
    try {
      sessions.update(role, options, await only.context.storageState())
    } catch {
      sessions.invalidate(role)
    }
  }
}

/**
 * Builds the extended `test` for one app. Every fixture is lazy except the context override and
 * the quarantine guard, so a test pays only for what it uses.
 */
export function createSuiteTest(env: SuiteEnv) {
  return base.extend<PitchsideTestFixtures, PitchsideWorkerFixtures>({
    suite: [env, { option: true }],
    consoleGateMode: [
      parseGateMode(process.env.PITCHSIDE_CONSOLE_GATE, env.consoleGate ?? 'strict'),
      { option: true },
    ],
    networkGateMode: [
      parseGateMode(process.env.PITCHSIDE_NETWORK_GATE, env.networkGate ?? 'strict'),
      { option: true },
    ],

    consoleGate: async ({ consoleGateMode, suite }, use) => {
      await use(new ConsoleGate({ mode: consoleGateMode, ignore: suite.consoleIgnore }))
    },

    networkGate: async ({ networkGateMode, suite }, use) => {
      await use(
        new NetworkGate({
          mode: networkGateMode,
          firstPartyOrigins: [suite.baseUrl, suite.apiBaseUrl],
          allow: suite.thirdPartyAllowList,
        }),
      )
    },

    context: async ({ context, suite, consoleGate, networkGate }, use, testInfo) => {
      await primeAndGuard(context, suite, consoleGate, networkGate)
      await use(context)
      const consoleFound = consoleGate.violations()
      const networkFound = networkGate.violations()
      if (consoleFound.length > 0 || networkFound.length > 0) {
        await testInfo.attach('pitchside-gates.json', {
          contentType: 'application/json',
          body: JSON.stringify({ console: consoleFound, network: networkFound }, null, 2),
        })
      }
      // Only blame the gates when the test body itself passed — otherwise the real failure stays
      // first. (Not `status === expectedStatus`: that would silently disable gates under test.fail().)
      if (testInfo.status === 'passed') {
        consoleGate.assertClean()
        networkGate.assertClean()
      }
    },

    actor: async (
      {
        browser,
        sessions,
        suite,
        consoleGate,
        networkGate,
        viewport,
        locale,
        timezoneId,
        baseURL,
        userAgent,
      },
      use,
    ) => {
      // Same user agent for login and replay: fingerprint-pinned sessions reject a mismatch.
      const sessionOptions = { userAgent, locale }
      const opened: OpenedActor[] = []
      await use(async (role: string) => {
        const epoch = sessions.epoch(role)
        const context = await browser.newContext({
          storageState: await sessions.state(role, sessionOptions),
          viewport,
          locale,
          timezoneId,
          baseURL,
          userAgent,
        })
        // Tracked for teardown BEFORE any setup that can fail, so a half-built context is closed.
        const entry: OpenedActor = { role, context, epoch, ready: false }
        opened.push(entry)
        await primeAndGuard(context, suite, consoleGate, networkGate, role)
        const page = await context.newPage()
        entry.ready = true
        return { role, context, page }
      })
      await writeBackSessions(opened, sessions, sessionOptions)
      for (const { context } of opened) await context.close().catch(() => undefined)
    },

    gotoRoute: async ({ page, suite }, use) => {
      await use(async (route, params) => {
        await page.goto(suite.routing.toUrl(interpolate(route, params)))
      })
    },

    settledRoute: async ({ page, suite }, use) => {
      await use((matcher, options) => waitForSettledRoute(page, suite.routing, matcher, options))
    },

    seed: async ({ seeder }, use) => {
      await use((spec) => seeder.seed(spec))
    },

    mail: async ({ suite }, use) => {
      if (!suite.mail) throw new Error(`${suite.name}: no mail sink configured in the SuiteEnv`)
      await use(suite.mail)
    },

    a11y: async ({ suite }, use) => {
      if (!suite.a11yBaselineDir)
        throw new Error(`${suite.name}: set a11yBaselineDir to use the a11y gate`)
      await use(
        new A11yGate({
          baselineDir: suite.a11yBaselineDir,
          update: process.env.PITCHSIDE_A11Y_UPDATE === '1',
        }),
      )
    },

    clock: async ({ suite }, use) => {
      await use(suite.clock ?? systemClock)
    },

    quarantineGuard: [
      async ({}, use, testInfo) => {
        const expires = testInfo.annotations.find(
          (a) => a.type === ANNOTATION.quarantineExpires,
        )?.description
        if (expires && isQuarantineExpired(expires)) {
          throw new Error(
            `Quarantine expired on ${expires}: fix this test or delete it — a permanently skipped test is a lie about coverage`,
          )
        }
        await use()
      },
      { auto: true },
    ],

    // ── worker scope ──────────────────────────────────────────────────────────────────────────
    ids: [
      async ({}, use, workerInfo) => {
        await use(new IdAllocator(workerInfo.workerIndex, env.ids))
      },
      { scope: 'worker' },
    ],

    names: [
      async ({}, use, workerInfo) => {
        await use(
          new UniqueNames(workerInfo.workerIndex, {
            emailDomain: env.emailDomain,
            runId: env.runId,
          }),
        )
      },
      { scope: 'worker' },
    ],

    api: [
      async ({ playwright }, use) => {
        const request = await playwright.request.newContext()
        await use(new ApiClient(request, { ...env.api, baseUrl: env.apiBaseUrl }))
        await request.dispose()
      },
      { scope: 'worker' },
    ],

    workerSql: [
      async ({}, use) => {
        const sql = env.sql ? await env.sql() : undefined
        try {
          await use(sql)
        } finally {
          await sql?.end?.()
        }
      },
      { scope: 'worker' },
    ],

    seedContext: [
      async ({ ids, names, api, workerSql }, use) => {
        await use({ ids, names, api, sql: workerSql, clock: env.clock ?? systemClock })
      },
      { scope: 'worker' },
    ],

    seeder: [
      async ({ seedContext }, use) => {
        await use(
          env.seeder ? env.seeder(seedContext) : new ContextSeeder({ context: seedContext }),
        )
      },
      { scope: 'worker' },
    ],

    sessions: [
      async ({ browser }, use) => {
        await use(new SessionStore(browser, env.auth))
      },
      { scope: 'worker' },
    ],
  })
}

export type PitchsideTest = ReturnType<typeof createSuiteTest>
