import { ref, computed } from 'vue';

// --- Constants -------------------------------------------------------------
const SESSION_CHECK_INTERVAL = 30 * 1000; // main monitor tick
const COUNTDOWN_INTERVAL = 1000; // 1s tick while the warning dialog is visible
// Show the warning this long before the session expires. Pure client-side UX choice,
// independent of the backend JWT_TTL (Story 1.7b).
const WARNING_THRESHOLD = 5 * 60 * 1000; // 5 minutes
// Legacy fallback: only used when the 'rint' cookie is absent (e.g. a session issued
// before this contract shipped, or the cookie was stripped). Keep loosely in sync with
// SecurityConstants.JWT_TTL. Under the normal path the absolute 'rint' timestamp is used
// instead and this value is never consulted.
const LEGACY_SESSION_TTL = 15 * 60 * 1000;
const RINT_COOKIE_NAME = 'rint'; // SecurityConstants.SESSION_REFRESH_COUNTDOWN
// Any 'rint' below this is not an absolute epoch-ms timestamp. Pre-1.7b builds wrote a fixed
// delta (600000) as a *browser-session* cookie, so it can still be sitting in a browser that
// was signed in across the deploy; read as an absolute time it lands in 1970 and would expire
// the session instantly. Treat sub-threshold values as "no cookie" and use the legacy fallback.
const MIN_PLAUSIBLE_EPOCH_MS = 1_000_000_000_000; // 2001-09-09

// --- Reactive state -----------------------------------------------------------
const lastActivityTime = ref(Date.now()); // only feeds the legacy fallback
const showWarning = ref(false);
const timeUntilExpiry = ref(LEGACY_SESSION_TTL);
const isRefreshing = ref(false);

// --- Computed display values ------------------------------------------------
const secondsRemaining = computed(() => Math.max(0, Math.ceil(timeUntilExpiry.value / 1000)));
const minutesRemaining = computed(() => Math.ceil(timeUntilExpiry.value / 60000));
// Kept as an exported name (consumed by useSession/SessionWarningDialog); now a constant.
const warningThresholdSeconds = computed(() => Math.round(WARNING_THRESHOLD / 1000));

// --- Internal timer IDs (not reactive) -------------------------------------
let checkIntervalId = null;
let countdownIntervalId = null;

/**
 * Record user activity. Only relevant to the legacy fallback path (see computeTimeUntilExpiry);
 * under the absolute-'rint' contract the server timestamp is authoritative.
 */
export function recordActivity() {
  lastActivityTime.value = Date.now();
}

/**
 * Read the 'rint' cookie as an ABSOLUTE session-expiry timestamp (epoch milliseconds).
 * The backend rewrites it on every authenticated response (JwtManagerImpl.createLoginCookies),
 * advancing it as the sliding-window JWT TTL resets. Returns null if absent, unparseable, or
 * too small to be an absolute timestamp (a stale pre-1.7b fixed-delta value - see
 * MIN_PLAUSIBLE_EPOCH_MS), in which case callers fall back to the legacy elapsed-time estimate.
 */
function readSessionExpiryFromCookie() {
  const match = document.cookie.match(new RegExp(`(?:^|;\\s*)${RINT_COOKIE_NAME}=([^;]*)`));
  if (!match) return null;
  const epochMs = Number(decodeURIComponent(match[1]));
  return Number.isFinite(epochMs) && epochMs >= MIN_PLAUSIBLE_EPOCH_MS ? epochMs : null;
}

/**
 * Milliseconds until the session expires.
 *
 * Primary: the absolute 'rint' timestamp minus now — server-authoritative and multi-tab safe
 * (an idle tab sees a sibling's advanced value). It also survives timer suspension across a
 * laptop sleep, because the deadline is a stored instant rather than an accumulating countdown.
 *
 * It is NOT immune to clock drift — the opposite: `rint - Date.now()` subtracts a CLIENT instant
 * from a SERVER instant, so the result carries the full wall-clock offset between the two
 * machines. `localEstimate` below is a pure Date.now() delta and is therefore skew-immune.
 *
 * So the server timestamp is trusted to *extend* a session freely (that is what makes this
 * multi-tab safe), but it is only trusted to *end* one when the skew-immune local estimate
 * agrees the session has actually been idle. Without that cross-check a client clock fast by
 * more than JWT_TTL reports a small negative remaining — indistinguishable from a real expiry —
 * and force-logs-out a healthy session within 30s of every login, unrecoverably, since each
 * fresh login writes an equally-skewed 'rint'.
 *
 * There is deliberately NO upper bound on `remaining`. Bounding it against LEGACY_SESSION_TTL
 * would silently disable this entire path the moment the backend JWT_TTL was raised above the
 * frontend's loose copy — exactly the coupling this contract promises does not exist. A slow
 * client clock therefore over-reports the remaining time and the client-side warning/expiry
 * simply never fire; the server's 401 remains the backstop for that direction.
 *
 * Fallback (when 'rint' is missing or in the stale pre-1.7b format): elapsed time since the last
 * recorded API activity against the legacy TTL.
 */
