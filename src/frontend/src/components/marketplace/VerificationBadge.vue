<template>
  <q-chip
    dense
    :color="badgeColor"
    :icon="badgeIcon"
    :label="badgeLabel"
    class="verification-badge text-white"
    size="sm"
  >
    <q-tooltip>{{ tooltipText }}</q-tooltip>
  </q-chip>
</template>

<script setup>
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

const props = defineProps({
  tier: { type: String, required: true }, // 'BASIC' | 'TRUSTED' | 'FEATURED'
})

const { t } = useI18n()

const badgeColor = computed(() => ({
  BASIC:    'grey-6',
  TRUSTED:  'blue-6',
  FEATURED: 'amber-8',
}[props.tier] ?? 'grey-6'))

const badgeIcon = computed(() => ({
  BASIC:    'verified',
  TRUSTED:  'verified_user',
  FEATURED: 'star',
}[props.tier] ?? 'verified'))

const badgeLabel  = computed(() => t(`marketplace.tier${props.tier}`))
const tooltipText = computed(() => t(`marketplace.tierTooltip${props.tier}`))
</script>
