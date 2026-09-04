/**
 * The landing route for each role, defined ONCE (skillars-deferred-92 AC16).
 *
 * This map used to be declared twice — `router/index.js` and `pages/auth/LoginPage.vue` — and read
 * from six places across the two. The two copies were byte-identical, verified before merging, but
 * only by luck: the failure mode if they ever drifted is an **infinite redirect loop**. The router
 * guard sends a role to route A, the login page pushes it to route B, the guard sends it back, and
 * the user sees a spinning tab with no error anywhere. Nothing in the build would have caught it.
 *
 * `/player/home` is deliberately not a real page: it resolves the caller's own playerId and then
 * redirects to `/player/locker-room/:playerId`.
 */
export const ROLE_ROUTES = Object.freeze({
  COACH: '/coach/command-center',
  PARENT: '/parent/dashboard',
  PLAYER: '/player/home',
  ADMIN: '/admin/health-dashboard',
})

/** Fallback for a role with no landing route of its own (or no role at all). */
export const DEFAULT_ROUTE = '/dashboard'

/** The landing route for `role`, or {@link DEFAULT_ROUTE}. */
export function routeForRole(role) {
  return ROLE_ROUTES[role] || DEFAULT_ROUTE
}
