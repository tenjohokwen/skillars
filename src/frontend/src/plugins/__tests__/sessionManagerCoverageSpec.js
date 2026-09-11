// skillars-deferred-108 AC5 — sessionManager.js remaining coverage.
//
// Closes the "remaining sessionManager.js coverage" clause of deferred-work.md:1121
// (refreshSession success/failure, the deferred-90 "torn down" branch, multi-tab extension).
// The startSessionMonitoring early-return case here is a CHARACTERIZATION test — it pins the
// deliberate behaviour but does NOT resolve the decided :1125 / :1128 re-arm concern.
//
// Sits alongside the reference sessionManagerSpec.js (kept pristine per the story's "extend
// alongside them" rule). Same faked-cookie + faked-clock isolation model; each test gets a fresh
// module via vi.resetModules() + dynamic import.
//
// Mutation checks (run both ways, see Dev Agent Record):
//   - delete `if (tick()) return;` in startSessionMonitoring() → the early-return characterization
//     test sees a second 'session:expired' 30 s later and fails.
//   - remove `refreshFailed.value = true` from refreshSession()'s catch → the failure test fails.

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

vi.mock('src/utils/sessionCookies', () => ({ hasUserSession: vi.fn(() => true) }))
vi.mock('src/api/session.api', () => ({ sessionApi: { refresh: vi.fn() } }))

// skillars-deferred-108 code review (P12): DERIVED, never copied. sessionManager.js does not
// export LEGACY_SESSION_TTL, but timeUntilExpiry initialises to exactly it, so a fresh module
// hands us the real value. A hard-coded `15 * 60 * 1000` would silently stop testing anything the
// moment the production TTL changed -- the multi-tab "no clamp" assertion below would still pass
// against a stale, smaller bound.
let LEGACY_SESSION_TTL
const RINT_COOKIE_NAME = 'rint'
const RINT_SEEN_STORAGE_KEY = 'skillars.session.rintSeen'
const T0 = new Date('2026-01-01T00:00:00.000Z')

function setCookie(name, value) {
  document.cookie = `${name}=${value}`
}
function clearCookie(name) {
  document.cookie = `${name}=; expires=Thu, 01 Jan 1970 00:00:00 GMT`
}
function expiredDispatched(spy) {
  return spy.mock.calls.some(([event]) => event && event.type === 'session:expired')
}

let sm
let hasUserSession
let sessionApi

beforeEach(async () => {
  vi.resetModules()
  vi.useFakeTimers()
  vi.setSystemTime(T0)
  ;({ hasUserSession } = await import('src/utils/sessionCookies'))
  ;({ sessionApi } = await import('src/api/session.api'))
  hasUserSession.mockReturnValue(true)
  sm = await import('src/plugins/sessionManager')
  // Read before any test touches the module: this is the production LEGACY_SESSION_TTL.
  LEGACY_SESSION_TTL = sm.timeUntilExpiry.value
})

afterEach(() => {
  sm.cleanup()
  vi.useRealTimers()
  vi.restoreAllMocks()
  clearCookie(RINT_COOKIE_NAME)
  try {
    sessionStorage.removeItem(RINT_SEEN_STORAGE_KEY)
  } catch {
    // sessionStorage unavailable — nothing to clear.
  }
})

