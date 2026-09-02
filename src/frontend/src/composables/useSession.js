import { computed } from 'vue';
import { useRouter } from 'vue-router';
import {
  showWarning,
  timeUntilExpiry,
  secondsRemaining,
  minutesRemaining,
  isRefreshing,
  warningThresholdSeconds,
  startSessionMonitoring,
  stopSessionMonitoring,
  refreshSession,
  cleanup,
} from 'src/plugins/sessionManager';
import { useAuthStore } from 'src/stores/auth.store';
import { usePlayerStore } from 'src/stores/playerStore';

/**
 * Composable for session management.
 * Provides reactive session state and actions for components.
 *
 * @returns {{
 *   showWarning: import('vue').ComputedRef<boolean>,
 *   timeUntilExpiry: import('vue').ComputedRef<number>,
 *   secondsRemaining: import('vue').ComputedRef<number>,
 *   minutesRemaining: import('vue').ComputedRef<number>,
 *   isRefreshing: import('vue').ComputedRef<boolean>,
 *   warningThresholdSeconds: import('vue').ComputedRef<number>,
 *   handleRefresh: () => Promise<void>,
 *   handleLogout: () => Promise<void>,
 *   initSession: () => void,
 *   destroySession: () => void
 * }}
 */
// Longest we block the logout teardown on the backend revocation call before proceeding anyway.
const LOGOUT_BACKEND_WAIT_MS = 3000;

export function useSession() {
  const router = useRouter();
  const authStore = useAuthStore();
  const playerStore = usePlayerStore();

  // Re-export reactive refs as computed for component use
  const showWarningComputed = computed(() => showWarning.value);
  const timeUntilExpiryComputed = computed(() => timeUntilExpiry.value);
  const secondsRemainingComputed = computed(() => secondsRemaining.value);
  const minutesRemainingComputed = computed(() => minutesRemaining.value);
  const isRefreshingComputed = computed(() => isRefreshing.value);
  const warningThresholdSecondsComputed = computed(() => warningThresholdSeconds.value);

  /**
   * Handle session refresh.
   */
  async function handleRefresh() {
    await refreshSession();
  }

  /**
   * Handle user logout.
   *
   * Mirrors App.vue's handleSessionExpired() teardown (the known-working path): clear the
   * `user` cookie, then wipe the Pinia auth store AND the `skp` cookie via authStore.logout()
   * (which also fires the best-effort backend logout call). Without clearing authStore/`skp`
   * the router's requiresGuest guard still sees a valid session and bounces the user straight
   * back into the app after the /login redirect (M2 from the review).
   */
  async function handleLogout() {
    stopSessionMonitoring();

    document.cookie = 'user=; expires=Thu, 01 Jan 1970 00:00:00 UTC; path=/;';
    // Awaited so callers of handleLogout() get a real guarantee that the backend revocation was
    // attempted before the promise resolves (the 'rtkn' refresh token has a 7-day TTL). This
    // does not delay the guard: authStore.logout() clears the skp cookie and the Pinia state
    // synchronously, before its own first await.
    //
    // The wait is BOUNDED. The axios instance sets no timeout, so on a stalled connection the
    // request can stay pending indefinitely; an unbounded await here would strand the user in a
    // persistent warning dialog with its countdown frozen (stopSessionMonitoring() has already
    // run) and no navigation, because cleanup() and router.push() are both behind this call.
    // authStore.logout() swallows its own errors, so this races only against the hang.
    await Promise.race([
      authStore.logout(), // clears skp + Pinia userId/role/displayName, then backend logout
      new Promise((resolve) => setTimeout(resolve, LOGOUT_BACKEND_WAIT_MS)),
    ]);
    playerStore.resetSelfPlayerId();
    cleanup();

    router.push('/login');
  }

  /**
   * Initialize session monitoring.
   * Call this when user becomes authenticated.
   */
  function initSession() {
    startSessionMonitoring();
  }

  /**
   * Destroy session monitoring.
   * Call this when user logs out or session expires.
   */
  function destroySession() {
    stopSessionMonitoring();
    cleanup();
  }

  return {
    showWarning: showWarningComputed,
    timeUntilExpiry: timeUntilExpiryComputed,
    secondsRemaining: secondsRemainingComputed,
    minutesRemaining: minutesRemainingComputed,
    isRefreshing: isRefreshingComputed,
    warningThresholdSeconds: warningThresholdSecondsComputed,
    handleRefresh,
    handleLogout,
    initSession,
    destroySession,
  };
}
