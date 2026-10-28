<template>
  <q-page class="q-pa-md">
    <div class="glass-card q-pa-lg q-mb-md">
      <div class="text-h6 q-mb-xs">{{ t('auth.parent.dashboardTitle') }}</div>
      <div class="text-meta q-mb-lg">{{ t('auth.parent.dashboardBody') }}</div>

      <div v-if="playersLoading" class="flex flex-center q-py-xl">
        <q-spinner-dots size="48px" style="color: var(--accent-primary)" />
      </div>

      <q-banner v-else-if="hasError" class="parent-dashboard__error q-mb-md" rounded>
        {{ errorMessage }}
      </q-banner>

      <template v-else>
        <div class="text-subtitle1 q-mb-md">{{ t('auth.parent.playersTitle') }}</div>

        <div class="parent-dashboard__grid">
          <!-- Each child card opens that child's hub (ParentPlayerPortalPage); the chips below are
               one-tap shortcuts to the hub's most-used destinations. The chips are siblings of
               the hub link, not nested in it — an <a> may not contain another <a>. -->
          <div
            v-for="player in players"
            :key="player.id"
            class="glass-card soft-hover parent-dashboard__player-card"
            data-testid="child-card"
          >
            <router-link
              :to="childLinks(player.id).sessions"
              class="parent-dashboard__player-link"
              data-testid="child-card-hub-link"
              :aria-label="t('parent.hub.openHub', { name: player.name })"
            >
              <div class="text-card-title">{{ player.name }}</div>
              <div class="text-meta q-mt-xs">
                {{ player.ageTierLabel }}
                <template v-if="player.position">
                  &middot; {{ t('player.positions.' + player.position) }}
                </template>
              </div>
            </router-link>
            <div class="row q-gutter-xs q-mt-sm">
              <q-btn
                v-bind="chipProps"
                icon="insights"
                :to="childLinks(player.id).development"
                :label="t('parent.hub.development')"
              />
              <q-btn
                v-bind="chipProps"
                icon="confirmation_number"
                :to="childLinks(player.id).packs"
                :label="t('parent.hub.packs')"
              />
              <q-btn
                v-bind="chipProps"
                icon="sports_soccer"
                :to="childLinks(player.id).lockerRoom"
                :label="t('parent.hub.lockerRoom')"
              />
            </div>
          </div>

          <router-link
            to="/parent/create-player"
            class="glass-card soft-hover parent-dashboard__add-card"
          >
            <q-icon name="add" size="28px" />
            <div class="text-card-title q-mt-sm">{{ t('auth.parent.addPlayerCta') }}</div>
          </router-link>
        </div>
      </template>
    </div>

    <div class="glass-card q-pa-lg">
      <div class="text-subtitle1 q-mb-md">{{ t('auth.parent.quickLinksTitle') }}</div>

      <div class="parent-dashboard__grid">
        <router-link to="/parent/bookings" class="glass-card soft-hover parent-dashboard__tile">
          <q-icon name="event" size="24px" style="color: var(--accent-primary)" />
          <div class="text-card-title q-mt-sm">{{ t('auth.parent.upcomingSessionsTitle') }}</div>
          <div class="text-meta q-mt-xs">
            <q-spinner-dots v-if="bookingStore.bookingsLoading" size="18px" />
            <template v-else-if="bookingStore.bookingsError">{{
              t('auth.parent.tileUnavailable')
            }}</template>
            <template v-else>{{
              t('auth.parent.upcomingSessionsCount', { count: upcomingSessionsCount })
            }}</template>
          </div>
        </router-link>

        <router-link to="/marketplace" class="glass-card soft-hover parent-dashboard__tile">
          <q-icon name="search" size="24px" style="color: var(--accent-primary)" />
          <div class="text-card-title q-mt-sm">{{ t('auth.parent.browseCoachesTitle') }}</div>
          <div class="text-meta q-mt-xs">{{ t('auth.parent.browseCoachesBody') }}</div>
        </router-link>

        <router-link
          to="/parent/credit-wallet"
          class="glass-card soft-hover parent-dashboard__tile"
        >
          <q-icon name="account_balance_wallet" size="24px" style="color: var(--accent-primary)" />
          <div class="text-card-title q-mt-sm">{{ t('auth.parent.creditWalletTitle') }}</div>
          <div class="text-meta q-mt-xs">
            <q-spinner-dots v-if="creditLoading" size="18px" />
            <template v-else-if="creditError">{{ t('auth.parent.tileUnavailable') }}</template>
            <template v-else>{{ formattedBalance }}</template>
          </div>
        </router-link>
      </div>
    </div>
  </q-page>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { usePlayerStore } from 'src/stores/playerStore'
import { useBookingStore } from 'src/stores/booking.store'
import { usePaymentStore } from 'src/stores/payment.store'
import { useErrorHandler } from 'src/composables/useErrorHandler'
import { parentChildLinks as childLinks } from 'src/utils/parentChildLinks'

const { t } = useI18n()
const playerStore = usePlayerStore()
const bookingStore = useBookingStore()
const paymentStore = usePaymentStore()
const { setError, hasError, errorMessage } = useErrorHandler()

const players = computed(() => playerStore.players)

// Shared look of the per-child quick-action chips.
const chipProps = {
  outline: true,
  rounded: true,
  dense: true,
  noCaps: true,
  size: 'sm',
  color: 'primary',
  'data-testid': 'child-chip',
}
const playersLoading = ref(true)

const UPCOMING_STATUSES = ['CONFIRMED', 'UPCOMING']
const upcomingSessionsCount = computed(
  () => bookingStore.parentBookings.filter((b) => UPCOMING_STATUSES.includes(b.status)).length,
)

const creditLoading = ref(true)
const creditError = ref(false)
const formattedBalance = computed(() => {
  const balance = paymentStore.creditBalance?.balance
  return balance != null ? `€${Number(balance).toFixed(2)}` : t('auth.parent.tileUnavailable')
})

onMounted(async () => {
  try {
    await playerStore.fetchPlayers()
  } catch (err) {
    setError(err)
  } finally {
    playersLoading.value = false
  }

  bookingStore.loadParentBookings()

  paymentStore
    .fetchCreditBalance()
    .catch(() => {
      creditError.value = true
    })
    .finally(() => {
      creditLoading.value = false
    })
})
</script>

<style lang="scss" scoped>
.parent-dashboard__error {
  background: rgba(255, 95, 122, 0.12) !important;
  color: var(--accent-danger) !important;
  border-radius: 12px !important;
}

.parent-dashboard__grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 16px;
}

.parent-dashboard__player-card {
  padding: 20px;
}

.parent-dashboard__player-link {
  display: block;
  text-decoration: none;
  color: var(--text-primary);
}

.parent-dashboard__add-card {
  padding: 20px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  text-decoration: none;
  color: var(--accent-primary);
  min-height: 88px;
  border-style: dashed;
}

.parent-dashboard__tile {
  padding: 20px;
  text-decoration: none;
  color: var(--text-primary);
  min-height: 88px;
}
</style>
