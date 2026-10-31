<template>
  <q-chip
    dense
    size="sm"
    :outline="!onRecord"
    :color="onRecord ? 'positive' : 'grey-6'"
    :text-color="onRecord ? 'white' : undefined"
    :icon="onRecord ? 'mdi-shield-check' : 'mdi-shield-outline'"
    :label="label"
    class="safeguarding-badge"
    :data-testid="onRecord ? 'safeguarding-badge-on' : 'safeguarding-badge-none'"
  >
    <q-tooltip>{{
      onRecord ? t('safeguarding.badge.tooltipOn') : t('safeguarding.badge.tooltipNone')
    }}</q-tooltip>
  </q-chip>
</template>

<script setup>
// S-09: the neutral, factual public indicator. Shown for every coach (a check on record, or a plain
// "no check on record"), never in a warning colour: the check is optional and parents decide for themselves.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { formatMonthYear } from 'src/utils/safeguarding'

const props = defineProps({
  onRecord: { type: Boolean, default: false },
  validUntil: { type: String, default: null }, // ISO date (yyyy-MM-dd) from the API
})

const { t } = useI18n()

const label = computed(() =>
  props.onRecord
    ? t('safeguarding.badge.onRecord', { date: formatMonthYear(props.validUntil) })
    : t('safeguarding.badge.none'),
)
</script>
