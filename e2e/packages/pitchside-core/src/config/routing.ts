import type { Page } from '@playwright/test'
import { pollUntil } from '../util/poll.js'

/**
 * Maps app routes to browser URLs and back. Hash vs history routing lives here and nowhere else:
 * in a hash-routed SPA a naive `page.goto('/settings')` silently loads the shell and redirects.
 */
export interface Routing {
  toUrl(route: string): string
  /** The route part of a browser URL, without query string — e.g. "/coach/home". */
  fromUrl(url: string): string
}

const trimSlash = (value: string): string => value.replace(/\/+$/, '')
const leadingSlash = (route: string): string => (route.startsWith('/') ? route : `/${route}`)
const stripQuery = (value: string): string => value.split('?')[0] ?? value

/** `https://host/app/#/route` */
export function hashRouting(baseUrl: string): Routing {
  const base = trimSlash(baseUrl)
  return {
    toUrl: (route) => `${base}/#${leadingSlash(route)}`,
    fromUrl: (url) => {
      const hash = new URL(url).hash.replace(/^#/, '')
      return leadingSlash(stripQuery(hash) || '/')
    },
  }
}

/** `https://host/app/route` */
export function historyRouting(baseUrl: string): Routing {
  const base = new URL(baseUrl)
  const prefix = trimSlash(base.pathname)
  return {
    toUrl: (route) => `${trimSlash(base.origin + prefix)}${leadingSlash(route)}`,
    fromUrl: (url) => {
      const path = new URL(url).pathname
      const relative = prefix && path.startsWith(prefix) ? path.slice(prefix.length) : path
      return leadingSlash(relative || '/')
    },
  }
}

/**
 * Fills `:param` placeholders, dropping vue-router style constraints such as `(\\d+)`.
 * Values are URI-encoded; a missing parameter throws instead of producing a bogus URL.
 */
export function interpolate(
  route: string,
  params: Record<string, string | number | bigint> = {},
): string {
  const used = new Set<string>()
  const filled = route.replace(/:([A-Za-z_][A-Za-z0-9_]*)(\([^)]*\))?/g, (_, name: string) => {
    const value = params[name]
    if (value === undefined) throw new Error(`Route ${route} needs parameter "${name}"`)
    used.add(name)
    return encodeURIComponent(String(value))
  })
  const unused = Object.keys(params).filter((key) => !used.has(key))
  if (unused.length > 0) throw new Error(`Route ${route} has no parameter(s) ${unused.join(', ')}`)
  return filled
}

/**
 * Converts a route pattern (`/players/:id(\\d+)/packs`) into a matcher for an actual route.
 * Constraints are honoured; plain params match one path segment.
 */
export function routePattern(route: string): RegExp {
  let source = ''
  let rest = route
  const param = /:([A-Za-z_][A-Za-z0-9_]*)(\(([^)]*)\))?/
  for (;;) {
    const match = param.exec(rest)
    if (!match) break
    source += rest.slice(0, match.index).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    source += `(${match[3] ?? '[^/]+'})`
    rest = rest.slice(match.index + match[0].length)
  }
  source += rest.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return new RegExp(`^${source}/?$`)
}

export type RouteMatcher = string | RegExp | ((route: string) => boolean)

const toPredicate = (matcher: RouteMatcher): ((route: string) => boolean) => {
  if (typeof matcher === 'function') return matcher
  if (matcher instanceof RegExp) return (route) => matcher.test(route)
  const pattern = routePattern(matcher)
  return (route) => pattern.test(route)
}

export interface SettledRouteOptions {
  /** How long the route must stay unchanged to count as settled. Default 300 ms. */
  stableMs?: number
  timeoutMs?: number
}

/**
 * Waits until the page's route matches AND has stopped changing. Async router guards (completeness
 * checks, id-resolving redirects) produce intermediate URLs; asserting on the first match races them.
 */
export async function waitForSettledRoute(
  page: Pick<Page, 'url'>,
  routing: Routing,
  matcher: RouteMatcher,
  options: SettledRouteOptions = {},
): Promise<string> {
  const predicate = toPredicate(matcher)
  const stableMs = options.stableMs ?? 300
  let candidate: string | undefined
  let since = 0
  return pollUntil(
    async () => {
      const route = routing.fromUrl(page.url())
      if (route !== candidate) {
        candidate = route
        since = Date.now()
        return undefined
      }
      return predicate(route) && Date.now() - since >= stableMs ? route : undefined
    },
    {
      timeoutMs: options.timeoutMs ?? 10_000,
      intervalMs: 50,
      describe: () => `route to settle on ${String(matcher)} (last seen: ${candidate ?? 'none'})`,
    },
  )
}
