// skillars-deferred-104 AC8 — reference "pure-logic" spec (no mount).
//
// Purpose: give follow-up stories a worked template for the non-component style of frontend
// unit test — faked `document.cookie` + faked clock, driving the module through its EXPORTED
// surface only. `sessionManager.js` is NOT modified by this story.
//
// The logic under test lives in three PRIVATE functions (`readSessionExpiryFromCookie`,
// `computeTimeUntilExpiry`, `tick`). They are exercised indirectly:
//   - `refreshExpiryState()` (exported) → `tick()` → `computeTimeUntilExpiry()` →
//     `readSessionExpiryFromCookie()`  — the entry point for cases 1 and 2.
//   - `startSessionMonitoring()` (exported) runs `tick()` synchronously before arming its 30 s
//     interval, so there is no "before the first tick" window after it returns — case 3 targets
//     the pre-any-call module defaults instead.
// Observable state = the exported reactive refs.

import { describe, it, expect, vi, afterEach } from 'vitest'
// Cases 1 & 2 share this static import of the module. Case 3 wants a genuinely fresh instance,
// so it re-imports dynamically after `vi.resetModules()` and reads everything off `sm.*`
// (including `secondsRemaining` / `minutesRemaining` / `startSessionMonitoring`).
import {
  refreshExpiryState,
  recordActivity,
  cleanup,
  showWarning,
  timeUntilExpiry,
} from '../sessionManager.js'

// Module-private constants, reproduced as local literals (they are not exported).
const WARNING_THRESHOLD = 5 * 60 * 1000
const LEGACY_SESSION_TTL = 15 * 60 * 1000
const MIN_PLAUSIBLE_EPOCH_MS = 1e12 // any `rint` below this is not an absolute epoch-ms timestamp
const RINT_COOKIE_NAME = 'rint'
const RINT_SEEN_STORAGE_KEY = 'skillars.session.rintSeen'

function setCookie(name, value) {
  document.cookie = `${name}=${value}`
}

function clearCookie(name) {
  document.cookie = `${name}=; expires=Thu, 01 Jan 1970 00:00:00 GMT`
}

afterEach(() => {
  // Isolation model: `sessionManager.js` holds module-level `ref`s and timer ids that persist
  // across tests in the same module instance. Cases 1 & 2 share the static import above and rely
  // on this `cleanup()` (stops every timer, resets every ref) to hand the next test a clean slate.
  // Case 3 needs more than a reset — a genuinely fresh module — so it additionally calls
  // `vi.resetModules()` and re-`import()`s; nothing extra is needed here for it.
  cleanup()
  vi.useRealTimers()
  vi.restoreAllMocks()
  clearCookie(RINT_COOKIE_NAME)
  // A valid `rint` calls markRintSeen() → sessionStorage; it gates the deferred-90 "torn down"
  // branch, so it must not leak between tests.
  try {
    sessionStorage.removeItem(RINT_SEEN_STORAGE_KEY)
  } catch {
    // sessionStorage unavailable — nothing to clear.
  }
})

describe('sessionManager (pure logic, via exported surface)', () => {
  it('case 1: rejects an implausible stale `rint` and falls back to the legacy estimate; honours a plausible absolute `rint`', () => {
    // --- stale / garbage rint (pre-1.7b fixed-delta value, < MIN_PLAUSIBLE_EPOCH_MS) ---
    expect(12345).toBeLessThan(MIN_PLAUSIBLE_EPOCH_MS)
    recordActivity() // pin lastActivityTime = now, so the legacy estimate ≈ LEGACY_SESSION_TTL
    setCookie(RINT_COOKIE_NAME, '12345')

    refreshExpiryState()

    // The implausible value was rejected, NOT read as an absolute epoch (which would land in
    // 1970 → a large negative remaining) and NOT treated as 0.
    expect(timeUntilExpiry.value).toBeGreaterThan(WARNING_THRESHOLD)
    expect(timeUntilExpiry.value).toBeLessThanOrEqual(LEGACY_SESSION_TTL)
    expect(timeUntilExpiry.value).toBeGreaterThan(LEGACY_SESSION_TTL - 60_000)
    expect(showWarning.value).toBe(false)

    // --- plausible absolute rint (10 minutes out) IS honoured ---
    const tenMinutes = 10 * 60 * 1000
    setCookie(RINT_COOKIE_NAME, String(Date.now() + tenMinutes))

    refreshExpiryState()

    expect(timeUntilExpiry.value).toBeGreaterThan(tenMinutes - 60_000)
    expect(timeUntilExpiry.value).toBeLessThanOrEqual(tenMinutes + 1_000)
  })

  it('case 2: the 1 s countdown interval is cleared when the session leaves the warning band (no leak)', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-01-01T00:00:00.000Z'))

    // rint 4 minutes out — inside the 5-minute WARNING_THRESHOLD.
    setCookie(RINT_COOKIE_NAME, String(Date.now() + 4 * 60 * 1000))
    refreshExpiryState()

    expect(showWarning.value).toBe(true)
    // startCountdown() armed exactly one interval (no 30 s monitor running in this test).
    expect(vi.getTimerCount()).toBe(1)

    // The armed interval actually ticks the countdown down.
    const before = timeUntilExpiry.value
    vi.advanceTimersByTime(1000)
    expect(timeUntilExpiry.value).toBeLessThan(before)
    expect(vi.getTimerCount()).toBe(1)

    // rint now 10 minutes out — outside the warning band.
    setCookie(RINT_COOKIE_NAME, String(Date.now() + 10 * 60 * 1000))
    refreshExpiryState()

    expect(showWarning.value).toBe(false)
    // Pre-fix, this interval was only ever cleared by cleanup() / refreshSession(), so it leaked
    // for the rest of the session. It must be gone now.
    expect(vi.getTimerCount()).toBe(0)

    // And advancing time no longer re-runs tick(): the countdown value holds.
    const held = timeUntilExpiry.value
    vi.advanceTimersByTime(5000)
    expect(timeUntilExpiry.value).toBe(held)
  })

  it('case 3: a freshly loaded module has safe defaults and does not force-expire a no-cookie session', async () => {
    vi.resetModules()
    const sm = await import('src/plugins/sessionManager')

    // Before any function is called: the module-init defaults must be sane.
    expect(sm.showWarning.value).toBe(false)
    expect(sm.timeUntilExpiry.value).toBe(LEGACY_SESSION_TTL)
    expect(sm.secondsRemaining.value).toBe(900)
    expect(Number.isNaN(sm.secondsRemaining.value)).toBe(false)
    expect(sm.minutesRemaining.value).toBe(15)
    expect(Number.isNaN(sm.minutesRemaining.value)).toBe(false)

    // With no `rint` cookie and a fresh lastActivityTime, the first synchronous tick() must NOT
    // dispatch 'session:expired' — it returns false and the 30 s interval is armed.
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-01-01T00:00:00.000Z'))
    const dispatchSpy = vi.spyOn(window, 'dispatchEvent')

    sm.startSessionMonitoring()

    const expiredDispatched = dispatchSpy.mock.calls.some(
      ([event]) => event && event.type === 'session:expired',
    )
    expect(expiredDispatched).toBe(false)
    expect(sm.showWarning.value).toBe(false)
    expect(vi.getTimerCount()).toBeGreaterThanOrEqual(1) // the 30 s monitor interval

    sm.stopSessionMonitoring()
  })
})
