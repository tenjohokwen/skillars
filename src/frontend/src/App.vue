<template>
  <GlobalLoadingBar />
  <SessionWarningDialog />
  <router-view />
</template>

<script setup>
import { onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import GlobalLoadingBar from 'src/components/common/GlobalLoadingBar.vue'
import SessionWarningDialog from 'src/components/common/SessionWarningDialog.vue'
import { startSessionMonitoring, stopSessionMonitoring, cleanup } from 'src/plugins/sessionManager'
import { useAuthStore } from 'src/stores/auth.store'
import { usePlayerStore } from 'src/stores/playerStore'
import { hasUserSession } from 'src/utils/sessionCookies'
import { pushLoginOrHardNavigate } from 'src/utils/sessionRedirect'

const router = useRouter()
const authStore = useAuthStore()
const playerStore = usePlayerStore()

function isAuthenticated() {
  // Exact cookie-name match AND a non-empty, non-sentinel value: a bare `user=` (empty value) is
  // not a session. See utils/sessionCookies.js (skillars-deferred-90 AC2).
  return hasUserSession()
}

function handleSessionExpired() {
  // Clear all session cookies and in-memory auth state synchronously, before
  // navigating, so the router's requiresGuest guard doesn't see a stale
  // authenticated state and bounce the redirect back into the app.
  document.cookie = 'user=; expires=Thu, 01 Jan 1970 00:00:00 UTC; path=/;'
  authStore.logout() // best-effort backend call fires in background; cookie/state already cleared
  playerStore.resetSelfPlayerId()
  cleanup()
  // skillars-deferred-125 AC3: router.push does not reject for the failure modes this teardown can
  // actually hit (see sessionRedirect.js's own comment) — it falls back to a hard navigation instead
  // of leaving every piece of state above torn down with the user stranded on the current route.
  // expired: true — this IS a genuine session expiry, unlike the two deliberate-logout call sites
  // (useSession.js, MainLayout.vue), which must not show the "Your session has expired" banner.
  pushLoginOrHardNavigate(router, { expired: true })
}

onMounted(() => {
  // Register the listener BEFORE starting the monitor. startSessionMonitoring() evaluates the
  // session immediately (see sessionManager.tick()), so a session that is already past its
  // 'rint' deadline at mount dispatches 'session:expired' synchronously from inside that call.
  // With the old ordering that event landed before this listener existed and was silently
  // dropped, and because the monitor also declines to arm its interval for an expired session
  // the app was left with no session handling at all until the next API call 401'd.
  window.addEventListener('session:expired', handleSessionExpired)

  if (isAuthenticated()) {
    startSessionMonitoring()
  }
})

onUnmounted(() => {
  stopSessionMonitoring()
  window.removeEventListener('session:expired', handleSessionExpired)
})
</script>
