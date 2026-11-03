import {
  ApiSessionStrategy,
  defineSuite,
  hashRouting,
  MailpitSink,
  type ScenarioDetails,
  type ScenarioMeta,
} from 'pitchside-core'
import {
  closeAccounts,
  credentialsFor,
  ensureBaseline,
  provisioner,
  type Baseline,
  type BaselineRole,
} from './data/accounts.js'
import { FINGERPRINT, type Provisioner } from './data/provisioner.js'
import { API_BASE_URL, E2E } from './env.js'
import { stubStripeJs } from './stubs/stripe.js'
import { taxonomy } from './taxonomy.js'

/**
 * The Skillars SuiteEnv. Everything app-specific the kernel needs is here; the kernel itself never
 * names Skillars (see _bmad-output/project-context.md §6).
 */
export const skillars = defineSuite({
  name: 'skillars',
  baseUrl: E2E.baseUrl,
  apiBaseUrl: API_BASE_URL,
  routing: hashRouting(E2E.baseUrl),
  // Login sets HttpOnly potc/rtkn/bcookie/ION plus the readable `skp` cookie the router guard
  // hydrates from. The backend refuses a login without `fcookie` (it becomes the session's client
  // id) and pins the session to the User-Agent: the kernel's SessionStore logs in with the same UA
  // the test contexts use, and every context is primed with the same fingerprint.
  auth: new ApiSessionStrategy({
    apiBaseUrl: API_BASE_URL,
    loginPath: '/auth/login',
    logoutPath: '/auth/logout',
    credentials: credentialsFor,
    body: ({ username, password }) => ({ email: username, password }),
    priming: async () => [
      { name: 'fcookie', value: FINGERPRINT, url: E2E.baseUrl, sameSite: 'Lax' },
    ],
  }),
  taxonomy,
  // Above the backend integration suite's own fixture-id registry, so UI-seeded rows never collide.
  ids: { base: 9_650_000_000n },
  emailDomain: E2E.emailDomain,
  runId: E2E.runId,
  mail: new MailpitSink({ baseUrl: E2E.mailpitUrl }),
  // Allowed beyond the app's own origin: the Google Fonts the SPA's index.html loads (intentional), and
  // object storage, which the browser talks to directly through presigned URLs. Everything else
  // third-party is blocked and recorded by the network gate.
  thirdPartyAllowList: [
    /^https:\/\/fonts\.googleapis\.com\//,
    /^https:\/\/fonts\.gstatic\.com\//,
    originPattern(E2E.storageUrl),
  ],
  // Default Stripe tier: serve a local Stripe.js stub; js.stripe.com is never reached.
  prepareContext: stubStripeJs,
})

/** Matches every URL on exactly this origin (scheme, host and port). */
function originPattern(url: string): RegExp {
  const origin = new URL(url).origin.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return new RegExp(`^${origin}(?:/|$)`)
}

interface SkillarsWorkerFixtures {
  /** The worker's baseline accounts, provisioned on first use. */
  baseline: <R extends BaselineRole>(role: R) => Promise<Baseline[R]>
  /** Create fresh accounts for a test that must not share the baseline. */
  provision: Provisioner
}

export const test = skillars.test.extend<object, SkillarsWorkerFixtures>({
  baseline: [
    async ({}, use) => {
      await use(ensureBaseline)
    },
    { scope: 'worker' },
  ],
  provision: [
    async ({}, use) => {
      await use(await provisioner())
      await closeAccounts()
    },
    { scope: 'worker' },
  ],
})

export const expect = skillars.expect
export const scenario = (meta: ScenarioMeta): ScenarioDetails => skillars.scenario(meta)
