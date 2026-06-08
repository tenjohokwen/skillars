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

const router = useRouter();

/**
 * Check if user is authenticated by checking for user cookie.
 */
function isAuthenticated() {
  return document.cookie.includes('user=');
}

/**
 * Handle session expired event - redirect to login.
 */
function handleSessionExpired() {
  // Delete user cookie
  document.cookie = 'user=; expires=Thu, 01 Jan 1970 00:00:00 UTC; path=/;';

  // Cleanup session state
  cleanup();

  // Redirect to login with expired flag
  const currentPath = window.location.pathname + window.location.search;
  router.push({
    path: '/login',
    query: { redirect: currentPath, expired: 'true' }
  });
}

onMounted(() => {
  // Auto-start session monitoring if user is authenticated
  if (isAuthenticated()) {
    startSessionMonitoring();
  }

  // Listen for session expired event
  window.addEventListener('session:expired', handleSessionExpired);
});

onUnmounted(() => {
  stopSessionMonitoring();
  window.removeEventListener('session:expired', handleSessionExpired);
});
</script>