describe('sessionManager — refreshSession (deferred-108 AC5)', () => {
  it('success: leaves the warning band, clears the 1 s countdown, no leak, refreshFailed stays false', async () => {
    setCookie(RINT_COOKIE_NAME, String(Date.now() + 4 * 60 * 1000)) // inside the 5-min warning band
    sm.refreshExpiryState()
    expect(sm.showWarning.value).toBe(true)
    expect(vi.getTimerCount()).toBe(1) // countdown armed

    sessionApi.refresh.mockImplementation(async () => {
      setCookie(RINT_COOKIE_NAME, String(Date.now() + 30 * 60 * 1000)) // far-future rint
    })

    const p = sm.refreshSession()
    expect(sm.isRefreshing.value).toBe(true)
    await p

    expect(sm.showWarning.value).toBe(false)
    expect(sm.refreshFailed.value).toBe(false)
    expect(sm.isRefreshing.value).toBe(false)
    expect(vi.getTimerCount()).toBe(0) // countdown cleared via tick()'s warning-edge handling

    const held = sm.timeUntilExpiry.value
    vi.advanceTimersByTime(5000)
    expect(sm.timeUntilExpiry.value).toBe(held) // nothing still ticking
  })

  it('failure: sets refreshFailed, clears isRefreshing, logs, and does not silently clear the countdown', async () => {
    setCookie(RINT_COOKIE_NAME, String(Date.now() + 4 * 60 * 1000))
    sm.refreshExpiryState()
    expect(vi.getTimerCount()).toBe(1)

    const errSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    sessionApi.refresh.mockRejectedValue(new Error('refresh 503'))

    await sm.refreshSession()

    expect(sm.refreshFailed.value).toBe(true)
    expect(sm.isRefreshing.value).toBe(false)
    expect(errSpy).toHaveBeenCalled()
    expect(vi.getTimerCount()).toBe(1) // countdown still running — failure must not clear it
  })
})

describe('sessionManager — deferred-90 "torn down" branch (deferred-108 AC5)', () => {
  async function armThenTearDown() {
    setCookie(RINT_COOKIE_NAME, String(Date.now() + 10 * 60 * 1000))
    sm.startSessionMonitoring() // arms the 30 s interval, markRintSeen() runs
    expect(sessionStorage.getItem(RINT_SEEN_STORAGE_KEY)).toBe('1')
    // P12/P15: exact, not >=1. A loose bound passes with 1, 5 or 50 timers -- i.e. it would sail
    // through the interval-leak class the sibling reference spec exists to catch.
    // rint is 10 min out and WARNING_THRESHOLD is 5 min, so the 1 s countdown is NOT armed here:
    // exactly one timer, the 30 s monitor interval.
    expect(vi.getTimerCount()).toBe(1)

    clearCookie(RINT_COOKIE_NAME)
    hasUserSession.mockReturnValue(false)
    const dispatchSpy = vi.spyOn(window, 'dispatchEvent')
    sm.startSessionMonitoring() // :209 clears the timer but leaves checkIntervalId truthy
    return dispatchSpy
  }

  it('re-arming with rint + user both gone dispatches session:expired and does not re-arm', async () => {
    const dispatchSpy = await armThenTearDown()
    expect(expiredDispatched(dispatchSpy)).toBe(true)
    expect(vi.getTimerCount()).toBe(0) // cleanup() ran, nothing re-armed
  })

  it('characterization: no timer armed → the clock advancing 30 s produces no second session:expired', async () => {
    const dispatchSpy = await armThenTearDown()
    const countAfterTeardown = dispatchSpy.mock.calls.filter(
      ([e]) => e && e.type === 'session:expired',
    ).length
    expect(countAfterTeardown).toBe(1)

    vi.advanceTimersByTime(30_000)

    const countLater = dispatchSpy.mock.calls.filter(
      ([e]) => e && e.type === 'session:expired',
    ).length
    expect(countLater).toBe(1) // still just the one
  })

  it('negative: monitoring never armed → no dispatch (first tick sees checkIntervalId null)', async () => {
    clearCookie(RINT_COOKIE_NAME)
    hasUserSession.mockReturnValue(false)
    const dispatchSpy = vi.spyOn(window, 'dispatchEvent')

    sm.startSessionMonitoring()

    expect(expiredDispatched(dispatchSpy)).toBe(false)
    expect(vi.getTimerCount()).toBe(1) // legacy-estimate path armed the 30 s interval only
  })

  it('negative: rint cleared but the user cookie is still present → no dispatch', async () => {
    setCookie(RINT_COOKIE_NAME, String(Date.now() + 10 * 60 * 1000))
    sm.startSessionMonitoring()
    clearCookie(RINT_COOKIE_NAME)
    hasUserSession.mockReturnValue(true) // user cookie still there
    const dispatchSpy = vi.spyOn(window, 'dispatchEvent')

    sm.startSessionMonitoring()

    expect(expiredDispatched(dispatchSpy)).toBe(false)
  })

  it('negative: rint cleared and rintSeen never set → legacy estimate, no dispatch', async () => {
    // No rint was ever set, so markRintSeen() never ran.
    clearCookie(RINT_COOKIE_NAME)
    hasUserSession.mockReturnValue(false)
    sm.startSessionMonitoring() // arms interval, checkIntervalId now truthy
    const dispatchSpy = vi.spyOn(window, 'dispatchEvent')

    sm.startSessionMonitoring()

    expect(expiredDispatched(dispatchSpy)).toBe(false)
  })
})