function computeTimeUntilExpiry() {
  const localEstimate = LEGACY_SESSION_TTL - (Date.now() - lastActivityTime.value);
  const expiresAt = readSessionExpiryFromCookie();
  if (expiresAt === null) return localEstimate;

  const remaining = expiresAt - Date.now();
  if (remaining <= 0 && localEstimate > 0) return localEstimate;
  return remaining;
}

/**
 * Re-evaluate the session state from the current cookie: refresh the countdown, toggle the
 * warning, and fire 'session:expired' if the deadline has passed.
 *
 * Delegates to tick() rather than assigning timeUntilExpiry directly. Two code paths write the
 * same reactive state and only tick() enforces its invariants, so a direct assignment here could
 * leave timeUntilExpiry negative with the warning dialog stuck open until the next 30s tick.
 * Called by boot/axios.js after every API response, so an in-flight extension shows immediately.
 */
export function refreshExpiryState() {
  tick();
}

/**
 * Apply the current expiry to the reactive state: update the countdown, toggle the warning,
 * and fire 'session:expired' once time runs out. Shared by both timers and by
 * refreshExpiryState().
 * @returns {boolean} true if the session had already expired. On true, tick() has already
 *   dispatched 'session:expired' and run cleanup() (which stops every timer), so a caller that
 *   is about to arm a timer must not do so.
 */
function tick() {
  timeUntilExpiry.value = computeTimeUntilExpiry();

  if (timeUntilExpiry.value <= 0) {
    window.dispatchEvent(new CustomEvent('session:expired'));
    cleanup();
    return true;
  }

  const wasWarning = showWarning.value;
  showWarning.value = timeUntilExpiry.value <= WARNING_THRESHOLD;

  if (showWarning.value && !wasWarning) startCountdown();
  if (!showWarning.value && wasWarning) stopCountdown();

  return false;
}

/**
 * Start the 1-second countdown timer (visual updates while the warning dialog is shown).
 */
function startCountdown() {
  if (countdownIntervalId) return;
  countdownIntervalId = setInterval(tick, COUNTDOWN_INTERVAL);
}

function stopCountdown() {
  if (countdownIntervalId) {
    clearInterval(countdownIntervalId);
    countdownIntervalId = null;
  }
}

/**
 * Start session monitoring (30-second interval checks).
 */
export function startSessionMonitoring() {
  if (checkIntervalId) clearInterval(checkIntervalId);
  stopCountdown();

  recordActivity();
  // Evaluate immediately rather than only priming the state: a tab resumed from sleep, or an
  // app loaded already inside the warning band, must be handled now instead of up to
  // SESSION_CHECK_INTERVAL later. tick() returning true means it already cleaned up.
  if (tick()) return;

  checkIntervalId = setInterval(tick, SESSION_CHECK_INTERVAL);
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
 * Uses dynamic import to avoid circular dependency with the axios boot file.
 */
export async function refreshSession() {
  isRefreshing.value = true;
  try {
    // Dynamic import to break the cycle: axios.js → sessionManager.js → session.api.js → axios.js
    const { sessionApi } = await import('src/api/session.api');
    // Record activity BEFORE the call, not after. The request interceptor deliberately skips
    // recordActivity() for GET /refresh, and the response interceptor now runs a full tick() —
    // so on the no-'rint' fallback path a *successful* refresh would otherwise be evaluated
    // against a stale lastActivityTime and could fire 'session:expired' on its own success.
    recordActivity();
    await sessionApi.refresh();
    // The response set a fresh 'rint'. Re-evaluate and let tick() clear the warning, so its
    // warning-edge handling also stops the 1s countdown interval. Assigning showWarning=false
    // directly here would destroy that edge and leak the interval for the rest of the session.
    recordActivity();
    refreshExpiryState();
  } catch (e) {
    // Log error - let the axios interceptor handle 401
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
  timeUntilExpiry.value = LEGACY_SESSION_TTL;
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
