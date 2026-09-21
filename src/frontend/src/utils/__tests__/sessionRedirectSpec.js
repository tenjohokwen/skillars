// skillars-deferred-125 AC3 — sessionRedirect.js's pushLoginOrHardNavigate.
//
// story-review.md correction (2026-09-21): Vue Router 4 (installed 4.6.4, verified directly against
// node_modules/vue-router/dist/vue-router.mjs) does NOT reject router.push() for a guard-aborted,
// duplicate, or superseded navigation — it RESOLVES with a NavigationFailure object. Only a guard
// that itself throws an arbitrary (non-NavigationFailure) error causes a rejection. This is not new
// test infrastructure: MainLayoutSpec.js/useSessionSpec.js/BookingRequestPageSpec.js already mount
// against a real createRouter/createMemoryHistory router (test/vitest/setup-file.js documents the
// Pinia-opt-in convention this file does not need, since pushLoginOrHardNavigate takes no Pinia
// dependency). Real navigation guards are used here (not a hand-mocked router.push) so the resolved
// NavigationFailure case is exercised as vue-router itself actually produces it, not as a shape this
// test merely assumes.
//
// Environment is happy-dom (vitest.config.mjs), not jsdom — window.location is a real, writable
// Location-shaped object here (unlike jsdom, which throws "Not implemented: navigation" on an href
// assignment), so the hard-nav fallback is asserted directly off window.location's own state rather
// than needing to stub it out.
//
// /bmad-code-review fixes (2026-09-21):
//   - The redirect query param is now built from the ROUTER's current route (router.currentRoute.
//     value.fullPath), not window.location — the two can diverge (this app runs hash-mode in
//     production, where window.location.pathname never reflects the SPA route at all; in these
//     tests, createMemoryHistory doesn't sync to window.location either). Tests below push into the
//     router itself, not window.history.pushState, to set "the current route".
//   - isNavigationFailure is now filtered to aborted|cancelled — NAVIGATION_DUPLICATED (the push
//     target IS the current route) no longer forces a hard-nav fallback. Not separately unit-tested
//     here: the only route this helper ever pushes to is /login, so a genuine NAVIGATION_DUPLICATED
//     is reachable only when already on /login — which the early "already on /login" guard below
//     intercepts before router.push is even called. The type filter is defense in depth for that
//     guard being removed or bypassed, not a reachable path through this function today.
//   - pushLoginOrHardNavigate takes an { expired } option (default false) rather than always setting
//     expired:'true' — a deliberate logout must not show the "Your session has expired" banner.
//   - Guards against re-pushing when already on /login (self-referential redirect nesting).
//
// Mutation check (run both ways, see Dev Agent Record): remove the `isNavigationFailure(failure, ...)`
// branch from pushLoginOrHardNavigate → the "guard aborts" test below fails (window.location never
// changes because the promise resolved rather than rejected, so only the catch path would have fired).

import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createRouter, createMemoryHistory } from 'vue-router'
import {
  pushLoginOrHardNavigate,
  __resetSessionRedirectGuardForTests,
} from 'src/utils/sessionRedirect'

const STUB = { template: '<div />' }

function buildRouter(beforeEachGuard) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: STUB },
      { path: '/blocked', component: STUB },
      { path: '/login', component: STUB },
    ],
  })
  if (beforeEachGuard) {
    router.beforeEach(beforeEachGuard)
  }
  return router
}

