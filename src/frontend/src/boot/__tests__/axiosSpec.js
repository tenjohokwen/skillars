// skillars-deferred-126 AC4 — boot/axios.js's 401 response interceptor.
//
// story-review.md Task 6: no spec in this suite has ever loaded the REAL boot/axios.js module —
// every existing spec that touches this territory (AppSpec.js, useSessionSpec.js, MainLayoutSpec.js,
// and others) works by `vi.mock('src/boot/axios', ...)`, mocking the real module away entirely. No
// boot-file spec exists for any boot file (`src/boot/__tests__/` did not exist before this file), so
// there is no existing precedent to mirror — this is a from-scratch design.
//
// The response interceptor is an inline, unexported closure registered at MODULE SCOPE (it runs once
// at import time, not inside the `defineBoot` callback) — reaching it needs either a mock HTTP
// adapter (axios-mock-adapter is NOT a current dependency) or axios's own internals. This file picks
// the latter: `api.interceptors.response.handlers[0].rejected` is axios's own public-ish internal
// array (the same shape axios's own test suite and widely-documented recipes use to reach a
// registered interceptor directly), avoiding a new dependency for one test file.
//
// `defineBoot` (from '#q-app/wrappers') is a plain identity wrapper in this project's pinned
// @quasar/app-vite version (`export const defineBoot = wrapper` where `wrapper = callback =>
// callback`) — calling the module's default export directly with a `{ router }` context object is
// exactly what Quasar's own boot sequence does, so `await boot({ router: fakeRouter })` genuinely
// exercises the real callback, not a stand-in. The `#q-app/wrappers` import itself resolves under
// Vitest via `.quasar/tsconfig.json`'s `vite-tsconfig-paths` entry (confirmed at story-creation time,
// not assumed here).
//
// `vi.resetModules()` + a dynamic `await import(...)` per test gives each test its own fresh copy of
// axios.js's module-level `bootRouter` variable — this file has no exported test-reset hook (unlike
// sessionRedirect.js's `__resetSessionRedirectGuardForTests`) because a fresh module instance is
// simpler and does not require adding a test-only export to production code for one boot flag.

import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('@rajesh896/broprint.js', () => ({
  getCurrentBrowserFingerPrint: vi.fn().mockResolvedValue('test-fingerprint'),
}))
// { spy: true } keeps sessionManager's real (pure-JS, DOM-only) implementations running — mirrors
// useSessionSpec.js's identical convention — rather than mocking away behavior this spec doesn't own.
vi.mock('src/plugins/sessionManager', { spy: true })
vi.mock('src/utils/sessionRedirect', () => ({
  pushLoginOrHardNavigate: vi.fn().mockResolvedValue(undefined),
}))

function build401(errorKey) {
  return {
    response: {
      status: 401,
      data: { errorMsg: { errorKey } },
    },
  }
}

describe('boot/axios — 401 handler routes through sessionRedirect once boot has run (deferred-126 AC4)', () => {
  let rejectedHandler
  let pushLoginOrHardNavigate
  let fakeRouter

  beforeEach(async () => {
    vi.resetModules()
    vi.clearAllMocks()

    const sessionRedirectModule = await import('src/utils/sessionRedirect')
    pushLoginOrHardNavigate = sessionRedirectModule.pushLoginOrHardNavigate

    const { api, default: boot } = await import('src/boot/axios')
    rejectedHandler = api.interceptors.response.handlers[0].rejected

    fakeRouter = { currentRoute: { value: { path: '/some-page' } } }
    await boot({ router: fakeRouter })
  })

  it('security.sessionExpired — pushLoginOrHardNavigate called with the boot-injected router and expired:true', async () => {
    await expect(rejectedHandler(build401('security.sessionExpired'))).rejects.toBeDefined()

    expect(pushLoginOrHardNavigate).toHaveBeenCalledTimes(1)
    expect(pushLoginOrHardNavigate).toHaveBeenCalledWith(fakeRouter, { expired: true })
  })

  it('security.unauthorized — expired:false, not hardcoded true (story-review.md F-6: a plain unauthorized 401 is not an expiry)', async () => {
    await expect(rejectedHandler(build401('security.unauthorized'))).rejects.toBeDefined()

    expect(pushLoginOrHardNavigate).toHaveBeenCalledTimes(1)
    expect(pushLoginOrHardNavigate).toHaveBeenCalledWith(fakeRouter, { expired: false })
  })

  it('a 401 with neither errorKey — pushLoginOrHardNavigate is not called', async () => {
    await expect(rejectedHandler(build401('some.other.key'))).rejects.toBeDefined()

    expect(pushLoginOrHardNavigate).not.toHaveBeenCalled()
  })

  it('a non-401 error — pushLoginOrHardNavigate is not called', async () => {
    await expect(
      rejectedHandler({ response: { status: 500, data: {} } }),
    ).rejects.toBeDefined()

    expect(pushLoginOrHardNavigate).not.toHaveBeenCalled()
  })
})

describe('boot/axios — 401 before boot has run (defensive fallback, deferred-126 AC4)', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.clearAllMocks()
  })

  it('falls back to a hash-aware hard navigation instead of silently no-op-ing', async () => {
    const { api } = await import('src/boot/axios')
    const rejectedHandler = api.interceptors.response.handlers[0].rejected
    // boot() deliberately NOT invoked here — the module-level router reference stays unset, the
    // theoretical edge case this fallback guards (an interceptor firing before Quasar's boot sequence
    // has run, e.g. from setBrowserFingerprint() itself failing with a 401).

    await expect(rejectedHandler(build401('security.sessionExpired'))).rejects.toBeDefined()

    expect(window.location.href).toContain('/#/login')
    expect(window.location.href).toContain('expired=true')
  })
})
