<template>
  <div v-if="isLoading" class="global-loading-bar">
    <q-linear-progress indeterminate color="primary" />
  </div>
</template>

<script setup>
import { useLoading } from 'src/composables/useLoading';

const { isLoading } = useLoading();
</script>

<style scoped>
.global-loading-bar {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  z-index: 9999;
}
</style>