describe('sessionRedirect — pushLoginOrHardNavigate (deferred-125 AC3)', () => {
  beforeEach(() => {
    __resetSessionRedirectGuardForTests()
  })

  it('push resolves with a NavigationFailure (guard returns false) — hard-nav fallback fires with the correctly encoded URL', async () => {
    // NAVIGATION_ABORTED: a guard returning false RESOLVES router.push's promise with a
    // NavigationFailure object. This is the dominant real-world case and must not be skipped.
    const router = buildRouter((to) => {
      if (to.path === '/login') {
        return false
      }
    })
    await router.push({ path: '/blocked', query: { a: '1', b: '2' } })

    await pushLoginOrHardNavigate(router, { expired: true })

    expect(window.location.pathname).toBe('/login')
    // The href is built via router.resolve(), so encoding goes through vue-router's own
    // stringifyQuery/encodeQueryValue rather than a hand-rolled encodeURIComponent — it escapes '&'
    // (the query-delimiter character) but leaves '/'/'?'/'=' inside the value alone, since only '&'
    // is structurally significant to parsing and the SAME encoder decodes it back on the other end.
    // What must not happen is the '&' inside the current route's own query string ('a=1&b=2')
    // leaking through unescaped and splitting into sibling top-level params.
    expect(window.location.search).toBe('?redirect=/blocked?a=1%26b=2&expired=true')
    const params = new URLSearchParams(window.location.search)
    expect(params.get('redirect')).toBe('/blocked?a=1&b=2')
    expect(params.get('expired')).toBe('true')
  })

  it('push rejects (a guard throws) — hard-nav fallback fires', async () => {
    const router = buildRouter((to) => {
      if (to.path === '/login') {
        throw new Error('guard exploded')
      }
    })
    await router.push('/blocked')

    await pushLoginOrHardNavigate(router, { expired: true })

    expect(window.location.pathname).toBe('/login')
    expect(window.location.search).toContain('expired=true')
  })

  it('push resolves successfully — fallback does not fire, no double-navigation', async () => {
    const router = buildRouter() // no guard: /login is always reachable
    await router.push({ path: '/blocked', query: { a: '1', b: '2' } })
    const hrefBeforeCall = window.location.href

    await pushLoginOrHardNavigate(router, { expired: true })

    // The SPA route landed via the router, not a hard navigation — window.location itself (a
    // separate mechanism from the in-memory router history) must be untouched.
    expect(window.location.href).toBe(hrefBeforeCall)
  })

  it('deliberate logout (expired not passed) does not set expired=true', async () => {
    const router = buildRouter((to) => {
      if (to.path === '/login') {
        return false
      }
    })
    await router.push('/blocked')

    await pushLoginOrHardNavigate(router)

    expect(window.location.pathname).toBe('/login')
    expect(window.location.search).not.toContain('expired')
  })

  it('already on /login — no-op, does not nest a self-referential redirect param', async () => {
    const router = buildRouter()
    await router.push({ path: '/login', query: { redirect: '/blocked', expired: 'true' } })
    const hrefBeforeCall = window.location.href

    await pushLoginOrHardNavigate(router, { expired: true })

    expect(window.location.href).toBe(hrefBeforeCall)
  })

  it('re-entrancy guard: a second failing call in quick succession does not reassign window.location again', async () => {
    const router = buildRouter((to) => {
      if (to.path === '/login') {
        return false
      }
    })
    await router.push('/blocked')

    await pushLoginOrHardNavigate(router, { expired: true })
    const hrefAfterFirstCall = window.location.href

    // A second, distinct navigation attempt in the same tab — e.g. a concurrent in-flight request's
    // response also dispatching session:expired — must not reassign window.location a second time
    // while the page may already be unloading from the first assignment.
    await pushLoginOrHardNavigate(router, { expired: true })

    expect(window.location.href).toBe(hrefAfterFirstCall)
  })

  // skillars-deferred-126 AC4 Task 3 (story-review.md F-3): a call arriving while a previous call is
  // still in flight — before that call's own router.push has resolved — must await and return THAT
  // SAME in-flight promise rather than issuing a second router.push. Added BEFORE wiring axios.js's
  // own new call site, per this AC's own task ordering, so the guard is proven in isolation first.
  // The guard is async (awaits a controllable gate) specifically so a second call can genuinely land
  // while the first's router.push is still pending — a synchronous guard would settle before a second
  // call could ever be issued, since nothing here yields to the event loop.
  it('a second call arrives before the first settles — awaits the same in-flight promise instead of issuing a second router.push', async () => {
    let releaseGuard
    const guardGate = new Promise((resolve) => {
      releaseGuard = resolve
    })
    const router = buildRouter(async (to) => {
      if (to.path === '/login') {
        await guardGate
        return false
      }
    })
    await router.push('/blocked')
    const pushSpy = vi.spyOn(router, 'push')

    const firstCall = pushLoginOrHardNavigate(router, { expired: true })
    const secondCall = pushLoginOrHardNavigate(router, { expired: true })

    // /bmad-code-review fix (2026-09-21): pushLoginOrHardNavigate now yields one microtask (see its
    // own comment) before calling router.push at all — both same-tick calls above are queued, but
    // neither has actually invoked router.push yet until that microtask runs.
    await Promise.resolve()

    // Both calls must be racing the SAME underlying navigation — only one router.push issued so far,
    // even though neither call has resolved yet.
    expect(pushSpy).toHaveBeenCalledTimes(1)

    releaseGuard()
    await firstCall
    await secondCall

    expect(pushSpy).toHaveBeenCalledTimes(1)
    expect(window.location.pathname).toBe('/login')
    expect(window.location.search).toContain('expired=true')
  })

  // /bmad-code-review fix (2026-09-21): router.push throwing SYNCHRONOUSLY (as opposed to returning
  // a rejected promise) used to run this whole function's try/catch/finally to completion — including
  // `inFlight = null` — before `inFlight = promise` was even assigned, permanently pinning the guard
  // to an already-settled promise and silently disabling every later call. Fixed by yielding one
  // microtask before doing anything risky (see the IIFE's own comment in sessionRedirect.js).
  it('router.push throwing synchronously does not permanently pin the in-flight guard against later calls', async () => {
    const router = buildRouter() // no guard: /login is always reachable
    await router.push('/blocked')
    let callCount = 0
    const realPush = router.push.bind(router)
    router.push = vi.fn((...args) => {
      callCount += 1
      if (callCount === 1) {
        throw new Error('synchronous boom')
      }
      return realPush(...args)
    })

    await pushLoginOrHardNavigate(router, { expired: true })
    expect(router.push).toHaveBeenCalledTimes(1)
    // The synchronous throw routed to the hard-nav fallback.
    expect(window.location.pathname).toBe('/login')

    // A later, unrelated call must still be able to issue its own router.push — under the bug this
    // guards against, `inFlight` would already be a settled promise here, so this call would
    // short-circuit on `if (inFlight) return inFlight` and router.push would never be called again.
    await pushLoginOrHardNavigate(router, { expired: false })
    expect(router.push).toHaveBeenCalledTimes(2)
  })

  // /bmad-code-review fix (2026-09-21): a call that coalesces onto an already-in-flight navigation
  // (the guard above) used to silently discard its own `expired` intent — the coalescing branch
  // returned before buildRedirectQuery ever ran, so whichever call arrived FIRST unilaterally decided
  // the query for both callers. A deliberate logout (expired: false) racing a genuine expiry
  // (expired: true) in the same tick must not lose the genuine expiry's banner.
  it('a second call racing the first in the same tick folds its own expired:true into the still-pending navigation', async () => {
    let releaseGuard
    const guardGate = new Promise((resolve) => {
      releaseGuard = resolve
    })
    const router = buildRouter(async (to) => {
      if (to.path === '/login') {
        await guardGate
        return false
      }
    })
    await router.push('/blocked')

    // First call is the deliberate logout (no expiry); second, arriving in the same tick, is the
    // genuine expiry — the exact race documented on sessionRedirect.js's `inFlight`/`pendingExpired`.
    const firstCall = pushLoginOrHardNavigate(router, { expired: false })
    const secondCall = pushLoginOrHardNavigate(router, { expired: true })

    releaseGuard()
    await firstCall
    await secondCall

    expect(window.location.pathname).toBe('/login')
    expect(window.location.search).toContain('expired=true')
  })
})
