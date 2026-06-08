<template>
  <div class="error-page app-bg">
    <div class="error-content fade-in">
      <div class="error-number gradient-text">404</div>
      <div class="text-page-title q-mb-sm">Page not found</div>
      <div class="text-body q-mb-xl">The page you're looking for doesn't exist or has been moved.</div>
      <q-btn unelevated to="/" class="btn-accent q-px-xl" size="md" label="Go Home" no-caps />
    </div>
  </div>
</template>

<script setup>
//
</script>

<style lang="scss" scoped>
.error-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  text-align: center;
  padding: 24px;
}
.error-content {
  max-width: 480px;
}
.error-number {
  font-size: clamp(80px, 20vw, 140px);
  font-weight: 800;
  line-height: 1;
  font-family: 'Inter', sans-serif;
}
</style>
