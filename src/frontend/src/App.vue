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

function isAuthenticated() {
  return document.cookie.includes('user=');
}

function handleSessionExpired() {
  document.cookie = 'user=; expires=Thu, 01 Jan 1970 00:00:00 UTC; path=/;';
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
