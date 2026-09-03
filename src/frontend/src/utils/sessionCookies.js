// Single source of truth for the `user` session cookie's contract (skillars-deferred-90 AC2).
//
// The backend writes the authenticated user's display name into a non-HttpOnly `user` cookie so
// the SPA can show a greeting and cheaply tell "there is a session" from "there is not". Two bugs
// this module closes:
//   1. `document.cookie.split(';').some(c => c.trim().startsWith('user='))` returns true for a
//      zero-length value, so a blank `user=` counted as authenticated.
//   2. `/user=([^;]+)/` is unanchored, so it also matches a cookie whose name ends in `user`
//      (e.g. `xuser=`).
// Backend hardening writes BLANK_DISPLAY_NAME_SENTINEL (SecurityConstants.BLANK_DISPLAY_NAME_SENTINEL)
// instead of an empty value when the display name is blank; readers here treat the sentinel — and a
// blank value — as "no usable display name".

export const USER_COOKIE_SENTINEL = '__blank__';

// Anchored: the capture only fires for a cookie actually named `user`.
const USER_COOKIE_RE = /(?:^|;\s*)user=([^;]*)/;

/** Raw decoded value of the `user` cookie, or null when the cookie is absent. */
export function readUserCookie() {
  const match = document.cookie.match(USER_COOKIE_RE);
  if (!match) {
    return null;
  }
  try {
    return decodeURIComponent(match[1]);
  } catch {
    return match[1];
  }
}

/**
 * True when the `user` cookie represents a live session.
 *
 * The sentinel COUNTS as a session — it is what the backend writes for an authenticated user whose
 * display name is blank (SecurityConstants.BLANK_DISPLAY_NAME_SENTINEL). Only a missing or
 * zero-length value means "no session".
 *
 * Code review (3-layer run, 2026-09-03): this previously excluded the sentinel, so a logged-in user
 * with a blank display name read as unauthenticated — App.vue's isAuthenticated() returned false,
 * and sessionManager.tick()'s `checkIntervalId !== null && hasSeenRintThisTab() && !hasUserSession()`
 * branch force-logged them out on any transient `rint` miss. That is the AC2/AC3 conflict the story
 * audit raised as F3, reintroduced through the sentinel. Use readUserDisplayName() — not this
 * function — when you need a name to render.
 */
export function hasUserSession() {
  const value = readUserCookie();
  return value !== null && value !== '';
}

/** The display name to greet the user by, or null when there is no usable one. */
export function readUserDisplayName() {
  const value = readUserCookie();
  if (!value || value === USER_COOKIE_SENTINEL) {
    return null;
  }
  return value;
}
