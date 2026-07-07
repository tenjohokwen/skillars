import { ref, computed } from 'vue';

// Constants
const DEFAULT_WARNING_THRESHOLD = 2 * 60 * 1000; // fallback used until the 'rint' cookie has been seen
const SESSION_CHECK_INTERVAL = 30 * 1000; // 30 seconds
const COUNTDOWN_INTERVAL = 1000; // 1 second for countdown display
const SESSION_TTL = 15 * 60 * 1000; // 15 minutes in ms — must match SecurityConstants.JWT_TTL
const RINT_COOKIE_NAME = 'rint'; // SecurityConstants.SESSION_REFRESH_COUNTDOWN

// Reactive state
const lastActivityTime = ref(Date.now());
const showWarning = ref(false);
const timeUntilExpiry = ref(SESSION_TTL);
const isRefreshing = ref(false);
// How long before expiry the warning should start. Kept in sync with the server-issued
// 'rint' cookie (see syncWarningThresholdFromCookie) so the client doesn't rely on a
// hardcoded guess that can drift from the backend's actual JWT_TTL/warning-window config.
const warningThreshold = ref(DEFAULT_WARNING_THRESHOLD);

// Computed values for display
const secondsRemaining = computed(() => Math.max(0, Math.ceil(timeUntilExpiry.value / 1000)));
const minutesRemaining = computed(() => Math.ceil(timeUntilExpiry.value / 60000));
const warningThresholdSeconds = computed(() => Math.round(warningThreshold.value / 1000));

// Internal timer IDs (not reactive)
let checkIntervalId = null;
let countdownIntervalId = null;

/**
 * Record user activity to reset session timer.
 */
export function recordActivity() {
  lastActivityTime.value = Date.now();
}

/**
 * Read the 'rint' cookie value (ms). The backend refreshes this cookie on every
 * authenticated request (JWTAuthorizationFilter -> extendTtlOfToken/renewLoginToken),
 * setting it to JWT_TTL minus the server's intended warning window. Deriving the
 * warning threshold from it keeps the client's warning window authoritative even if
 * the backend's TTL or warning-window constants change.
 */
function readRintCookie() {
  const match = document.cookie.match(new RegExp(`(?:^|;\\s*)${RINT_COOKIE_NAME}=([^;]*)`));
  if (!match) return null;
  const ms = Number(decodeURIComponent(match[1]));
  return Number.isFinite(ms) && ms > 0 ? ms : null;
}

/**
 * Sync the warning threshold from the 'rint' cookie. Call this after every
 * authenticated API response (the cookie is refreshed alongside the JWT).
 */
export function syncWarningThresholdFromCookie() {
  const rint = readRintCookie();
  if (rint === null) return;
  const threshold = SESSION_TTL - rint;
  if (threshold > 0 && threshold < SESSION_TTL) {
    warningThreshold.value = threshold;
  }
}

/**
 * Start the countdown timer for visual updates (every second).
 */
function startCountdown() {
  if (countdownIntervalId) return; // Already running

  countdownIntervalId = setInterval(() => {
    const elapsed = Date.now() - lastActivityTime.value;
    timeUntilExpiry.value = SESSION_TTL - elapsed;

    // Handle session expiry during countdown
    if (timeUntilExpiry.value <= 0) {
      window.dispatchEvent(new CustomEvent('session:expired'));
      cleanup();
    }
  }, COUNTDOWN_INTERVAL);
}

/**
 * Stop the countdown timer.
 */
function stopCountdown() {
  if (countdownIntervalId) {
    clearInterval(countdownIntervalId);
    countdownIntervalId = null;
  }
}

/**
 * Start session monitoring with 30-second interval checks.
 */
export function startSessionMonitoring() {
  // Clear any existing intervals
  if (checkIntervalId) {
    clearInterval(checkIntervalId);
  }
  stopCountdown();

  // Initialize with current activity
  recordActivity();
  // Pick up any 'rint' cookie already present (e.g. surviving from a prior page load)
  // instead of waiting for the next API response.
  syncWarningThresholdFromCookie();

  // Start interval timer for main checks
  checkIntervalId = setInterval(() => {
    // Calculate time until expiry
    const elapsed = Date.now() - lastActivityTime.value;
    timeUntilExpiry.value = SESSION_TTL - elapsed;

    const wasWarning = showWarning.value;

    // Set warning flag when under the server-driven threshold and still positive
    showWarning.value = timeUntilExpiry.value < warningThreshold.value && timeUntilExpiry.value > 0;

    // Start countdown timer when warning begins (for visual second-by-second updates)
    if (showWarning.value && !wasWarning) {
      startCountdown();
    }

    // Stop countdown timer when warning ends (user refreshed session)
    if (!showWarning.value && wasWarning) {
      stopCountdown();
    }

    // Handle session expiry
    if (timeUntilExpiry.value <= 0) {
      // Dispatch session expired event
      window.dispatchEvent(new CustomEvent('session:expired'));
      cleanup();
    }
  }, SESSION_CHECK_INTERVAL);
}

/**
 * Stop session monitoring.
 */
export function stopSessionMonitoring() {
  if (checkIntervalId) {
    clearInterval(checkIntervalId);
    checkIntervalId = null;
  }
  stopCountdown();
}

/**
 * Refresh the session by calling the API.
 * Uses dynamic import to avoid circular dependency with axios boot file.
 */
export async function refreshSession() {
  isRefreshing.value = true;
  try {
    // Dynamic import to break circular dependency:
    // axios.js → sessionManager.js → session.api.js → axios.js
    const { sessionApi } = await import('src/api/session.api');
    await sessionApi.refresh();
    // On success, record activity and clear warning
    recordActivity();
    showWarning.value = false;
  } catch (e) {
    // Log error - let axios interceptor handle 401
    console.error('Session refresh failed:', e);
  } finally {
    isRefreshing.value = false;
  }
}

/**
 * Clean up session state and stop monitoring.
 */
export function cleanup() {
  stopSessionMonitoring();
  lastActivityTime.value = Date.now();
  showWarning.value = false;
  timeUntilExpiry.value = SESSION_TTL;
  isRefreshing.value = false;
}

// Export reactive refs for composable use
export {
  showWarning,
  timeUntilExpiry,
  secondsRemaining,
  minutesRemaining,
  isRefreshing,
  warningThresholdSeconds,
};
