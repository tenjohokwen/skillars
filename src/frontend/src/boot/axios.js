import { defineBoot } from '#q-app/wrappers'
import axios from 'axios'
import { getCurrentBrowserFingerPrint } from '@rajesh896/broprint.js'
import {
  recordActivity,
  stopSessionMonitoring,
  cleanup,
  refreshExpiryState,
} from 'src/plugins/sessionManager'
import { getCurrentLocale } from 'src/boot/i18n'
import { pushLoginOrHardNavigate } from 'src/utils/sessionRedirect'

// skillars-deferred-126 AC4: the 401 response interceptor below (module-scope, runs once at module
// import time) needs a `router` reference to call pushLoginOrHardNavigate — but Quasar only injects
// `router` into the boot callback, which runs LATER, when Quasar invokes this file's default export.
// Set once, at the top of that callback (before the existing setBrowserFingerprint() call), and read
// by the interceptor at REQUEST time, by which point boot has already run on every real request path.
let bootRouter = null

// Loading state management
let pendingRequests = 0
const loadingCallbacks = []

/**
 * Subscribe to loading state changes.
 * @param {Function} callback - Called with (isLoading: boolean) when state changes
 * @returns {Function} Unsubscribe function
 */
function onLoadingChange(callback) {
  loadingCallbacks.push(callback)
  // Return unsubscribe function
  return () => {
    const index = loadingCallbacks.indexOf(callback)
    if (index > -1) {
      loadingCallbacks.splice(index, 1)
    }
  }
}

/**
 * Notify all subscribers of loading state change
 */
function notifyLoadingChange() {
  const isLoading = pendingRequests > 0
  loadingCallbacks.forEach((callback) => {
    try {
      callback(isLoading)
    } catch (e) {
      console.error('Error in loading callback:', e)
    }
  })
}

/**
 * Delete session cookies (used on logout/session expiry).
 * Clears both 'user' (display name) and 'skp' (id/role, read by
 * authStore.hydrateFromCookie()) — leaving skp behind would let the router's
 * requiresGuest guard re-authenticate from the stale cookie after the redirect
 * to /login and bounce the user straight back into the app.
 */
function deleteUserCookie() {
  document.cookie = 'user=; expires=Thu, 01 Jan 1970 00:00:00 UTC; path=/;'
  document.cookie = 'skp=; Max-Age=0; path=/'
}

/**
 * Check if fcookie (fingerprint cookie) already exists
 */
function hasFingerprintCookie() {
  return document.cookie.split(';').some((c) => c.trim().startsWith('fcookie='))
}

/**
 * Set the browser fingerprint cookie (fcookie) for fraud prevention.
 * Cookie is set with 1 year expiration and persists across sessions.
 * Note: Named 'fcookie' (fingerprint) to distinguish from backend's 'bcookie'.
 */
async function setBrowserFingerprint() {
  if (hasFingerprintCookie()) {
    return // Cookie already exists
  }

  try {
    const fingerprint = await getCurrentBrowserFingerPrint()
    const expirationDate = new Date()
    expirationDate.setFullYear(expirationDate.getFullYear() + 1) // 1 year expiration
    document.cookie = `fcookie=${fingerprint}; expires=${expirationDate.toUTCString()}; path=/; SameSite=Lax`
  } catch (error) {
    console.error('Failed to generate browser fingerprint:', error)
  }
}

// Create axios instance with credentials for cookie-based auth
const api = axios.create({
  baseURL: '', // Empty string - backend uses relative paths with dev proxy
  withCredentials: true,
})

// Request interceptor
api.interceptors.request.use(
  (config) => {
    // Increment pending requests counter
    pendingRequests++
    notifyLoadingChange()

    // Set Accept-Language header for i18n error messages from backend
    config.headers['Accept-Language'] = getCurrentLocale()

    // Record activity for session tracking. The keep-alive call is excluded here so that
    // merely *issuing* it (or a failed one) doesn't reset the idle timer — a genuine
    // user-initiated refresh already records activity explicitly in
    // sessionManager.refreshSession() on success. Exact-match the path (minus any query
    // string) rather than a substring test that could catch unrelated URLs.
    const requestPath = (config.url || '').split('?')[0]
    if (requestPath !== '/refresh') {
      recordActivity()
    }

    return config
  },
  (error) => {
    // Decrement on request error
    pendingRequests--
    notifyLoadingChange()
    return Promise.reject(error)
  },
)

