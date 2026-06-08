import { computed } from 'vue';
import { useRouter } from 'vue-router';
import {
  showWarning,
  timeUntilExpiry,
  secondsRemaining,
  minutesRemaining,
  isRefreshing,
  startSessionMonitoring,
  stopSessionMonitoring,
  refreshSession,
  cleanup,
} from 'src/plugins/sessionManager';
import { authApi } from 'src/api/auth.api';

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
 *   handleRefresh: () => Promise<void>,
 *   handleLogout: () => Promise<void>,
 *   initSession: () => void,
 *   destroySession: () => void
 * }}
 */
export function useSession() {
  const router = useRouter();

  // Re-export reactive refs as computed for component use
  const showWarningComputed = computed(() => showWarning.value);
  const timeUntilExpiryComputed = computed(() => timeUntilExpiry.value);
  const secondsRemainingComputed = computed(() => secondsRemaining.value);
  const minutesRemainingComputed = computed(() => minutesRemaining.value);
  const isRefreshingComputed = computed(() => isRefreshing.value);

  /**
   * Handle session refresh.
   */
  async function handleRefresh() {
    await refreshSession();
  }

  /**
   * Handle user logout.
   * Stops session monitoring, calls logout API, cleans up, and redirects.
   */
  async function handleLogout() {
    // Stop session monitoring first
    stopSessionMonitoring();

    // Call logout API - ignore errors (e.g., if already logged out)
    try {
      await authApi.logout();
    } catch {
      // Ignore logout errors
    }

    // Clean up session state
    cleanup();

    // Redirect to login
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
    handleRefresh,
    handleLogout,
    initSession,
    destroySession,
  };
}
