<template>
  <GlobalLoadingBar />
  <SessionWarningDialog />
  <router-view />
</template>

<script setup>
import { onMounted, onUnmounted } from 'vue';
import { useRouter } from 'vue-router';
import GlobalLoadingBar from 'src/components/common/GlobalLoadingBar.vue';
import SessionWarningDialog from 'src/components/common/SessionWarningDialog.vue';
import { startSessionMonitoring, stopSessionMonitoring, cleanup } from 'src/plugins/sessionManager';
import { useAuthStore } from 'src/stores/auth.store';

const router = useRouter();
const authStore = useAuthStore();

function isAuthenticated() {
  return document.cookie.includes('user=');
}

function handleSessionExpired() {
  // Clear all session cookies and in-memory auth state synchronously, before
  // navigating, so the router's requiresGuest guard doesn't see a stale
  // authenticated state and bounce the redirect back into the app.
  document.cookie = 'user=; expires=Thu, 01 Jan 1970 00:00:00 UTC; path=/;';
  authStore.logout(); // best-effort backend call fires in background; cookie/state already cleared
  cleanup();
  const currentPath = window.location.pathname + window.location.search;
  router.push({
    path: '/login',
    query: { redirect: currentPath, expired: 'true' }
  });
}

onMounted(() => {
  if (isAuthenticated()) {
    startSessionMonitoring();
  }

  window.addEventListener('session:expired', handleSessionExpired);
});

onUnmounted(() => {
  stopSessionMonitoring();
  window.removeEventListener('session:expired', handleSessionExpired);
});
</script>
