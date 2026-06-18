<template>
  <div class="drill-suggestion-panel">
    <div class="row items-center q-mb-sm">
      <div class="text-subtitle2 col">
        {{ isPersonalized ? t('session.suggestions.personalizedTitle') : t('session.suggestions.fallbackTitle') }}
      </div>
      <q-btn flat dense round icon="close" size="xs" @click="emit('close')" />
    </div>

    <div v-if="loading" class="flex flex-center q-pa-md">
      <q-spinner-dots size="24px" color="primary" />
    </div>

    <div v-else-if="!suggestions.length" class="text-caption text-secondary q-pa-md text-center">
      {{ t('session.suggestions.empty') }}
    </div>

    <div v-else class="drill-suggestion-panel__list">
      <DrillCard
        v-for="drill in suggestions"
        :key="drill.id"
        :drill="drill"
        context="session-builder"
        class="q-mb-xs"
        @add-to-session="emit('add-drill', drill)"
        @open-detail="emit('open-detail', drill)"
      />
    </div>
  </div>
</template>

<script setup>
import { useI18n } from 'vue-i18n'
import DrillCard from 'src/components/session/DrillCard.vue'

defineProps({
  suggestions: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  isPersonalized: { type: Boolean, default: false },
})

const emit = defineEmits(['close', 'add-drill', 'open-detail'])
const { t } = useI18n()
</script>