// ---------------------------------------------------------------------------
// skillars-deferred-109 AC2.1 — the clock-skew cross-check has regression protection.
// computeTimeUntilExpiry()'s `if (remaining <= 0 && localEstimate > 0) return localEstimate`
// (sessionManager.js:140) is the whole defence between a fast client clock and an unrecoverable
// logout loop. Every deferred-108 fixture uses `Date.now() + N`, so the line was never exercised.
// Spec-only — no production change here.
// ---------------------------------------------------------------------------
describe('sessionManager — clock-skew cross-check (deferred-109 AC2.1)', () => {
  it('a past-due rint with a still-positive local estimate reports the local estimate, not expiry', () => {
    // Client clock is "fast": the server-issued rint reads as a few seconds in the past.
    setCookie(RINT_COOKIE_NAME, String(Date.now() - 8 * 1000))
    sm.startSessionMonitoring() // recordActivity() → localEstimate ≈ LEGACY_SESSION_TTL, positive
    const dispatchSpy = vi.spyOn(window, 'dispatchEvent')

    sm.refreshExpiryState()

    expect(sm.timeUntilExpiry.value).toBeGreaterThan(0)
    expect(sm.timeUntilExpiry.value).toBeLessThanOrEqual(LEGACY_SESSION_TTL)
    expect(expiredDispatched(dispatchSpy)).toBe(false)
    // Mutation: delete `if (remaining <= 0 && localEstimate > 0) return localEstimate` at
    // sessionManager.js:140 → computeTimeUntilExpiry returns the negative `remaining`, tick()
    // dispatches session:expired and runs cleanup() → both assertions go RED.
  })

  it('boundary: rint exactly WARNING_THRESHOLD out → showWarning is true (<=, not <)', () => {
    const { warningThresholdSeconds } = sm
    const thresholdMs = warningThresholdSeconds.value * 1000
    setCookie(RINT_COOKIE_NAME, String(Date.now() + thresholdMs))
    sm.startSessionMonitoring()

    sm.refreshExpiryState()

    expect(sm.timeUntilExpiry.value).toBeLessThanOrEqual(thresholdMs)
    expect(sm.showWarning.value).toBe(true)
  })

  it('boundary: rint exactly now AND no local cushion → session expires', () => {
    sm.startSessionMonitoring() // records activity at T0
    vi.setSystemTime(new Date(T0.getTime() + LEGACY_SESSION_TTL)) // localEstimate now == 0
    setCookie(RINT_COOKIE_NAME, String(Date.now())) // remaining == 0
    const dispatchSpy = vi.spyOn(window, 'dispatchEvent')

    sm.refreshExpiryState()

    // remaining <= 0 but localEstimate is NOT > 0, so the skew guard does not fire: real expiry.
    expect(expiredDispatched(dispatchSpy)).toBe(true)
  })
})

