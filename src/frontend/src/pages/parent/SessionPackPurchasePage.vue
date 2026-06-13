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
        v-if="perSessionPrice != null"
        flat
        bordered
        class="pack-option q-mb-sm cursor-pointer"
        :class="{ 'pack-option--selected': selected === 'single' }"
        @click="selected = 'single'"
      >
        <q-card-section>
          <div class="row items-center">
            <div class="col">
              <div class="text-weight-medium">{{ t('booking.packs.perSession') }}</div>
              <div class="text-caption text-grey">{{ t('booking.packs.pricePerSession', { price: formatPrice(perSessionPrice) }) }}</div>
            </div>
            <q-radio :model-value="selected" val="single" @update:model-value="selected = 'single'" />
          </div>
        </q-card-section>
      </q-card>

      <q-card
        v-for="pack in sessionPacks"
        :key="pack.id"
        flat
        bordered
        class="pack-option q-mb-sm cursor-pointer"
        :class="{ 'pack-option--selected': selected === pack.id }"
        @click="selected = pack.id"
      >
        <q-card-section>
          <div class="row items-center">
            <div class="col">
              <div class="text-weight-medium">{{ t('booking.packs.sessionsBundle', { count: pack.sessionCount }) }}</div>
              <div class="text-caption text-grey">{{ formatPrice(pack.totalPrice) }} · {{ t('booking.packs.pricePerSession', { price: formatPrice(pack.totalPrice / pack.sessionCount) }) }}</div>
            </div>
            <q-radio :model-value="selected" :val="pack.id" @update:model-value="selected = pack.id" />
          </div>
        </q-card-section>
      </q-card>

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

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const bookingStore = useBookingStore()

const coachId = route.params.coachId
const playerId = route.query.playerId

const coachName = ref('')
const sessionPacks = ref([])
const perSessionPrice = ref(null)
const pricingCurrency = ref('EUR')
const loadingPacks = ref(false)
const selected = ref(null)
const purchasing = ref(false)
const purchaseError = ref(null)

const currentCredits = computed(() =>
  bookingStore.creditsForCoach(coachId)
)

const currentSessionCount = computed(() => {
  const activePacks = bookingStore.sessionPacks.filter(
    (p) => p.coachId === coachId && p.status === 'ACTIVE'
  )
  return activePacks.length > 0 ? activePacks[0].sessionCount : 0
})

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
    const request = {
      coachId,
      sessionPackId: selected.value === 'single' ? null : selected.value,
    }
    await bookingStore.purchasePack(playerId, request)
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
    const [coachRes] = await Promise.all([
      getCoachProfile(coachId),
      playerId ? bookingStore.loadPlayerPacks(playerId) : Promise.resolve(),
    ])
    const profile = coachRes.data
    coachName.value = profile.displayName ?? ''
    sessionPacks.value = profile.sessionPacks ?? []
    perSessionPrice.value = profile.perSessionPrice ?? null
    pricingCurrency.value = profile.currency ?? 'EUR'
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