// Response interceptor
api.interceptors.response.use(
  (response) => {
    // Decrement pending requests counter
    pendingRequests--
    notifyLoadingChange()

    // Every authenticated response rewrites the 'rint' cookie (the JWT's new absolute
    // expiry, see JWTAuthorizationFilter -> extendTtlOfToken); re-evaluate from it so the
    // monitor and any open warning dialog reflect the extension immediately.
    refreshExpiryState()

    // Unwrap axios response - return just the data
    return response.data
  },
  (error) => {
    // Decrement pending requests counter
    pendingRequests--
    notifyLoadingChange()

    // The auth filter runs (and refreshes 'rint') before business-logic errors occur too.
    // On a 401 the filter has already cleared 'rint' (JwtManagerImpl.deleteLoginToken), so this
    // normally takes the legacy elapsed-time fallback and the errorKey gate below owns the
    // teardown. It is not a guarantee: if that fallback is itself already past zero, tick()
    // fires 'session:expired' here and both paths tear down. That is idempotent (cleanup() and
    // stopSessionMonitoring() are both safe to repeat) but can produce two navigations.
    refreshExpiryState()

    // Handle different error scenarios
    if (error.response) {
      const { status, data } = error.response
      const errorKey = data?.errorMsg?.errorKey || ''

      // Handle 401 - Unauthorized / Session expired
      if (status === 401) {
        if (errorKey === 'security.sessionExpired' || errorKey === 'security.unauthorized') {
          // Stop session monitoring and clean up
          stopSessionMonitoring()
          cleanup()

          // Delete user cookie
          deleteUserCookie()

          // skillars-deferred-126 AC4: was a hard `window.location.href = '/login?...'` — this app
          // runs vueRouterMode: 'hash' (quasar.config.js), so a bare '/login' path targets the SERVER
          // path, not the SPA route (which lives at '/#/login'), and never actually reached it;
          // route.query.expired/redirect were always undefined on the next render as a result. Reuses
          // the shared sessionRedirect.js helper skillars-deferred-125 AC3 already introduced for the
          // three Vue-component call sites, rather than a standalone hash-aware patch that would leave
          // two different redirect mechanisms in the codebase for the same event.
          //
          // `expired` is gated on the ACTUAL errorKey, not hardcoded true — the 401 condition above
          // covers 'security.unauthorized' too, which is NOT an expiry (AuthorizationException /
          // non-expiry JWT failures). Before this fix that conflation was harmless only because the
          // hash-mode bug meant `expired` never reached the SPA at all; once it does, hardcoding true
          // would render a false "Your session has expired" banner for a plain unauthorized 401.
          if (bootRouter) {
            // /bmad-code-review fix (2026-09-21): this call was neither awaited nor had a .catch() —
            // a throw from inside pushLoginOrHardNavigate that escapes its own inner try (e.g. router
            // itself being malformed) would become an unhandled promise rejection with the session
            // already torn down above and no redirect ever issued. This handler is itself a plain
            // (non-async) callback, so `await` is not available here; `.catch()` is the correct shape.
            pushLoginOrHardNavigate(bootRouter, {
              expired: errorKey === 'security.sessionExpired',
            }).catch((redirectError) => {
              console.error('[401] pushLoginOrHardNavigate failed unexpectedly', redirectError)
            })
          } else {
            // Defensive fallback for the theoretical case this interceptor fires before boot has run
            // (should not happen in Quasar's normal boot sequence — boot callbacks run before the app
            // mounts and issues any request that could 401 — but confirmed not silently no-op-ing if
            // it somehow does, e.g. setBrowserFingerprint() itself failing with a 401).
            //
            // /bmad-code-review fixes (2026-09-21):
            //   - `redirect` must match the SAME shape sessionRedirect.js's own buildRedirectQuery
            //     produces (router.currentRoute.value.fullPath, e.g. '/dashboard?foo=bar' — no leading
            //     '#', no window.location.pathname prefix). This app runs hash-mode (quasar.config.js),
            //     so the SPA's own path lives entirely after the '#'; window.location.pathname is
            //     always just '/' (the server path) and prepending it produced a malformed value
            //     ('/#/dashboard') that LoginPage.vue's own `redirect.startsWith('/') &&
            //     !redirect.startsWith('//')` guard accepted anyway (it does start with '/'), then
            //     pushed as a route — landing the user on a bogus nested route, not back where they
            //     were.
            //   - Added the window.location.reload() that hardNavigateToLogin (sessionRedirect.js's
            //     own equivalent fallback) documents as mandatory: a hash-only URL change does not by
            //     itself force a page reload/unload in a browser — it only fires 'hashchange' on the
            //     current document — which would silently no-op the very escape hatch this branch
            //     exists to provide (boot never having run implies whatever broke it is still broken
            //     without a real reload).
            const currentPath = window.location.hash ? window.location.hash.slice(1) : '/'
            const expired = errorKey === 'security.sessionExpired'
            window.location.href = `/#/login?redirect=${encodeURIComponent(currentPath)}&expired=${expired}`
            window.location.reload()
          }
        }
      }

      // Handle 403 - Forbidden
      if (status === 403) {
        const message = data?.errorMsg?.message || 'Operation forbidden'
        console.warn(
          '[403 Forbidden]',
          message,
          data?.helpCode ? `(Help code: ${data.helpCode})` : '',
        )
      }

      // Handle 5xx - Server errors
      if (status >= 500) {
        const helpCode = data?.helpCode
        const message = data?.errorMsg?.message || 'Server error'
        console.error('[Server Error]', message, helpCode ? `(Help code: ${helpCode})` : '')
      }
    } else if (error.request) {
      // Network error - request made but no response received
      console.error('[Network Error] Unable to reach server. Please check your connection.')
    } else {
      // Error setting up request
      console.error('[Request Error]', error.message)
    }

    // Always reject with original error for component handling
    return Promise.reject(error)
  },
)

// Quasar boot wrapper - initialize browser fingerprint cookie
export default defineBoot(async ({ router }) => {
  // skillars-deferred-126 AC4: capture the boot-injected router for the 401 interceptor above, which
  // is registered at module scope (before this callback ever runs) and has no other way to reach it.
  bootRouter = router

  // Set browser fingerprint cookie for fraud prevention
  await setBrowserFingerprint()
})

export { api, onLoadingChange }
