import type { Page, Route } from '@playwright/test'

type UrlMatcher = string | RegExp | ((url: URL) => boolean)

/** Serve a JSON body for every matching request. */
export async function stubJson(
  page: Pick<Page, 'route'>,
  url: UrlMatcher,
  body: unknown,
  status = 200,
): Promise<void> {
  await page.route(url, (route) =>
    route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) }),
  )
}

export interface FailOptions {
  status?: number
  body?: unknown
  /** How many matching requests to fail before letting the rest through. Default 1. */
  times?: number
}

/**
 * Fail the first N matching requests, then pass the rest through untouched — the shape of a
 * transient outage, for "error state with retry" and "degrades one tile" scenarios. Resolves to a
 * counter of how many requests were failed.
 */
export async function failFirst(
  page: Pick<Page, 'route'>,
  url: UrlMatcher,
  options: FailOptions = {},
): Promise<{ failed: () => number }> {
  const limit = options.times ?? 1
  let failed = 0
  await page.route(url, async (route: Route) => {
    if (failed < limit) {
      failed += 1
      await route.fulfill({
        status: options.status ?? 500,
        contentType: 'application/json',
        body: JSON.stringify(options.body ?? { error: 'injected failure' }),
      })
      return
    }
    await route.fallback()
  })
  return { failed: () => failed }
}

/** Hold matching requests until `release()` is called — for asserting a pending/loading state. */
export async function holdRequests(
  page: Pick<Page, 'route'>,
  url: UrlMatcher,
): Promise<{ release: () => void; held: () => number }> {
  let release!: () => void
  const gate = new Promise<void>((resolve) => {
    release = resolve
  })
  let held = 0
  await page.route(url, async (route: Route) => {
    held += 1
    await gate
    await route.fallback()
  })
  return { release, held: () => held }
}
