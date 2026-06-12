<template>
  <q-page class="auth-page">
    <div class="auth-card-container fade-in">

      <div class="auth-brand q-mb-xl">
        <div class="gradient-text auth-brand-name">Skillars</div>
      </div>

      <div class="glass-card--static auth-card text-center">
        <div class="text-section-title q-mb-md">{{ t('auth.parent.dashboardTitle') }}</div>
        <div class="text-meta">{{ t('auth.parent.dashboardBody') }}</div>

        <div class="q-mt-xl">
          <router-link to="/login" class="auth-link">
            {{ t('auth.login') }}
          </router-link>
        </div>
      </div>
    </div>
  </q-page>
</template>

<script setup>
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
</script>

<style lang="scss" scoped>
.auth-brand { text-align: center; }
.auth-brand-name {
  font-size: 32px;
  font-weight: 800;
  font-family: 'Inter', sans-serif;
  letter-spacing: -1px;
}
.auth-card { padding: 40px 32px; }
.auth-link {
  color: var(--accent-primary);
  text-decoration: none;
  font-size: 14px;
  font-weight: 500;
  &:hover { opacity: 0.8; }
}
</style>
