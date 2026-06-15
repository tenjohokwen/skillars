<template>
  <q-chip :class="chipClass" dense>{{ label }}</q-chip>
</template>

<script setup>
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

const props = defineProps({ status: { type: String, required: true } })
const { t } = useI18n()

const statusMap = {
  REQUESTED: { key: 'booking.requests.statusRequested', cls: 'chip--warning' },
  ACCEPTED: { key: 'booking.requests.statusAccepted', cls: 'chip--primary' },
  CONFIRMED: { key: 'booking.requests.statusConfirmed', cls: 'chip--primary' },
  UPCOMING: { key: 'booking.requests.statusUpcoming', cls: 'chip--primary' },
  DECLINED: { key: 'booking.requests.statusDeclined', cls: 'chip--error' },
  COMPLETED: { key: 'booking.requests.statusCompleted', cls: 'chip--neutral' },
  CANCELLED: { key: 'booking.requests.statusCancelled', cls: 'chip--neutral' },
  DISPUTED: { key: 'booking.requests.statusDisputed', cls: 'chip--neutral' },
}

const label = computed(() => {
  const entry = statusMap[props.status]
  return entry ? t(entry.key) : props.status
})

const chipClass = computed(() => statusMap[props.status]?.cls ?? 'chip--neutral')
</script>

<style scoped>
.chip--warning {
  background-color: var(--accent-warning);
  color: #fff;
}
.chip--primary {
  background-color: var(--accent-primary);
  color: #fff;
}
.chip--error {
  background-color: var(--color-error);
  color: #fff;
}
.chip--neutral {
  background-color: var(--text-secondary);
  color: #fff;
}
</style>