// ---------------------------------------------------------------------------
// skillars-deferred-109 AC2.2 — a refresh that returns 200 without advancing 'rint' is not
// treated as success (sessionManager.js refreshSession success path).
// ---------------------------------------------------------------------------
describe('sessionManager — ineffective refresh (deferred-109 AC2.2)', () => {
  it('200 without a fresh rint, on the absolute-rint contract → refreshFailed is set', async () => {
    // A read of a valid rint sets the rintSeen marker (markRintSeen), so this tab is "on contract".
    setCookie(RINT_COOKIE_NAME, String(Date.now() + 4 * 60 * 1000)) // inside the warning band
    sm.refreshExpiryState()
    expect(sessionStorage.getItem(RINT_SEEN_STORAGE_KEY)).toBe('1')

    // The refresh resolves but does NOT re-issue rint (proxy stripped Set-Cookie / no advance).
    sessionApi.refresh.mockResolvedValue(undefined)

    await sm.refreshSession()

    expect(sm.refreshFailed.value).toBe(true)
    expect(sm.isRefreshing.value).toBe(false)
    // Mutation: remove the whole post-refresh advance-check block → refreshFailed stays false → RED.
  })

  it('legacy path: 200, no rint ever seen this tab → refreshFailed stays false', async () => {
    // No rint has ever been set in this fresh module, so hasSeenRintThisTab() is false.
    expect(sessionStorage.getItem(RINT_SEEN_STORAGE_KEY)).toBeNull()
    sessionApi.refresh.mockResolvedValue(undefined)

    await sm.refreshSession()

    expect(sm.refreshFailed.value).toBe(false)
    // Mutation: drop the `hasSeenRintThisTab()` condition from the guard → a legacy refresh with no
    // rint is wrongly flagged (advanced === false) and refreshFailed goes true → RED.
  })

  // skillars-deferred-109 code review: the AC2.2 block shipped with no coverage of a refresh that
  // genuinely WORKS, so an inverted or mis-thresholded predicate was invisible here.
  it('200 that advances rint → refreshFailed stays false', async () => {
    setCookie(RINT_COOKIE_NAME, String(Date.now() + 4 * 60 * 1000))
    sm.refreshExpiryState()
    sessionApi.refresh.mockImplementation(async () => {
      setCookie(RINT_COOKIE_NAME, String(Date.now() + 15 * 60 * 1000))
    })

    await sm.refreshSession()

    expect(sm.refreshFailed.value).toBe(false)
    // Mutation: invert the comparison to `expiresAt >= expiryBeforeRefresh` → a real extension is
    // flagged as failed → RED.
  })

  // The regression this replaced: the original predicate was
  // `expiresAt - Date.now() > WARNING_THRESHOLD`, i.e. a client instant subtracted from a server
  // instant and compared to a client-side constant — a silent re-coupling to JWT_TTL that
  // SecurityConstants.java:68-79 forbids. Here the deadline advances by a real 2 minutes but lands
  // INSIDE the 5-minute warning band, which is exactly what a >10-min-fast client clock (or a
  // backend JWT_TTL <= WARNING_THRESHOLD) produces. It is a successful extension and must not be
  // flagged.
  it('an advance that lands inside the warning band is still an advance', async () => {
    setCookie(RINT_COOKIE_NAME, String(Date.now() + 2 * 60 * 1000))
    sm.refreshExpiryState()
    sessionApi.refresh.mockImplementation(async () => {
      setCookie(RINT_COOKIE_NAME, String(Date.now() + 4 * 60 * 1000))
    })

    await sm.refreshSession()

    expect(sm.refreshFailed.value).toBe(false)
    // Mutation: restore `expiresAt - Date.now() > WARNING_THRESHOLD` → 4 min is not > 5 min, the
    // successful extension is reported as a failure and the red banner shows → RED.
  })
})

describe('sessionManager — multi-tab extension (deferred-108 AC5)', () => {
  it('a sibling tab advancing rint past LEGACY_SESSION_TTL extends this tab with no clamp', () => {
    setCookie(RINT_COOKIE_NAME, String(Date.now() + 2 * 60 * 1000)) // 2 min → warning
    sm.startSessionMonitoring()
    expect(sm.showWarning.value).toBe(true)

    setCookie(RINT_COOKIE_NAME, String(Date.now() + 30 * 60 * 1000)) // 30 min > 15-min legacy TTL
    sm.refreshExpiryState()

    expect(sm.showWarning.value).toBe(false)
    expect(sm.timeUntilExpiry.value).toBeGreaterThan(LEGACY_SESSION_TTL) // no upper bound
    expect(sm.timeUntilExpiry.value).toBeLessThanOrEqual(30 * 60 * 1000 + 1000)
  })
})
