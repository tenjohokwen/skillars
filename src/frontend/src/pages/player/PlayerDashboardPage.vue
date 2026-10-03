<template>
  <q-page class="q-pa-md">
    <div class="glass-card q-pa-lg">
      <div class="text-h6 q-mb-xs">{{ t('player.dashboard.title') }}</div>
      <div class="text-meta q-mb-lg">{{ t('player.dashboard.body') }}</div>

      <div class="player-dashboard__grid">
        <router-link to="/parent/bookings" class="glass-card soft-hover player-dashboard__tile">
          <q-icon name="event" size="24px" style="color: var(--accent-primary)" />
          <div class="text-card-title q-mt-sm">
            {{ t('player.dashboard.upcomingSessionsTitle') }}
          </div>
          <div class="text-meta q-mt-xs">
            <q-spinner-dots v-if="bookingStore.bookingsLoading" size="18px" />
            <template v-else-if="bookingStore.bookingsError">{{
              t('player.dashboard.tileUnavailable')
            }}</template>
            <template v-else>{{
              t('player.dashboard.upcomingSessionsCount', { count: upcomingSessionsCount })
            }}</template>
          </div>
        </router-link>

        <div class="glass-card player-dashboard__tile">
          <q-icon name="account_balance_wallet" size="24px" style="color: var(--accent-primary)" />
          <div class="text-card-title q-mt-sm">{{ t('player.dashboard.creditWalletTitle') }}</div>
          <div class="text-meta q-mt-xs">
            <q-spinner-dots v-if="creditPending" size="18px" />
            <template v-else-if="paymentStore.error.creditBalance">{{
              t('player.dashboard.tileUnavailable')
            }}</template>
            <template v-else>{{ formattedBalance }}</template>
          </div>
        </div>

        <router-link to="/parent/bookings" class="glass-card soft-hover player-dashboard__tile">
          <q-icon name="task_alt" size="24px" style="color: var(--accent-primary)" />
          <div class="text-card-title q-mt-sm">{{ t('player.dashboard.approvalsTitle') }}</div>
          <div class="text-meta q-mt-xs">
            <q-spinner-dots v-if="bookingStore.bookingsLoading" size="18px" />
            <template v-else-if="bookingStore.bookingsError">{{
              t('player.dashboard.tileUnavailable')
            }}</template>
            <template v-else-if="approvalsCount > 0">{{
              t('player.dashboard.approvalsPending', { count: approvalsCount })
            }}</template>
            <template v-else>{{ t('player.dashboard.approvalsNone') }}</template>
          </div>
        </router-link>
      </div>
    </div>
  </q-page>
</template>

<script setup>
import { computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useBookingStore } from 'src/stores/booking.store'
import { usePaymentStore } from 'src/stores/payment.store'

const { t } = useI18n()
const bookingStore = useBookingStore()
const paymentStore = usePaymentStore()

const UPCOMING_STATUSES = ['CONFIRMED', 'UPCOMING']
const upcomingSessionsCount = computed(
  () => bookingStore.parentBookings.filter((b) => UPCOMING_STATUSES.includes(b.status)).length,
)
// skillars-deferred-141 AC4: "pending approvals" for a self-registered player reads as the
// player's own bookings still awaiting the coach's decision (REQUESTED), not video-approval
// consent (a parent-over-a-minor concept that doesn't apply to this persona).
const approvalsCount = computed(
  () => bookingStore.parentBookings.filter((b) => b.status === 'REQUESTED').length,
)

// skillars-deferred-141 code review: `paymentStore.loading.creditBalance` initialises to FALSE
// (payment.store.js emptyKeyedState()) with `creditBalance` still null, and onMounted runs after the
// first render — so gating the spinner on the store flag alone made the tile's first paint render the
// "Unavailable" ERROR copy via formattedBalance's null fallback. ParentDashboardPlaceholderPage.vue
// avoids this by seeding a local `creditLoading = ref(true)`; treating "no balance yet and no error"
// as still-pending is the equivalent without duplicating store state.
const creditPending = computed(
  () =>
    paymentStore.loading.creditBalance ||
    (paymentStore.creditBalance == null && !paymentStore.error.creditBalance),
)

const formattedBalance = computed(() => {
  const balance = paymentStore.creditBalance?.balance
  return balance != null ? `€${Number(balance).toFixed(2)}` : t('player.dashboard.tileUnavailable')
})

onMounted(() => {
  bookingStore.loadParentBookings()
  paymentStore.fetchCreditBalance()
})
</script>

<style lang="scss" scoped>
.player-dashboard__grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 16px;
}

.player-dashboard__tile {
  padding: 20px;
  text-decoration: none;
  color: var(--text-primary);
  min-height: 88px;
}
</style>
