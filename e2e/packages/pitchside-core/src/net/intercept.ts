import type { Route } from '@playwright/test'
import { GateViolationError, type GateMode } from '../gates/GateMode.js'

export interface BlockedRequest {
  url: string
  method: string
  resourceType: string
}

export interface NetworkGateOptions {
  mode: GateMode
  /** Origins the app legitimately talks to (its own base and API URLs). */
  firstPartyOrigins: readonly string[]
  /** Extra allowed URLs, e.g. a font CDN. Everything else is aborted and recorded. */
  allow?: readonly RegExp[]
}

const ALWAYS_ALLOWED = /^(data:|blob:|about:|chrome-extension:)/i

export function toOrigin(url: string): string {
  return new URL(url).origin
}

/** Whether a request may leave the browser under the given allow-list. */
export function isAllowed(
  url: string,
  options: Pick<NetworkGateOptions, 'firstPartyOrigins' | 'allow'>,
): boolean {
  if (ALWAYS_ALLOWED.test(url)) return true
  let origin: string
  try {
    origin = toOrigin(url)
  } catch {
    return false
  }
  if (options.firstPartyOrigins.some((allowed) => toOrigin(allowed) === origin)) return true
  return (options.allow ?? []).some((pattern) => pattern.test(url))
}

/**
 * Aborts and records every request to a host outside the allow-list. This is how a test finds out
 * a page is reaching a payment or analytics host on a path nobody expected. Installed as a context
 * route, so a test's own `page.route` stubs (which take precedence) still serve stubbed hosts.
 */
export class NetworkGate {
  private readonly blocked: BlockedRequest[] = []

  constructor(private readonly options: NetworkGateOptions) {}

  get mode(): GateMode {
    return this.options.mode
  }

  /** Route registration as both BrowserContext and Page expose it (structural, so fakes fit). */
  async install(context: {
    route(url: string, handler: (route: Route) => Promise<void>): Promise<unknown>
  }): Promise<void> {
    if (this.options.mode === 'off') return
    await context.route('**/*', (route) => this.handle(route))
  }

  /** Exposed for unit tests; Playwright calls this through the route installed above. */
  async handle(route: Pick<Route, 'request' | 'abort' | 'fallback'>): Promise<void> {
    const request = route.request()
    if (isAllowed(request.url(), this.options)) {
      await route.fallback()
      return
    }
    this.blocked.push({
      url: request.url(),
      method: request.method(),
      resourceType: request.resourceType(),
    })
    await route.abort('blockedbyclient')
  }

  violations(): readonly BlockedRequest[] {
    return this.blocked
  }

  assertClean(): void {
    if (this.options.mode !== 'strict' || this.blocked.length === 0) return
    throw new GateViolationError(
      'Network',
      this.blocked.map((b) => `${b.method} ${b.url} (${b.resourceType}) is outside the allow-list`),
    )
  }
}
