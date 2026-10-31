import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { authApi } from 'src/api/auth.api'
import { getCoachTier } from 'src/api/marketplace.api'

const browserTimezone = Intl.DateTimeFormat().resolvedOptions().timeZone

export const useAuthStore = defineStore('auth', () => {
  const userId = ref(null)
  const role = ref(null)
  const displayName = ref(null)
  const timezoneNoticeDismissed = ref(false)
  const coachTier = ref(null)
  // S-28: ROLE_LTD_ADMIN without ROLE_ADMIN. UI hint only (role stays 'ADMIN' for routing); the backend
  // enforces the tier on every endpoint, so a tampered cookie only reveals buttons that answer 403.
  const limitedAdmin = ref(false)

  const isAuthenticated = computed(() => !!userId.value)
  const isCoach = computed(() => role.value === 'COACH')
  const isParent = computed(() => role.value === 'PARENT')
  const isPlayer = computed(() => role.value === 'PLAYER')
  const isAdmin = computed(() => role.value === 'ADMIN')
  const isFullAdmin = computed(() => role.value === 'ADMIN' && !limitedAdmin.value)

  function setUser(data) {
    userId.value = data.userId
    role.value = data.role
    displayName.value = data.displayName
    limitedAdmin.value = data.limitedAdmin === true
  }

  function clearUser() {
    userId.value = null
    role.value = null
    displayName.value = null
    coachTier.value = null
    limitedAdmin.value = false
  }

  // skillars-deferred-163 AC4.3: a 20s timeout (getCoachTier, marketplace.api.js) means this always
  // settles — a hung request used to leave both CoachPlayerDetailPage.vue's and ProfilePage.vue's
  // tierSettled gate stuck forever, since a request that never settles is neither a resolved nor a
  // rejected promise. A timeout still rejects, taking the existing catch branch below exactly like
  // any other failure (the UI's tierUnknown indicator already covers "settled but unavailable").
  async function fetchCoachTier() {
    try {
      const response = await getCoachTier()
      coachTier.value = response.tier
    } catch {
      coachTier.value = null
    }
  }

  async function logout() {
    // Clear client-side state synchronously, before the network call, so any router
    // guard evaluating isAuthenticated() while the logout request is in flight sees
    // the user as already logged out (avoids a race that bounces the redirect back
    // into the app because userId/skp were still set).
    document.cookie = 'skp=; Max-Age=0; path=/'
    clearUser()
    try {
      await authApi.skillarsLogout()
    } catch {
      /* best-effort */
    }
  }

  /**
   * Hydrate from the skp cookie (non-HttpOnly, set server-side on login/refresh).
   * The skp cookie holds URL-encoded JSON: {"id":"<Long>","role":"COACH"} — `id` is a QUOTED
   * string, not a bare number. Bug found manually testing (2026-10-01): AuthService.java used to
   * emit `id` unquoted, and a Tsid-sized id (every id in this system) silently lost precision the
   * moment JSON.parse below touched it — authStore.userId then held a DIFFERENT number than the
   * backend's own JWT business ID, surfacing as a 403 wherever that value was later sent back (e.g.
   * CoachProfileBuilderPage.vue's photo-upload entityId). This parses fine as long as
   * the backend keeps quoting it.
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
          limitedAdmin.value = parsed.limitedAdmin === true
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
    coachTier,
    isAuthenticated,
    isCoach,
    isParent,
    isPlayer,
    isAdmin,
    isFullAdmin,
    limitedAdmin,
    timezoneNoticeDismissed,
    browserTimezone,
    setUser,
    clearUser,
    logout,
    hydrateFromCookie,
    dismissTimezoneNotice,
    fetchCoachTier,
  }
})
