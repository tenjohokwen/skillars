// skillars-deferred-125 AC3 — shared push-or-hard-navigate helper for every session-teardown call
// site that needs to land on /login.
//
// story-review.md correction (2026-09-21): the original finding assumed Vue Router 4's router.push()
// REJECTS on a failed navigation. Verified directly against the installed package
// (node_modules/vue-router, 4.6.4, dist/vue-router.mjs's pushWithRedirect): a guard returning false
// (NAVIGATION_ABORTED) and being superseded by a more recent navigation (NAVIGATION_CANCELLED)
// RESOLVE the promise with a NavigationFailure object — they do not reject it. Only a guard that
// itself throws an arbitrary (non-NavigationFailure) error causes a rejection. Both outcomes are
// handled below: a resolved NavigationFailure (detected via vue-router's own isNavigationFailure
// export, filtered to aborted|cancelled — see the code-review fix note below) and a rejection.
//
// /bmad-code-review fixes (2026-09-21), applied on top of the original AC3 implementation:
//   - isNavigationFailure was called with no type filter, so a resolved NAVIGATION_DUPLICATED (the
//     push target IS the current route — not a failure to land, just a no-op) was treated as
//     "did not land" and forced a needless hard-navigation fallback. Filtered to aborted|cancelled.
//   - buildRedirectQuery() unconditionally set expired:'true', so a DELIBERATE logout (useSession.js,
//     MainLayout.vue) showed a false "Your session has expired" banner on the next /login render.
//     pushLoginOrHardNavigate() now takes an { expired } option; only App.vue's genuine
//     session-expiry teardown passes true.
//   - The hard-navigation fallback built its URL as a bare '/login?...' path. This app runs
//     vueRouterMode: 'hash' (quasar.config.js) — a bare path with no '#' is not the SPA route at all,
//     so route.query.expired/redirect would never be seen after the reload, AND (worse) a hash-only
//     URL change on the SAME document does not force a real page reload in a browser — it just fires
//     'hashchange', leaving every piece of in-memory state that made router.push fail untouched,
//     while permanently latching `hardNavigated` for a fallback that never actually ran. Fixed by
//     building the href via the router's own `resolve()` (respects whatever history mode is actually
//     configured — hash, history, or memory in tests — via history.createHref) and following the
//     assignment with an explicit reload() to force a fresh boot against the new URL regardless of
//     whether only the hash changed.
//   - No guard for "already on /login": a second session:expired dispatch (re-entrant — see the
//     module-level guard note below) would push another /login?redirect=...&expired=true on top of
//     the current /login?redirect=...&expired=true, nesting a self-referential redirect param.
//     Guarded by checking the router's current route before doing anything.
//   - Rewritten from .then()/.catch() chaining to async/await, per project-context.md convention.
//
// Every call site using this helper (App.vue's handleSessionExpired, useSession.js's handleLogout,
// MainLayout.vue's handleLogout) has already torn down cookies/Pinia auth state/session monitoring by
// the time it calls this — if the navigation does not land, the user is left on the current route
// with no active session and no active monitoring.
import { isNavigationFailure, NavigationFailureType } from 'vue-router'

// Re-entrancy guard (module-level, shared by every call site). `handleSessionExpired` is a `window`
// listener, and refreshExpiryState() -> tick() runs from the axios response interceptor on every
// response, so `session:expired` can dispatch more than once in quick succession from concurrent
// in-flight requests completing after expiry. Without this, a burst of calls into this helper could
// each fail their own router.push and each reassign window.location.href/reload() while the page may
// already be in the middle of unloading from the first assignment — harmless, but wasteful. Reset
// only by __resetSessionRedirectGuardForTests, which exists solely for test isolation (mirrors
// sessionManager.js's own vi.resetModules()-based test-reset convention for other module-level state).
let hardNavigated = false

// skillars-deferred-126 AC4 (2026-09-21, story-review.md F-3): a SEPARATE, same-tick guard from
// `hardNavigated` above. `hardNavigated` only prevents a second HARD navigation once one has already
// fired; it does nothing about two calls into this function racing to each start their OWN
// `router.push` before either has resolved. That race is real: `refreshExpiryState()`'s
// `tick()` dispatches `session:expired` via a SYNCHRONOUS `window.dispatchEvent` — every listener,
// including App.vue's `handleSessionExpired` (fire-and-forget, not awaited), runs to the point of its
// own first `await` before `dispatchEvent` returns control to the axios response interceptor, whose
// own 401 handler then calls this function a SECOND time in the SAME tick. At that point neither
// call's `router.push` has resolved, so `router.currentRoute.value.path` is still the pre-navigation
// route for both — the "already on /login" check below cannot distinguish "genuinely elsewhere" from
// "a navigation to /login is already underway". A call that arrives while `inFlight` is already set
// (checked first, before the "already on /login" check — that check would otherwise be fooled by the
// exact race this guard exists to close, since the in-progress navigation has not yet updated the
// current route) awaits and returns THAT SAME promise instead of issuing a second `router.push`.
// Deliberately generic — not a bespoke axios-side "skip if I just dispatched session:expired" special
// case — so it fixes the race for any two of this helper's four call sites that might ever fire in
// the same tick, not only the specific coupling that first surfaced it.
let inFlight = null

