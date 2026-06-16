<template>
  <div class="session-dna-chart">
    <!-- Radar stub placeholder -->
    <svg
      class="session-dna-chart__radar"
      viewBox="0 0 120 120"
      xmlns="http://www.w3.org/2000/svg"
      aria-hidden="true"
    >
      <circle cx="60" cy="60" r="50" fill="none" stroke="var(--border-subtle)" stroke-width="2" />
      <circle cx="60" cy="60" r="35" fill="none" stroke="var(--border-subtle)" stroke-width="1" />
      <circle cx="60" cy="60" r="20" fill="none" stroke="var(--border-subtle)" stroke-width="1" />
      <polygon
        points="60,20 90,75 30,75"
        fill="var(--accent-primary)"
        fill-opacity="0.2"
        stroke="var(--accent-primary)"
        stroke-width="2"
      />
    </svg>

    <div class="session-dna-chart__label text-body2" style="color: var(--text-secondary)">
      {{ t('booking.completion.sessionIntelligencePlaceholder', 'Session intelligence will appear here') }}
    </div>

    <!-- Confirmation indicator -->
    <div class="session-dna-chart__confirmed">
      <q-icon name="check_circle" color="positive" size="32px" />
      <span class="text-body1 q-ml-sm">{{ t('booking.completion.summaryTitle') }}</span>
    </div>
  </div>
</template>

<script setup>
import { useI18n } from 'vue-i18n'

defineProps({
  bookingId: { type: String, default: null },
  variant: { type: String, default: 'compact' },
})

const { t } = useI18n()

// TODO(5.x): Wire to SLU engine once platform.development module is built
</script>

<style lang="scss" scoped>
.session-dna-chart {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  padding: 24px;
}

.session-dna-chart__radar {
  width: 160px;
  height: 160px;
}

.session-dna-chart__confirmed {
  display: flex;
  align-items: center;
  margin-top: 8px;
}
</style>
