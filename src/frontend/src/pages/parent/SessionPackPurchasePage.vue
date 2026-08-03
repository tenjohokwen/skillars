<template>
  <q-page class="q-pa-md">
    <div class="row items-center q-mb-md">
      <q-btn flat round icon="arrow_back" @click="router.back()" />
      <div class="text-h6 q-ml-sm">
        {{ t('booking.packs.purchaseTitle', { coachName: coachName }) }}
      </div>
    </div>

    <SessionPackTracker
      :credits-remaining="currentCredits"
      :session-count="currentSessionCount"
      class="q-mb-md"
    />

    <div v-if="loadingPacks" class="text-center q-py-lg">
      <q-spinner size="40px" />
    </div>

    <template v-else>
      <q-card
        v-if="tier"
        flat
        bordered
        class="pack-option q-mb-sm cursor-pointer"
        :class="{ 'pack-option--selected': selected === tier.packTierId }"
        @click="selected = tier.packTierId"
      >
        <q-card-section>
          <div class="row items-center">
            <div class="col">
              <div class="text-weight-medium">{{ t('booking.packs.sessionsBundle', { count: tier.sessionCount }) }}</div>
              <div class="text-caption text-grey">{{ formatPrice(tier.totalPrice) }} · {{ t('booking.packs.pricePerSession', { price: formatPrice(tier.pricePerSession) }) }}</div>
            </div>
            <q-radio :model-value="selected" :val="tier.packTierId" @update:model-value="selected = tier.packTierId" />
          </div>
        </q-card-section>
      </q-card>

      <div v-else class="text-body2 text-secondary q-py-md text-center">
        {{ t('booking.packs.noTierAvailable') }}
      </div>

      <q-banner v-if="purchaseError" class="bg-negative text-white q-mb-md">
        {{ purchaseError }}
      </q-banner>

      <q-btn
        unelevated
        color="primary"
        class="full-width q-mt-md"
        :label="t('booking.packs.confirmPurchase')"
        :disable="!selected"
        :loading="purchasing"
        @click="confirmPurchase"
      />
    </template>
  </q-page>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useBookingStore } from 'src/stores/booking.store'
import SessionPackTracker from 'src/components/booking/SessionPackTracker.vue'
import { getCoachProfile } from 'src/api/marketplace.api'
import { fetchCoachSessionPackTiers } from 'src/api/payment.api'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const bookingStore = useBookingStore()

const coachId = route.params.coachId
const playerId = route.query.playerId

const coachName = ref('')
// Story 11.2: the marketplace's coach-profile "sessionPacks" bundle catalog (used here
// pre-cutover) has no usable id field on its DTO and was never wired to a real purchase flow —
// this page now sources its single purchasable tier from the payment module's
// session-pack-tiers endpoint instead (Story 7.1's model: one active tier per coach).
// The legacy "buy a single session credit" option has no equivalent on the new path (the new
// purchase endpoint always requires a packTierId) — pay-per-session is a booking-time choice
// now (no pack selected when booking), not a pre-purchasable product, so that option is removed.
const tier = ref(null)
const pricingCurrency = ref('EUR')
const loadingPacks = ref(false)
const selected = ref(null)
const purchasing = ref(false)
const purchaseError = ref(null)

// A player+coach pair can now have multiple simultaneously-active packs — show the
// soonest-expiring one here (see decision in ParentPlayerPortalPage.vue for the same tiebreak).
const currentPack = computed(() => {
  const activePacks = bookingStore.sessionPacks.filter(
    (p) => p.coachId === coachId && p.status === 'ACTIVE',
  )
  if (activePacks.length === 0) return null
  return activePacks.reduce((soonest, p) =>
    new Date(p.expiresAt) < new Date(soonest.expiresAt) ? p : soonest,
  )
})
const currentCredits = computed(() => currentPack.value?.creditsRemaining ?? 0)
const currentSessionCount = computed(() => currentPack.value?.sessionCount ?? 0)

function formatPrice(value) {
  return new Intl.NumberFormat(undefined, {
    style: 'currency',
    currency: pricingCurrency.value,
  }).format(Number(value))
}

async function confirmPurchase() {
  if (!selected.value || !playerId) return
  purchasing.value = true
  purchaseError.value = null
  try {
    await bookingStore.purchasePack(playerId, selected.value)
    router.back()
  } catch (e) {
    purchaseError.value = e?.response?.data?.message ?? t('booking.packs.purchaseError')
  } finally {
    purchasing.value = false
  }
}

onMounted(async () => {
  loadingPacks.value = true
  try {
    const [coachRes, tierRes] = await Promise.all([
      getCoachProfile(coachId),
      fetchCoachSessionPackTiers(coachId).catch(() => null),
      playerId ? bookingStore.loadPlayerPacks(playerId) : Promise.resolve(),
    ])
    coachName.value = coachRes?.displayName ?? ''
    pricingCurrency.value = coachRes?.currency ?? 'EUR'
    tier.value = tierRes ?? null
  } finally {
    loadingPacks.value = false
  }
})
</script>

<style lang="scss" scoped>
.pack-option {
  border-color: var(--border-subtle);
  transition: border-color 0.2s;

  &--selected {
    border-color: var(--accent-primary);
  }
}
</style>
