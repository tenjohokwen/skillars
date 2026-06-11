<template>
  <q-page class="auth-page">
    <div class="auth-card-container fade-in">
      <div class="glass-card--static auth-card">
        <div class="text-section-title q-mb-xs">{{ t('auth.coach.profileBuilderTitle') }}</div>
        <div class="text-meta">{{ t('auth.coach.profileBuilderBody') }}</div>
      </div>
    </div>
  </q-page>
</template>

<script setup>
import { useI18n } from 'vue-i18n'
const { t } = useI18n()
</script>

<style lang="scss" scoped>
.auth-card { padding: 32px; }
</style>
