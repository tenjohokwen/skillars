<template>
  <div class="development-focus-selector">
    <div class="text-subtitle2 q-mb-sm">{{ t('session.builder.developmentFocus') }}</div>
    <div class="row q-gutter-sm">
      <q-chip
        v-for="option in FOCUS_OPTIONS"
        :key="option"
        clickable
        :outline="!selected.includes(option)"
        :color="selected.includes(option) ? 'primary' : undefined"
        :text-color="selected.includes(option) ? 'white' : undefined"
        @click="toggle(option)"
      >
        {{ t(`session.builder.focus.${option}`) }}
      </q-chip>
    </div>
    <div v-if="props.modelValue.length === 0" class="text-caption text-negative q-mt-xs">
      {{ t('session.builder.focusRequired') }}
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

defineOptions({ name: 'DevelopmentFocusSelector' })

const props = defineProps({
  modelValue: { type: Array, default: () => [] },
})

const emit = defineEmits(['update:modelValue'])

const { t } = useI18n()

const FOCUS_OPTIONS = [
  'technical',
  'physical',
  'cognitive',
  'matchRealism',
  'weakFoot',
  'set_pieces',
  'goalkeeping',
  'possession',
]

const selected = computed(() => props.modelValue)

function toggle(option) {
  const current = [...props.modelValue]
  const idx = current.indexOf(option)
  if (idx === -1) {
    current.push(option)
  } else {
    current.splice(idx, 1)
  }
  emit('update:modelValue', current)
}
</script>

<style scoped lang="scss">
.development-focus-selector {
  width: 100%;
}
</style>
