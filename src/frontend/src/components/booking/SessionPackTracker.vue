<template>
  <div class="session-pack-tracker" :class="stateClass">
    <div class="tracker-credits">{{ creditsLabel }}</div>
    <div class="tracker-bar">
      <div class="tracker-bar__fill" :style="{ width: progressPercent + '%', background: progressColor }" />
    </div>
    <div v-if="showCta" class="tracker-cta">
      <q-btn flat dense :label="ctaLabel" @click="$emit('buy-sessions')" />
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

const props = defineProps({
  creditsRemaining: { type: Number, default: 0 },
  sessionCount: { type: Number, default: 0 },
})

defineEmits(['buy-sessions'])
const { t } = useI18n()

const progressPercent = computed(() =>
  props.sessionCount > 0 ? Math.min(100, Math.round((props.creditsRemaining / props.sessionCount) * 100)) : 0
)

const state = computed(() => {
  if (props.creditsRemaining === 0 || props.sessionCount === 0) return 'exhausted'
  if (props.creditsRemaining <= 2) return 'critical'
  if (props.creditsRemaining / props.sessionCount < 0.3) return 'warning'
  return 'healthy'
})

const stateClass = computed(() => `tracker--${state.value}`)
const showCta = computed(() => state.value === 'critical' || state.value === 'exhausted')
const ctaLabel = computed(() =>
  state.value === 'exhausted' ? t('booking.packs.buySessions') : t('booking.packs.buyMoreSessions')
)

const progressColor = computed(() => {
  if (state.value === 'healthy') return 'var(--accent-primary)'
  if (state.value === 'exhausted') return 'var(--color-error)'
  return 'var(--color-warning)'
})

const creditsLabel = computed(() => {
  if (state.value === 'exhausted') return t('booking.packs.exhaustedStatus')
  return t(`booking.packs.${state.value}Status`, { remaining: props.creditsRemaining })
})
</script>

<style lang="scss" scoped>
.session-pack-tracker {
  padding: 12px;
  border-radius: 8px;
  background: var(--surface-raised);
  border: 1px solid var(--border-subtle);
}

.tracker-credits {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-primary);
  margin-bottom: 6px;
}

.tracker-bar {
  height: 6px;
  border-radius: 3px;
  background: var(--border-subtle);
  overflow: hidden;
  margin-bottom: 8px;

  &__fill {
    height: 100%;
    border-radius: 3px;
    transition: width 0.3s ease;
  }
}

.tracker--exhausted .tracker-credits {
  color: var(--color-error);
}

.tracker--warning .tracker-credits,
.tracker--critical .tracker-credits {
  color: var(--color-warning);
}
</style>
