import { computed } from 'vue'
import { getActivePinia } from 'pinia'
import { useAuthStore } from 'src/stores/auth.store'

/**
 * S-28: lets an admin page hide actions that need a full administrator (money, enforcement, config,
 * personal data) when the signed-in account is a limited administrator. UI hint only - the backend
 * returns 403 for those endpoints regardless.
 *
 * Reads the auth store only when Pinia is active, so the admin page specs that mount a page without
 * a store keep behaving as a full administrator.
 */
export function useAdminTier() {
  const authStore = getActivePinia() ? useAuthStore() : null
  const isLimitedAdmin = computed(() => authStore?.limitedAdmin === true)
  return { isLimitedAdmin }
}
