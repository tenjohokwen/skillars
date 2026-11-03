import { test } from '@playwright/test'

/** Used when no Playwright timeout is configured, or outside a running test (unit tests, scripts). */
export const DEFAULT_SETTLE_TIMEOUT_MS = 10_000

export interface PlaywrightTimeouts {
  navigationTimeout?: number
  actionTimeout?: number
}

/**
 * The budget for a route to settle, taken from Playwright's own timeouts: settling is the tail of a
 * navigation, so `navigationTimeout` first, then `actionTimeout`, then the default. A suite that
 * runs on slow machines raises `navigationTimeout` once in its config instead of every call site
 * passing `timeoutMs`. Zero means "no limit" in Playwright and is skipped here: an unbounded wait
 * would end in a bare test timeout instead of naming the route it was waiting for.
 */
export function resolveSettleTimeout(timeouts?: PlaywrightTimeouts): number {
  for (const candidate of [timeouts?.navigationTimeout, timeouts?.actionTimeout]) {
    if (typeof candidate === 'number' && candidate > 0) return candidate
  }
  return DEFAULT_SETTLE_TIMEOUT_MS
}

// The timeouts of the test running in this worker, as resolved fixtures — so `test.use()` overrides
// count, which `testInfo.project.use` (the config file's values only) would miss. A worker runs one
// test at a time, so a module-level slot is enough; the Pitchside fixtures set and clear it.
let active: PlaywrightTimeouts | undefined

/** @internal Called by the Pitchside test fixtures around each test. */
export function activateTimeouts(timeouts: PlaywrightTimeouts | undefined): void {
  active = timeouts
}

/**
 * {@link resolveSettleTimeout} for the test that is currently running: its resolved fixtures when it
 * uses the Pitchside `test`, else its project's configured `use`, else the default.
 */
export function settleTimeoutMs(): number {
  if (active) return resolveSettleTimeout(active)
  try {
    return resolveSettleTimeout(test.info().project.use)
  } catch {
    return DEFAULT_SETTLE_TIMEOUT_MS // not inside a Playwright test
  }
}
