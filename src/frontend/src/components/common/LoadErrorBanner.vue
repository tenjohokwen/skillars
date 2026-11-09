<template>
  <q-banner class="bg-negative text-white q-mb-md" rounded data-testid="load-error">
    {{ message || t('common.loadFailed') }}
    <template #action>
      <q-btn
        flat
        no-caps
        color="white"
        icon="refresh"
        :label="t('common.retry')"
        :loading="retrying"
        data-testid="load-retry"
        @click="$emit('retry')"
      />
    </template>
  </q-banner>
</template>

<script setup>
import { useI18n } from 'vue-i18n'

// Pitchside X-09: a page whose main data call failed says so and offers a retry, instead of an empty state
// that reads as "you have none" or a spinner that never ends.
defineProps({
  message: { type: String, default: '' },
  retrying: { type: Boolean, default: false },
})
defineEmits(['retry'])

const { t } = useI18n()
</script>
