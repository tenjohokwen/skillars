<template>
  <span :class="['reliability-indicator', stateClass]">
    <q-icon :name="iconName" size="14px" class="q-mr-xs" />
    {{ label }}
  </span>
</template>

<script setup>
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

const props = defineProps({
  strikeCount: { type: Number, required: true },
})

const { t } = useI18n()

const stateClass = computed(() => {
  if (props.strikeCount === 0) return 'reliability-indicator--ok'
  if (props.strikeCount <= 2) return 'reliability-indicator--warning'
  return 'reliability-indicator--danger'
})

const iconName = computed(() => {
  if (props.strikeCount === 0) return 'check_circle'
  if (props.strikeCount <= 2) return 'warning'
  return 'error'
})

const label = computed(() => {
  if (props.strikeCount === 0) return t('marketplace.reliabilityOk')
  if (props.strikeCount <= 2) return t('marketplace.reliabilityWarning', { count: props.strikeCount })
  return t('marketplace.reliabilityDanger')
})
</script>

<style lang="scss" scoped>
.reliability-indicator {
  display: inline-flex;
  align-items: center;
  font-size: 12px;
  font-weight: 500;

  &--ok      { color: var(--accent-success); }
  &--warning { color: var(--accent-warning); }
  &--danger  { color: var(--accent-danger); }
}
</style>
