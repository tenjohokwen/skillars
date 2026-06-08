import { ref, computed } from 'vue';

// Constants
const SESSION_WARNING_THRESHOLD = 2 * 60 * 1000; // 2 minutes in ms
const SESSION_CHECK_INTERVAL = 30 * 1000; // 30 seconds
const COUNTDOWN_INTERVAL = 1000; // 1 second for countdown display
const SESSION_TTL = 15 * 60 * 1000; // 15 minutes in ms

// Reactive state
const lastActivityTime = ref(Date.now());
const showWarning = ref(false);
const timeUntilExpiry = ref(SESSION_TTL);
const isRefreshing = ref(false);

// Computed values for display
const secondsRemaining = computed(() => Math.max(0, Math.ceil(timeUntilExpiry.value / 1000)));
const minutesRemaining = computed(() => Math.ceil(timeUntilExpiry.value / 60000));

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

  // Start interval timer for main checks
  checkIntervalId = setInterval(() => {
    // Calculate time until expiry
    const elapsed = Date.now() - lastActivityTime.value;
    timeUntilExpiry.value = SESSION_TTL - elapsed;

    const wasWarning = showWarning.value;

    // Set warning flag when under 2 minutes and still positive
    showWarning.value = timeUntilExpiry.value < SESSION_WARNING_THRESHOLD && timeUntilExpiry.value > 0;

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
export { showWarning, timeUntilExpiry, secondsRemaining, minutesRemaining, isRefreshing };
