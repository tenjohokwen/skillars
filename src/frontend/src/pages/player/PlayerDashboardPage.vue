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

        <!-- fix/player-wallet-cashout: opens the player's own credit wallet (balance + cash-out), as the
             parent dashboard's wallet tile does; the statement stays one drawer entry away. -->
        <router-link
          :to="{ name: 'parent-credit-wallet' }"
          class="glass-card soft-hover player-dashboard__tile"
          data-test="wallet-tile"
        >
          <q-icon name="account_balance_wallet" size="24px" style="color: var(--accent-primary)" />
          <div class="text-card-title q-mt-sm">{{ t('player.dashboard.creditWalletTitle') }}</div>
          <div class="text-meta q-mt-xs">
            <q-spinner-dots v-if="creditPending" size="18px" />
            <template v-else-if="paymentStore.error.creditBalance">{{
              t('player.dashboard.tileUnavailable')
            }}</template>
            <template v-else>{{ formattedBalance }}</template>
          </div>
        </router-link>

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

        <!-- S-27: Stripe Identity age verification; shown once the feature is on (or once verified). -->
        <router-link
          v-if="ageTileVisible"
          :to="{ name: 'player-age-verification' }"
          class="glass-card soft-hover player-dashboard__tile"
          data-test="age-verification-tile"
        >
          <q-icon
            :name="ageStatus.state === 'VERIFIED' ? 'verified' : 'badge'"
            size="24px"
            style="color: var(--accent-primary)"
          />
          <div class="text-card-title q-mt-sm">
            {{ t('player.ageVerification.dashboardTitle') }}
          </div>
          <div class="text-meta q-mt-xs">{{ ageTileText }}</div>
        </router-link>
      </div>
    </div>
  </q-page>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useBookingStore } from 'src/stores/booking.store'
import { usePaymentStore } from 'src/stores/payment.store'
import { ageVerificationApi } from 'src/api/ageVerification.api'

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
// "Unavailable" ERROR copy via formattedBalance's null fallback. ParentDashboardPage.vue
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

// S-27: the tile is a nicety — a failed status call just leaves it out; the gates still redirect on demand.
const ageStatus = ref(null)
const ageTileVisible = computed(
  () =>
    !!ageStatus.value?.applicable &&
    (ageStatus.value.enabled || ageStatus.value.state === 'VERIFIED'),
)
const ageTileText = computed(() => {
  switch (ageStatus.value?.state) {
    case 'VERIFIED':
      return t('player.ageVerification.dashboardVerified')
    case 'FAILED':
      return t('player.ageVerification.dashboardFailed')
    case 'BLOCKED':
      return t('player.ageVerification.blocked')
    default:
      return t('player.ageVerification.dashboardPending')
  }
})

async function loadAgeStatus() {
  try {
    ageStatus.value = (await ageVerificationApi.getStatus()) ?? null
  } catch {
    ageStatus.value = null
  }
}

onMounted(() => {
  bookingStore.loadParentBookings()
  paymentStore.fetchCreditBalance()
  loadAgeStatus()
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
