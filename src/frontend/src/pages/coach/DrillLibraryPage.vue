<template>
  <q-page class="q-pa-md">
    <h1 class="text-h5 q-mb-md">{{ t('session.drillLibrary.title') }}</h1>
    <p>{{ t('session.drillLibrary.placeholder') }}</p>
  </q-page>
</template>

<script setup>
import { onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { sessionApi } from 'src/api/session.api'

const { t } = useI18n()

onMounted(async () => {
  const response = await sessionApi.getDrills('PLATFORM')
  console.log('[DrillLibraryPage] platform drills:', response.data)
})
</script>
