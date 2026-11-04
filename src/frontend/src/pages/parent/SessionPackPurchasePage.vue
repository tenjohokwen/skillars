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
              <div class="text-weight-medium">
                {{ t('booking.packs.sessionsBundle', { count: tier.sessionCount }) }}
              </div>
              <div class="text-caption text-grey">
                {{ formatPrice(tier.totalPrice) }} ·
                {{
                  t('booking.packs.pricePerSession', { price: formatPrice(tier.pricePerSession) })
                }}
              </div>
              <div
                v-if="tier.validityDays"
                class="text-caption text-grey"
                data-test="pack-validity"
              >
                {{ t('booking.packs.validFor', { days: tier.validityDays }) }}
              </div>
            </div>
            <q-radio
              :model-value="selected"
              :val="tier.packTierId"
              @update:model-value="selected = tier.packTierId"
            />
          </div>
        </q-card-section>
      </q-card>

      <div v-else class="text-body2 text-secondary q-py-md text-center">
        {{ t('booking.packs.noTierAvailable') }}
      </div>

      <q-banner v-if="purchaseError" class="bg-negative text-white q-mb-md">
        {{ purchaseError }}
        <template v-if="cardDeclined" #action>
          <q-btn
            flat
            :label="t('payment.card.updateCardCta')"
            data-test="purchase-update-card"
            @click="addCardDialog = true"
          />
        </template>
      </q-banner>

      <q-banner v-if="!hasCard" rounded class="q-mb-md" style="background: var(--surface-warning)">
        {{ t('payment.card.addCardPrompt') }}
        <template #action>
          <q-btn flat :label="t('payment.card.addCardCta')" @click="addCardDialog = true" />
        </template>
      </q-banner>

      <q-btn
        unelevated
        color="primary"
        class="full-width q-mt-md"
        :label="t('booking.packs.confirmPurchase')"
        :disable="!selected || !hasCard"
        :loading="purchasing"
        @click="confirmPurchase"
      />
    </template>

    <q-dialog v-model="addCardDialog">
      <q-card class="glass-card" style="min-width: 340px">
        <q-card-section>
          <div class="text-h6">{{ t('payment.card.title') }}</div>
        </q-card-section>
        <q-card-section class="q-pt-none">
          <PaymentMethodCard @saved="onCardSaved" />
        </q-card-section>
      </q-card>
    </q-dialog>
  </q-page>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useBookingStore } from 'src/stores/booking.store'
import { usePaymentStore } from 'src/stores/payment.store'
import SessionPackTracker from 'src/components/booking/SessionPackTracker.vue'
import PaymentMethodCard from 'src/components/payment/PaymentMethodCard.vue'
import { getCoachProfile } from 'src/api/marketplace.api'
import { fetchCoachSessionPackTiers } from 'src/api/payment.api'
import { parseApiError } from 'src/utils/errorHandler'

const route = useRoute()
const router = useRouter()
const { t, te, locale } = useI18n()
const bookingStore = useBookingStore()
const paymentStore = usePaymentStore()

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
// The card was refused (payment.cardDeclined): the banner offers to update it, in place.
const cardDeclined = ref(false)
const addCardDialog = ref(false)

// AC 5: gate the purchase control on a saved card — the backend falls back to it silently, so
// without this gate a parent with no card would only find out the purchase is impossible after
// clicking confirm.
const hasCard = computed(() => paymentStore.savedPaymentMethod?.hasCard ?? false)

function onCardSaved() {
  addCardDialog.value = false
  // A new card is a different purchase only when the last answer proved nothing was charged: while an earlier
  // attempt is unresolved the key must stay, or the retry could charge twice (skillars-deferred-169).
  if (!keyHeldForUnresolved) attemptKey = null
  // A new card answers the decline; the old message no longer applies.
  purchaseError.value = null
  cardDeclined.value = false
}

const CARD_DECLINED = 'payment.cardDeclined'

