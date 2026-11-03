import type { BrowserContext } from '@playwright/test'
import type { AuthStrategy } from '../auth/AuthStrategy.js'
import type { IdAllocatorOptions } from '../data/IdAllocator.js'
import type { SeedContext, Seeder, SqlExecutor } from '../data/Seeder.js'
import type { GateMode } from '../gates/GateMode.js'
import type { VisualPolicy } from '../gates/visual.js'
import type { ApiClientOptions } from '../http/ApiClient.js'
import type { MailSink } from '../mail/MailSink.js'
import type { TagTaxonomy } from '../tags/taxonomy.js'
import type { Clock } from '../time/Clock.js'
import type { Routing } from './routing.js'

/**
 * Everything the kernel needs to know about an app, in one object. Anything an app needs beyond
 * this belongs in the app package, not here.
 */
export interface SuiteEnv {
  /** Shown in reports and errors. */
  readonly name: string
  /** Where the SPA is served. */
  readonly baseUrl: string
  /** Where the API is served (may equal baseUrl + "/api"). */
  readonly apiBaseUrl: string
  readonly routing: Routing
  readonly auth: AuthStrategy
  readonly taxonomy: TagTaxonomy
  /** The id range reserved for UI-seeded rows. */
  readonly ids: IdAllocatorOptions
  /** Reserved domain for generated email addresses, e.g. "example.test". */
  readonly emailDomain: string
  /** Correlates generated names and request ids with one CI run. */
  readonly runId?: string
  readonly mail?: MailSink
  /** Opens this worker's SQL connection, if the app seeds through SQL. Closed by the kernel. */
  readonly sql?: () => SqlExecutor | Promise<SqlExecutor>
  /** Builds this worker's seeder from its context. Default: a plain ContextSeeder. */
  readonly seeder?: (context: SeedContext) => Seeder
  readonly clock?: Clock
  readonly consoleGate?: GateMode
  readonly consoleIgnore?: readonly (string | RegExp)[]
  readonly networkGate?: GateMode
  /** Third-party URLs the app may reach. First-party origins are always allowed. */
  readonly thirdPartyAllowList?: readonly RegExp[]
  /**
   * Runs on every browser context the suite opens (the default page's and each actor's), after the
   * gates are installed. Routes registered here take precedence over the network gate, so this is
   * where an app serves local stubs for third-party scripts it loads (a payment SDK, a widget).
   */
  readonly prepareContext?: (context: BrowserContext) => Promise<void>
  /** Directory of per-screen axe baselines; enables the a11y fixture. */
  readonly a11yBaselineDir?: string
  readonly visual?: VisualPolicy
  readonly api?: Omit<ApiClientOptions, 'baseUrl'>
}

const isHttpUrl = (value: string): boolean => {
  try {
    const url = new URL(value)
    return url.protocol === 'http:' || url.protocol === 'https:'
  } catch {
    return false
  }
}

/** Every problem with an env, so a misconfigured run fails once with the full list. */
export function validateSuiteEnv(env: SuiteEnv): string[] {
  const problems: string[] = []
  if (!env.name?.trim()) problems.push('name is required')
  if (!isHttpUrl(env.baseUrl)) problems.push(`baseUrl "${env.baseUrl}" is not an http(s) URL`)
  if (!isHttpUrl(env.apiBaseUrl))
    problems.push(`apiBaseUrl "${env.apiBaseUrl}" is not an http(s) URL`)
  if (!/^[a-z0-9.-]+\.[a-z]{2,}$/i.test(env.emailDomain ?? '')) {
    problems.push(`emailDomain "${env.emailDomain}" is not a domain`)
  }
  if (env.ids?.base === undefined || env.ids.base < 0n)
    problems.push('ids.base must be a non-negative bigint')
  if (!env.routing) problems.push('routing is required')
  if (!env.auth) problems.push('auth is required')
  if (!env.taxonomy || env.taxonomy.persona.length === 0)
    problems.push('taxonomy needs at least one persona')
  return problems
}

/** Reads a required environment variable, failing with the variable's name. */
export function requiredEnv(name: string, source: NodeJS.ProcessEnv = process.env): string {
  const value = source[name]
  if (value === undefined || value.trim() === '') {
    throw new Error(`Environment variable ${name} is required for the UI suite`)
  }
  return value
}

export function optionalEnv(
  name: string,
  fallback: string,
  source: NodeJS.ProcessEnv = process.env,
): string {
  const value = source[name]
  return value === undefined || value.trim() === '' ? fallback : value
}
