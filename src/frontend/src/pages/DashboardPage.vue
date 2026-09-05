<template>
  <q-page>
    <TimezoneNotice
      v-if="dashboardPitchTimezone && !authStore.timezoneNoticeDismissed"
      :pitch-timezone="dashboardPitchTimezone"
    />
    <div class="app-page fade-in">
      <!-- Page header -->
      <div class="page-header q-mb-xl">
        <div class="text-page-title">{{ $t('dashboard.title') }}</div>
        <div class="text-meta">{{ $t('dashboard.welcomeBack', { name: username }) }}</div>
      </div>

      <!-- Metric cards -->
      <div class="metrics-row q-mb-xl">
        <div v-for="metric in metrics" :key="metric.label" class="glass-card metric-card">
          <div class="text-label q-mb-sm">{{ metric.label }}</div>
          <div class="metric-value gradient-text">{{ metric.value }}</div>
          <div class="text-meta q-mt-xs">{{ metric.sub }}</div>
        </div>
      </div>

      <!-- Status card -->
      <div class="glass-card--static status-card">
        <div class="status-inner">
          <q-icon name="check_circle" size="40px" style="color: var(--accent-primary)" />
          <div>
            <div class="text-card-title">{{ $t('dashboard.loggedIn') }}</div>
            <div class="text-meta">{{ $t('dashboard.operational') }}</div>
          </div>
        </div>
      </div>
    </div>
  </q-page>
</template>

<script setup>
import { computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useBookingStore } from 'src/stores/booking.store'
import { useAuthStore } from 'src/stores/auth.store'
import { readUserDisplayName } from 'src/utils/sessionCookies'
import TimezoneNotice from 'src/components/booking/TimezoneNotice.vue'

const { t } = useI18n()

// readUserDisplayName() returns null for a blank / sentinel `user` cookie value
// (skillars-deferred-90 AC2), so the greeting falls through to the generic default. `t` must
// be declared above this: it only survived being referenced before its own declaration
// because computed getters are lazy (review, skillars-deferred-92 chunk 4).
const username = computed(() => readUserDisplayName() ?? t('dashboard.defaultUser'))

const bookingStore = useBookingStore()
const authStore = useAuthStore()

onMounted(async () => {
  if (authStore.isParent && bookingStore.parentBookings.length === 0) {
    await bookingStore.loadParentBookings()
  }
})

const dashboardPitchTimezone = computed(() => {
  return bookingStore.parentBookings[0]?.canonicalTimezone ?? null
})

// computed, not a plain array: the labels must re-render when the locale changes.
const metrics = computed(() => [
  { label: t('dashboard.metricSessionsToday'), value: '—', sub: t('dashboard.metricNoData') },
  { label: t('dashboard.metricActiveUsers'), value: '—', sub: t('dashboard.metricNoData') },
  { label: t('dashboard.metricUptime'), value: '99.9%', sub: t('dashboard.metricLast30Days') },
  { label: t('dashboard.metricResponseTime'), value: '—', sub: t('dashboard.metricAverageMs') },
])
</script>

<style lang="scss" scoped>
.page-header {
  border-bottom: 1px solid var(--border-soft);
  padding-bottom: 24px;
}

.metric-card {
  padding: 24px;
}

.metric-value {
  font-size: 40px;
  font-weight: 800;
  line-height: 1;
  font-family: 'Inter', sans-serif;
}

.status-card {
  padding: 24px 28px;
}

.status-inner {
  display: flex;
  align-items: center;
  gap: 16px;
}
</style>
