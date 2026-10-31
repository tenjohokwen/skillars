<template>
  <q-page class="q-pa-md">
    <div class="app-page fade-in">
      <div class="page-header q-mb-lg">
        <div class="text-page-title" data-testid="admin-required-title">
          {{ t('admin.limited.title') }}
        </div>
      </div>
      <q-banner class="bg-warning text-dark" rounded data-testid="admin-required-message">
        <template #avatar>
          <q-icon name="lock" />
        </template>
        {{ t('admin.limited.message') }}
      </q-banner>
      <q-btn
        class="q-mt-md"
        flat
        color="primary"
        to="/admin/health-dashboard"
        :label="t('admin.limited.back')"
        data-testid="admin-required-back-btn"
      />
    </div>
  </q-page>
</template>

<script setup>
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
</script>
