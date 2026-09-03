import { ref, computed } from 'vue';
import { hasUserSession } from 'src/utils/sessionCookies';

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

// skillars-deferred-90 AC3: a per-tab, reload-surviving marker that this tab has seen a real
// absolute-'rint' cookie at least once — i.e. the backend is on the 1.7b contract, not a legacy
// build. sessionStorage (not a module variable) so it survives a reload of a sibling tab after
// another tab logged out; it dies with the tab, which is the desired lifetime.
const RINT_SEEN_STORAGE_KEY = 'skillars.session.rintSeen';

function markRintSeen() {
  try {
    sessionStorage.setItem(RINT_SEEN_STORAGE_KEY, '1');
  } catch {
    // sessionStorage unavailable (privacy mode / disabled) — degrade to the legacy fallback path.
  }
}

function hasSeenRintThisTab() {
  try {
    return sessionStorage.getItem(RINT_SEEN_STORAGE_KEY) === '1';
  } catch {
    return false;
  }
}

// --- Reactive state -----------------------------------------------------------
const lastActivityTime = ref(Date.now()); // only feeds the legacy fallback
const showWarning = ref(false);
const timeUntilExpiry = ref(LEGACY_SESSION_TTL);
const isRefreshing = ref(false);
// skillars-deferred-90 AC4: true after a refreshSession() call failed, so SessionWarningDialog can
// show an error surface instead of silently re-enabling "Continue session" and ticking to 0:00.
const refreshFailed = ref(false);

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
  if (Number.isFinite(epochMs) && epochMs >= MIN_PLAUSIBLE_EPOCH_MS) {
    markRintSeen();
    return epochMs;
  }
  return null;
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
  if (expiresAt === null) {
    // skillars-deferred-90 AC3: "the shared session was torn down" branch. Fires ONLY when all of:
    //   1. monitoring is actually active (checkIntervalId !== null) — never on the public
    //      marketplace / login page, whose first API call also runs tick() via the axios
    //      response interceptor;
    //   2. this tab has previously seen a real 'rint' (hasSeenRintThisTab) — so we know the
    //      backend is on the absolute-expiry contract and an absent 'rint' means "cleared",
    //      not "legacy build". Reload-durable, so a reloaded sibling tab still knows this;
    //   3. the AC2-hardened session cookie is now absent.
    // Most common trigger: another tab called logout and cleared the shared cookies. Returning 0
    // makes tick() dispatch 'session:expired'. This also closes the round-1 "legacy fallback
    // fails open on the 401 path" item — that path lands here with 'rint' + 'user' both cleared.
    // The 60s grace window (rint maxAge = JWT_TTL + 60s) is unaffected: while 'rint' is still
    // present this branch is not reached and the existing `remaining <= 0` path owns it.
    if (checkIntervalId !== null && hasSeenRintThisTab() && !hasUserSession()) {
      return 0;
    }
    return localEstimate;
  }

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
  if (!showWarning.value && wasWarning) {
    stopCountdown();
    // Code review (3-layer run): the warning band was left (a sibling tab's activity extended the
    // session, or a refresh succeeded), so a previous failure is no longer relevant. Without this
    // the flag was only cleared by refreshSession() and cleanup(), so the next idle-into-warning
    // showed "we couldn't extend your session" when nothing had failed.
    refreshFailed.value = false;
  }

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

  // A fresh monitoring session (login, or a re-arm) must not inherit a stale failure banner:
  // re-login calls startSessionMonitoring() without going through cleanup().
  refreshFailed.value = false;
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
  refreshFailed.value = false;
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
    // skillars-deferred-90 AC4: surface the failure. Without this the catch only logs, isRefreshing
    // returns to false so "Continue session" re-enables as though it worked, the countdown keeps
    // ticking, and at 0:00 the user is logged out with no explanation. A genuine 401 is still
    // handled by the axios interceptor's errorKey gate.
    console.error('Session refresh failed:', e);
    refreshFailed.value = true;
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
  refreshFailed.value = false;
}

// Export reactive refs for composable use
export {
  showWarning,
  timeUntilExpiry,
  secondsRemaining,
  minutesRemaining,
  isRefreshing,
  refreshFailed,
  warningThresholdSeconds,
};
