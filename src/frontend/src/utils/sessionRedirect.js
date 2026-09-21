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
 * route. A no-op if already on `/login`.
 *
 * @param {import('vue-router').Router} router
 * @param {{ expired?: boolean }} [options] `expired` should be true only for a genuine session-expiry
 *   teardown (App.vue's handleSessionExpired) — a deliberate logout (useSession.js, MainLayout.vue)
 *   must not show the "Your session has expired" banner on the next /login render.
 * @returns {Promise<void>}
 */
export async function pushLoginOrHardNavigate(router, { expired = false } = {}) {
  if (router.currentRoute.value.path === LOGIN_PATH) {
    return
  }
  const query = buildRedirectQuery(router, expired)
  try {
    const failure = await router.push({ path: LOGIN_PATH, query })
    if (isNavigationFailure(failure, DID_NOT_LAND)) {
      hardNavigateToLogin(router, query)
    }
  } catch {
    hardNavigateToLogin(router, query)
  }
}

/** Test-only reset for the module-level re-entrancy guard. Not for production use. */
export function __resetSessionRedirectGuardForTests() {
  hardNavigated = false
}
