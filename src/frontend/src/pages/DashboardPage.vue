<template>
  <q-page>
    <TimezoneNotice
      v-if="dashboardPitchTimezone && !authStore.timezoneNoticeDismissed"
      :pitch-timezone="dashboardPitchTimezone"
    />
    <div class="app-page fade-in">

      <!-- Page header -->
      <div class="page-header q-mb-xl">
        <div class="text-page-title">Dashboard</div>
        <div class="text-meta">Welcome back, {{ username }}</div>
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
            <div class="text-card-title">You are successfully logged in</div>
            <div class="text-meta">All systems are operational.</div>
          </div>
        </div>
      </div>

    </div>
  </q-page>
</template>

<script setup>
import { computed, onMounted } from 'vue'
import { useBookingStore } from 'src/stores/booking.store'
import { useAuthStore } from 'src/stores/auth.store'
import TimezoneNotice from 'src/components/booking/TimezoneNotice.vue'

function getUsernameFromCookie() {
  const match = document.cookie.match(/user=([^;]+)/);
  if (match) {
    try { return decodeURIComponent(match[1]); } catch { return match[1]; }
  }
  return 'User';
}

const username = computed(() => getUsernameFromCookie())

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

const metrics = [
  { label: 'Sessions Today', value: '—', sub: 'No data yet' },
  { label: 'Active Users', value: '—', sub: 'No data yet' },
  { label: 'Uptime', value: '99.9%', sub: 'Last 30 days' },
  { label: 'Response Time', value: '—', sub: 'Average ms' },
];
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
