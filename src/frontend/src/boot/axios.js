import { defineBoot } from '#q-app/wrappers';
import axios from 'axios';
import { getCurrentBrowserFingerPrint } from '@rajesh896/broprint.js';
import { recordActivity, stopSessionMonitoring, cleanup, refreshExpiryState } from 'src/plugins/sessionManager';
import { getCurrentLocale } from 'src/boot/i18n';

// Loading state management
let pendingRequests = 0;
const loadingCallbacks = [];

/**
 * Subscribe to loading state changes.
 * @param {Function} callback - Called with (isLoading: boolean) when state changes
 * @returns {Function} Unsubscribe function
 */
function onLoadingChange(callback) {
  loadingCallbacks.push(callback);
  // Return unsubscribe function
  return () => {
    const index = loadingCallbacks.indexOf(callback);
    if (index > -1) {
      loadingCallbacks.splice(index, 1);
    }
  };
}

/**
 * Notify all subscribers of loading state change
 */
function notifyLoadingChange() {
  const isLoading = pendingRequests > 0;
  loadingCallbacks.forEach((callback) => {
    try {
      callback(isLoading);
    } catch (e) {
      console.error('Error in loading callback:', e);
    }
  });
}

/**
 * Delete session cookies (used on logout/session expiry).
 * Clears both 'user' (display name) and 'skp' (id/role, read by
 * authStore.hydrateFromCookie()) — leaving skp behind would let the router's
 * requiresGuest guard re-authenticate from the stale cookie after the redirect
 * to /login and bounce the user straight back into the app.
 */
function deleteUserCookie() {
  document.cookie = 'user=; expires=Thu, 01 Jan 1970 00:00:00 UTC; path=/;';
  document.cookie = 'skp=; Max-Age=0; path=/';
}

/**
 * Check if fcookie (fingerprint cookie) already exists
 */
function hasFingerprintCookie() {
  return document.cookie.split(';').some((c) => c.trim().startsWith('fcookie='));
}

/**
 * Set the browser fingerprint cookie (fcookie) for fraud prevention.
 * Cookie is set with 1 year expiration and persists across sessions.
 * Note: Named 'fcookie' (fingerprint) to distinguish from backend's 'bcookie'.
 */
async function setBrowserFingerprint() {
  if (hasFingerprintCookie()) {
    return; // Cookie already exists
  }

  try {
    const fingerprint = await getCurrentBrowserFingerPrint();
    const expirationDate = new Date();
    expirationDate.setFullYear(expirationDate.getFullYear() + 1); // 1 year expiration
    document.cookie = `fcookie=${fingerprint}; expires=${expirationDate.toUTCString()}; path=/; SameSite=Lax`;
  } catch (error) {
    console.error('Failed to generate browser fingerprint:', error);
  }
}

// Create axios instance with credentials for cookie-based auth
const api = axios.create({
  baseURL: '', // Empty string - backend uses relative paths with dev proxy
  withCredentials: true,
});

// Request interceptor
api.interceptors.request.use(
  (config) => {
    // Increment pending requests counter
    pendingRequests++;
    notifyLoadingChange();

    // Set Accept-Language header for i18n error messages from backend
    config.headers['Accept-Language'] = getCurrentLocale();

    // Record activity for session tracking. The keep-alive call is excluded here so that
    // merely *issuing* it (or a failed one) doesn't reset the idle timer — a genuine
    // user-initiated refresh already records activity explicitly in
    // sessionManager.refreshSession() on success. Exact-match the path (minus any query
    // string) rather than a substring test that could catch unrelated URLs.
    const requestPath = (config.url || '').split('?')[0];
    if (requestPath !== '/refresh') {
      recordActivity();
    }

    return config;
  },
  (error) => {
    // Decrement on request error
    pendingRequests--;
    notifyLoadingChange();
    return Promise.reject(error);
  }
);

// Response interceptor
api.interceptors.response.use(
  (response) => {
    // Decrement pending requests counter
    pendingRequests--;
    notifyLoadingChange();

    // Every authenticated response rewrites the 'rint' cookie (the JWT's new absolute
    // expiry, see JWTAuthorizationFilter -> extendTtlOfToken); re-evaluate from it so the
    // monitor and any open warning dialog reflect the extension immediately.
    refreshExpiryState();

    // Unwrap axios response - return just the data
    return response.data;
  },
  (error) => {
    // Decrement pending requests counter
    pendingRequests--;
    notifyLoadingChange();

    // The auth filter runs (and refreshes 'rint') before business-logic errors occur too.
    // On a 401 the filter has already cleared 'rint' (JwtManagerImpl.deleteLoginToken), so this
    // normally takes the legacy elapsed-time fallback and the errorKey gate below owns the
    // teardown. It is not a guarantee: if that fallback is itself already past zero, tick()
    // fires 'session:expired' here and both paths tear down. That is idempotent (cleanup() and
    // stopSessionMonitoring() are both safe to repeat) but can produce two navigations.
    refreshExpiryState();

    // Handle different error scenarios
    if (error.response) {
      const { status, data } = error.response;
      const errorKey = data?.errorMsg?.errorKey || '';

      // Handle 401 - Unauthorized / Session expired
      if (status === 401) {
        if (errorKey === 'security.sessionExpired' || errorKey === 'security.unauthorized') {
          // Stop session monitoring and clean up
          stopSessionMonitoring();
          cleanup();

          // Delete user cookie
          deleteUserCookie();

          // Redirect to login with current path for redirect after login
          const currentPath = window.location.pathname + window.location.search;
          const redirectUrl = `/login?redirect=${encodeURIComponent(currentPath)}&expired=true`;
          window.location.href = redirectUrl;
        }
      }

      // Handle 403 - Forbidden
      if (status === 403) {
        const message = data?.errorMsg?.message || 'Operation forbidden';
        console.warn('[403 Forbidden]', message, data?.helpCode ? `(Help code: ${data.helpCode})` : '');
      }

      // Handle 5xx - Server errors
      if (status >= 500) {
        const helpCode = data?.helpCode;
        const message = data?.errorMsg?.message || 'Server error';
        console.error('[Server Error]', message, helpCode ? `(Help code: ${helpCode})` : '');
      }
    } else if (error.request) {
      // Network error - request made but no response received
      console.error('[Network Error] Unable to reach server. Please check your connection.');
    } else {
      // Error setting up request
      console.error('[Request Error]', error.message);
    }

    // Always reject with original error for component handling
    return Promise.reject(error);
  }
);

// Quasar boot wrapper - initialize browser fingerprint cookie
export default defineBoot(async () => {
  // Set browser fingerprint cookie for fraud prevention
  await setBrowserFingerprint();
});

export { api, onLoadingChange };
