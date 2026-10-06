/**
 * Decides whether a `?redirect=` query value is safe to navigate to after login/OTP —
 * previously duplicated verbatim in LoginPage.vue and OtpPage.vue (skillars-deferred-142 AC3's
 * own explicit requirement, to keep the two pages byte-identical). See roleRoutes.js's own
 * header for why a duplicated routing concern is dangerous even when the two copies are
 * currently identical: the failure mode is silent on introduction and only appears once one
 * copy is changed and the other isn't.
 *
 * Two checks: shape (must look like an app-relative path — not protocol-relative, not absolute)
 * and resolvability (must match a real route, excluding the tagged catch-all, otherwise even a
 * shape-safe value would dead-end the just-authenticated user on the 404 page instead of their
 * role's dashboard).
 *
 * Code review 2026-10-06: the shape check alone is NOT "the open-redirect guard" — it is
 * incidental protection today. A backslash- or percent-encoded payload (`/\evil.example.com`,
 * `/%2F%2Fevil.example.com`) passes the shape check; what actually rejects it right now is the
 * resolvability check finding no real route and landing only on the tagged catch-all (see
 * safeRedirectSpec.js's pinned cases). Add one permissive top-level route (a `/:slug` CMS route
 * is the ordinary way) and a backslash/percent-encoded payload would resolve and this guard would
 * wave it through with no failing test to catch it. The actual reason this can never become an
 * off-origin navigation, regardless of what the route table ever grows into, is
 * `quasar.config.js`'s `vueRouterMode: 'hash'` — a `/`-prefixed path can never become
 * protocol-relative or absolute in hash mode. Hardening the shape check itself (rejecting
 * backslashes and percent-encoding outright) is tracked as a follow-up, not done here.
 *
 * `matched.length > 0` alone is NOT sufficient here, unlike in a route table with no catch-all:
 * routes.js registers `/:catchAll(.*)*` -> ErrorNotFound.vue, which matches every `/`-prefixed
 * path, so an unresolvable redirect would still report matched.length === 1 (verified by
 * executing vue-router 4.6.4 against this exact route shape). The catch-all is tagged
 * `meta: { notFound: true }` (routes.js) specifically so this guard can exclude it.
 */
export function isSafeRedirect(path, router) {
  if (typeof path !== 'string' || !path.startsWith('/') || path.startsWith('//')) {
    return false
  }
  const resolved = router.resolve(path)
  return resolved.matched.length > 0 && !resolved.matched.some((r) => r.meta?.notFound)
}