// What a failed purchase tells the parent. The error key lives under errorMsg (ErrorDto); the old
// read of response.data.message was always undefined, so every failure — a refused card included —
// showed the generic text. A known key is translated; payment.lifecycleFailure and anything else
// unknown stay generic (PaymentApiAdvice puts the raw code in errorMsg.message, never shown).
function purchaseFailureMessage(error) {
  const { errorKey } = parseApiError(error)
  if (errorKey === CARD_DECLINED) return t('payment.error.cardDeclined')
  if (errorKey && errorKey.startsWith('payment.') && te(`payment.error.${errorKey.slice(8)}`)) {
    return t(`payment.error.${errorKey.slice(8)}`)
  }
  return t('booking.packs.purchaseError')
}

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
  return new Intl.NumberFormat(locale.value, {
    style: 'currency',
    currency: pricingCurrency.value,
  }).format(Number(value))
}

// One key per purchase attempt (sent as Idempotency-Key): a double-click or a retry after a lost
// response replays the same charge instead of buying a second pack. Kept across a retry that never got
// a DEFINITE answer (a network error, a 5xx, "still being confirmed", any outcome that may have charged);
// cleared on success and on a definite no-charge answer (skillars-deferred-169). The server's one-open-attempt-per-parent
// index is the safety guarantee; these rules are UX.
let attemptKey = null
// True while the last answer left the outcome unknown, so a card save or pack change must not mint a new key.
let keyHeldForUnresolved = false

// A PaymentGatewayException (422) carries its code in errorMsg.errorKey; a ResponseStatusException (400/409) carries it
// in errorMsg.message (errorKey is the generic 'generic.requestError').
function errorCode(e) {
  const msg = e?.response?.data?.errorMsg
  return msg?.errorKey === 'generic.requestError' ? msg?.message : (msg?.errorKey ?? msg?.message)
}

// payment.cardDeclined is itself proof that nothing was charged (the gateway raises it only for a refused card).
const KEY_CLEARING_CODES = [
  'payment.noChargeMade',
  'payment.cardDeclined',
  'payment.invalidIdempotencyKey',
  'payment.idempotencyKeyReused',
]
// An answer that may have moved money: no answer at all, a 5xx, "still being confirmed", or a 422 that is not a definite refusal.
function outcomeMayHaveCharged(e, code) {
  const status = e?.response?.status
  if (!status || status >= 500) return true
  if (STILL_CONFIRMING_CODES.includes(code)) return true
  return (
    status === 422 &&
    (code === 'payment.lifecycleFailure' || code === 'payment.chargeOutcomeUnknown')
  )
}

const STILL_CONFIRMING_CODES = ['payment.attemptInProgress', 'payment.attemptUnresolved']

function newAttemptKey() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`
}

// A different pack choice is a different purchase.
watch(selected, () => {
  if (!keyHeldForUnresolved) attemptKey = null
})

async function confirmPurchase() {
  if (!selected.value || !playerId || purchasing.value) return
  purchasing.value = true
  purchaseError.value = null
  cardDeclined.value = false
  attemptKey ??= newAttemptKey()
  try {
    await bookingStore.purchasePack(playerId, selected.value, undefined, attemptKey)
    attemptKey = null
    keyHeldForUnresolved = false
    router.back()
  } catch (e) {
    const code = errorCode(e)
    if (KEY_CLEARING_CODES.includes(code)) {
      attemptKey = null
      keyHeldForUnresolved = false
    } else if (outcomeMayHaveCharged(e, code)) {
      keyHeldForUnresolved = true
    } else {
      // A definite refusal raised before any attempt row existed (403 age gate, 404/409 inactive tier or suspended coach,
      // 422 coach who cannot be paid yet, 400):
      // nothing was charged and nothing is open, so a card save or pack change may mint a fresh key.
      attemptKey = null
      keyHeldForUnresolved = false
    }
    if (STILL_CONFIRMING_CODES.includes(code)) {
      purchaseError.value = t('booking.packs.purchasePending')
    } else if (code === 'payment.noChargeMade') {
      // Also returned for a charge that was refunded: never say "nothing was charged".
      purchaseError.value = t('booking.packs.purchaseNotCompleted')
    } else {
      purchaseError.value = purchaseFailureMessage(e)
    }
    cardDeclined.value = code === CARD_DECLINED
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
      paymentStore.fetchSavedPaymentMethod(),
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
