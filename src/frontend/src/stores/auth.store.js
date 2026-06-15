import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { authApi } from 'src/api/auth.api'

const browserTimezone = Intl.DateTimeFormat().resolvedOptions().timeZone

export const useAuthStore = defineStore('auth', () => {
  const userId = ref(null)
  const role = ref(null)
  const displayName = ref(null)
  const timezoneNoticeDismissed = ref(false)

  const isAuthenticated = computed(() => !!userId.value)
  const isCoach = computed(() => role.value === 'COACH')
  const isParent = computed(() => role.value === 'PARENT')
  const isPlayer = computed(() => role.value === 'PLAYER')
  const isAdmin = computed(() => role.value === 'ADMIN')

  function setUser(data) {
    userId.value = data.userId
    role.value = data.role
    displayName.value = data.displayName
  }

  function clearUser() {
    userId.value = null
    role.value = null
    displayName.value = null
  }

  async function logout() {
    try {
      await authApi.skillarsLogout()
    } catch {
      /* best-effort */
    }
    // Clear the skp cookie client-side regardless of API outcome.
    // Without this, hydrateFromCookie() re-authenticates from the stale cookie on next reload
    // even though the Pinia store was cleared.
    document.cookie = 'skp=; Max-Age=0; path=/'
    clearUser()
  }

  /**
   * Hydrate from the skp cookie (non-HttpOnly, set server-side on login/refresh).
   * The skp cookie holds URL-encoded JSON: {"id":<Long>,"role":"COACH"}.
   * NOTE: the user= cookie contains only the display name (plain string) — do NOT parse it as JSON.
   */
  function dismissTimezoneNotice() {
    timezoneNoticeDismissed.value = true
  }

  function hydrateFromCookie() {
    try {
      const match = document.cookie.match(/(?:^|;\s*)skp=([^;]*)/)
      if (match) {
        const parsed = JSON.parse(decodeURIComponent(match[1]))
        if (parsed.id && parsed.role) {
          userId.value = parsed.id
          role.value = parsed.role
          // displayName not in skp — will be populated on next login response
        }
      }
    } catch {
      /* malformed cookie — ignore */
    }
  }

  return {
    userId,
    role,
    displayName,
    isAuthenticated,
    isCoach,
    isParent,
    isPlayer,
    isAdmin,
    timezoneNoticeDismissed,
    browserTimezone,
    setUser,
    clearUser,
    logout,
    hydrateFromCookie,
    dismissTimezoneNotice,
  }
})
