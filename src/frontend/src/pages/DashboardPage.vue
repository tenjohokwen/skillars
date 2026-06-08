<template>
  <q-page class="flex flex-center">
    <div class="column items-center q-gutter-md">
      <q-icon name="check_circle" color="positive" size="64px" />
      <h4 class="q-ma-none">Dashboard</h4>
      <p class="text-subtitle1 text-grey-7">
        You are successfully logged in.
      </p>
      <q-btn
        color="primary"
        label="Logout"
        :loading="loading"
        @click="handleLogout"
      />
    </div>
  </q-page>
</template>

<script setup>
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { authApi } from 'src/api/auth.api';

const router = useRouter();
const loading = ref(false);

async function handleLogout() {
  loading.value = true;
  try {
    await authApi.logout();
  } catch (error) {
    // Logout may fail if session already expired, but we still redirect
    console.warn('Logout error:', error);
  } finally {
    loading.value = false;
    router.push('/login');
  }
}
</script>