// /bmad-code-review fix (2026-09-21): the `expired` intent of a call that coalesces onto `inFlight`
// (above) used to be silently discarded — the coalescing branch returned before buildRedirectQuery
// ever ran, so whichever call happened to arrive FIRST unilaterally decided the query for both. A
// deliberate logout (expired: false) racing a genuine expiry (expired: true) in the same tick — the
// exact scenario `inFlight`'s own comment documents — could therefore either show a false "Your
// session has expired" banner (the precise bug skillars-deferred-125 fixed, resurfacing through this
// different mechanism) or, the other direction, silently swallow a real expiry's banner. `pendingExpired`
// is read once per navigation, at the deferred point documented on the IIFE below — any call that
// arrives before that read (same-tick coalescing) can still fold its own `expired: true` in. It only
// ever upgrades false -> true, never downgrades: once ANY racing caller reports a genuine expiry, that
// is not a fact a later "deliberate logout" call can retract, so the banner errs toward showing a real
// expiry rather than toward hiding one.
let pendingExpired = false

const LOGIN_PATH = '/login'

// A resolved navigation "failure" that means the push genuinely did not land on the target route.
// NAVIGATION_DUPLICATED is deliberately excluded: it resolves when the target IS the current route
// (a no-op, not a failure to land) — treating it as "did not land" forced an unnecessary hard reload
// on every re-entrant call once the first push had already succeeded.
const DID_NOT_LAND = NavigationFailureType.aborted | NavigationFailureType.cancelled

function buildRedirectQuery(router, expired) {
  const query = { redirect: router.currentRoute.value.fullPath }
  if (expired) {
    query.expired = 'true'
  }
  return query
}

function hardNavigateToLogin(router, query) {
  if (hardNavigated) {
    return
  }
  hardNavigated = true
  // router.resolve().href asks the router's own configured history (hash, history, or memory in
  // tests) to build the href — this is the same mechanism <router-link> uses, so it is correct for
  // whatever mode is actually configured rather than assuming one.
  window.location.href = router.resolve({ path: LOGIN_PATH, query }).href
  // A hash-only URL change does not by itself force a full page reload/unload in a browser (it just
  // fires 'hashchange'), which would silently no-op this entire fallback — the whole point of which
  // is to bypass whatever in-memory state (a stuck guard, a broken store) made router.push fail.
  // Forcing a reload here is a no-op in history mode, where the href assignment above already
  // triggers a real navigation.
  window.location.reload()
}

/**
 * Pushes to `/login` with the current route preserved as a `redirect` query param (the query-object
 * form auto-encodes it) plus `expired=true` when `expired` is true. If the push does not actually
 * land — a resolved NavigationFailure (aborted/cancelled) or a rejection — falls back to a hard
 * navigation instead of leaving the caller's already-torn-down session state stranded on the current
 * route. A no-op if already on `/login`. A call that arrives while a previous call is still in
 * flight (skillars-deferred-126 AC4) awaits and returns that same in-flight promise rather than
 * issuing a second `router.push`.
 *
 * @param {import('vue-router').Router} router
 * @param {{ expired?: boolean }} [options] `expired` should be true only for a genuine session-expiry
 *   teardown (App.vue's handleSessionExpired) — a deliberate logout (useSession.js, MainLayout.vue)
 *   must not show the "Your session has expired" banner on the next /login render.
 * @returns {Promise<void>}
 */
export async function pushLoginOrHardNavigate(router, { expired = false } = {}) {
  if (inFlight) {
    if (expired) {
      pendingExpired = true
    }
    return inFlight
  }
  if (router.currentRoute.value.path === LOGIN_PATH) {
    return
  }
  pendingExpired = expired
  const promise = (async () => {
    // /bmad-code-review fix (2026-09-21): this await was added as the FIRST statement in this IIFE,
    // before anything else runs. Two separate bugs both traced back to the same root cause — this
    // body previously started doing real work (building the query, calling router.push) SYNCHRONOUSLY,
    // in the same tick as `pushLoginOrHardNavigate` itself was called:
    //   1. `inFlight = promise` (below) executes AFTER this IIFE is invoked. If router.push were to
    //      throw synchronously (rather than returning a rejected promise — not vue-router's documented
    //      behavior today, but not guaranteed by the type signature either), this body's own
    //      try/catch/finally — including `inFlight = null` — would run to completion before `inFlight
    //      = promise` even executes, permanently pinning the guard to an already-settled promise and
    //      silently disabling every later call into this function.
    //   2. `pendingExpired` (above) was being read to build the query before a same-tick second caller
    //      (see that field's own comment) ever had a chance to fold its own `expired` in.
    // Yielding one microtask here fixes both: `inFlight = promise` is now unconditionally guaranteed to
    // run before this body does anything risky, and every same-tick caller (the exact race `inFlight`
    // and `pendingExpired`'s own comments describe) gets to update `pendingExpired` before it is read.
    await Promise.resolve()
    const query = buildRedirectQuery(router, pendingExpired)
    try {
      const failure = await router.push({ path: LOGIN_PATH, query })
      if (isNavigationFailure(failure, DID_NOT_LAND)) {
        hardNavigateToLogin(router, query)
      }
    } catch {
      hardNavigateToLogin(router, query)
    } finally {
      inFlight = null
    }
  })()
  inFlight = promise
  return promise
}

/** Test-only reset for the module-level re-entrancy guards. Not for production use. */
export function __resetSessionRedirectGuardForTests() {
  hardNavigated = false
  inFlight = null
  pendingExpired = false
}
