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
  PAYMENT_PENDING: { key: 'booking.requests.statusPaymentPending', cls: 'chip--warning' },
  CONFIRMED: { key: 'booking.requests.statusConfirmed', cls: 'chip--primary' },
  UPCOMING: { key: 'booking.requests.statusUpcoming', cls: 'chip--primary' },
  IN_PROGRESS: { key: 'booking.requests.statusInProgress', cls: 'chip--primary' },
  PAUSED: { key: 'booking.requests.statusPaused', cls: 'chip--warning' },
  COMPLETED_PENDING_CONFIRMATION: { key: 'booking.requests.statusCompletingPending', cls: 'chip--warning' },
  DECLINED: { key: 'booking.requests.statusDeclined', cls: 'chip--error' },
  COMPLETED: { key: 'booking.requests.statusCompleted', cls: 'chip--neutral' },
  CANCELLED: { key: 'booking.requests.statusCancelled', cls: 'chip--neutral' },
  CANCELLED_PARENT: { key: 'booking.requests.statusCancelledParent', cls: 'chip--neutral' },
  CANCELLED_COACH: { key: 'booking.requests.statusCancelledCoach', cls: 'chip--neutral' },
  NO_SHOW_PLAYER: { key: 'booking.requests.statusNoShowPlayer', cls: 'chip--error' },
  NO_SHOW_COACH: { key: 'booking.requests.statusNoShowCoach', cls: 'chip--error' },
  DISPUTED: { key: 'booking.requests.statusDisputed', cls: 'chip--neutral' },
  REFUND_PENDING: { key: 'booking.requests.statusRefundPending', cls: 'chip--warning' },
  REFUNDED: { key: 'booking.requests.statusRefunded', cls: 'chip--neutral' },
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
