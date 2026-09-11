<template>
  <div class="payment-method-card glass-card q-pa-md">
    <div v-if="loadingInitial" class="flex flex-center q-py-md">
      <q-spinner size="32px" />
    </div>

    <div v-else-if="stripeUnavailable" class="text-body2 text-secondary q-mb-md">
      <div class="q-mb-md">{{ t('payment.card.unavailable') }}</div>
      <q-btn
        flat
        no-caps
        :label="t('common.retry')"
        @click="loadStripeConfig({ isRetry: true })"
        :loading="retrying"
        :disable="retrying"
      />
    </div>

    <template v-else>
      <div v-if="savedCard?.hasCard && !editing" class="row items-center q-gutter-sm">
        <q-icon name="mdi-credit-card-outline" size="sm" color="primary" />
        <span class="text-body2">
          {{
            savedCard.brand
              ? t('payment.card.savedLabel', {
                  brand: savedCard.brand,
                  last4: savedCard.last4,
                  expMonth: savedCard.expMonth,
                  expYear: savedCard.expYear,
                })
              : t('payment.card.detailsUnavailable')
          }}
        </span>
        <q-btn
          flat
          dense
          size="sm"
          color="primary"
          :label="t('payment.card.replaceCard')"
          @click="startEditing"
        />
      </div>

      <div v-else>
        <div v-if="!savedCard?.hasCard" class="text-body2 q-mb-sm">
          {{ t('payment.card.addCardPrompt') }}
        </div>
        <div ref="cardElementRef" class="card-element q-pa-sm q-mb-sm"></div>
        <q-banner v-if="cardError" dense rounded class="bg-negative text-white q-mb-sm">
          {{ cardError }}
        </q-banner>
        <div class="row q-gutter-sm">
          <q-btn
            unelevated
            color="primary"
            :label="t('payment.card.save')"
            :loading="saving"
            :disable="!elementsReady"
            @click="submit"
          />
          <q-btn
            v-if="savedCard?.hasCard"
            flat
            :label="t('common.cancel')"
            :disable="saving"
            @click="cancelEditing"
          />
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import { loadStripe } from '@stripe/stripe-js'
import { usePaymentStore } from 'src/stores/payment.store'
import { createSetupIntent, savePaymentMethod, confirmCardSetup } from 'src/api/payment.api'

const { t } = useI18n()
const $q = useQuasar()
const paymentStore = usePaymentStore()
const emit = defineEmits(['saved'])

const cardElementRef = ref(null)
const loadingInitial = ref(true)
const retrying = ref(false)
const stripeUnavailable = ref(false)
const elementsReady = ref(false)
const editing = ref(false)
const saving = ref(false)
const cardError = ref(null)

let stripe = null
let elements = null
let cardElement = null

const savedCard = computed(() => paymentStore.savedPaymentMethod)
// AC 2: the form (and the Stripe Elements mount point) shows whenever there is no saved card
// yet, or the parent explicitly asked to replace the saved one.
const showForm = computed(
  () => !stripeUnavailable.value && (editing.value || !savedCard.value?.hasCard),
)

async function ensureStripeReady() {
  if (stripe) return true
  // AC 2: never call loadStripe with a null/undefined/empty key — show the unavailable state
  // and disable submit instead of letting Stripe.js throw.
  const key = paymentStore.stripeConfig?.publishableKey
  if (!key) {
    stripeUnavailable.value = true
    return false
  }
  try {
    stripe = await loadStripe(key)
    elements = stripe ? stripe.elements() : null
  } catch {
    stripe = null
    elements = null
  }
  if (!stripe || !elements) {
    stripeUnavailable.value = true
    return false
  }
  return true
}

let mountGeneration = 0

async function mountCardElement() {
  const generation = ++mountGeneration
  const ready = await ensureStripeReady()
  if (generation !== mountGeneration) return
  if (!ready) return
  await nextTick()
  if (generation !== mountGeneration) return
  if (!cardElementRef.value || cardElement) return
  try {
    cardElement = elements.create('card')
    cardElement.mount(cardElementRef.value)
    elementsReady.value = true
  } catch {
    cardElement = null
    stripeUnavailable.value = true
  }
}

function unmountCardElement() {
  mountGeneration++
  cardElement?.unmount()
  cardElement = null
  elementsReady.value = false
}

watch(showForm, (show) => {
  if (show) mountCardElement()
  else unmountCardElement()
})

function startEditing() {
  cardError.value = null
  editing.value = true
}

function cancelEditing() {
  cardError.value = null
  editing.value = false
}

// skillars-deferred-103 AC9: shared init used by onMounted and the "Try again" button in the
// stripeUnavailable state. On a retry it drives the button's own spinner (retrying) rather than
// loadingInitial, so the unavailable block with the button stays visible while the refetch runs;
// a failed refetch re-raises stripeUnavailable so the affordance stays put.
async function loadStripeConfig({ isRetry = false } = {}) {
  if (isRetry) retrying.value = true
  // skillars-deferred-109 AC1.1: do NOT clear stripeUnavailable here. Clearing it before the
  // fetches resolve flips showForm true synchronously, which queues the watch(showForm) job; that
  // job runs mid-await, ensureStripeReady reads the still-null publishableKey, re-raises
  // stripeUnavailable, and the "Try again" click no-ops (showForm is false again by :end, so
  // Elements never mounts — the user has to click twice). Decide stripeUnavailable from the
  // fetch OUTCOME instead, once the data is actually in.
  try {
    await Promise.all([paymentStore.fetchStripeConfig(), paymentStore.fetchSavedPaymentMethod()])
  } catch {
    stripeUnavailable.value = true
    return
  } finally {
    loadingInitial.value = false
    retrying.value = false
  }
  // fetchStripeConfig / fetchSavedPaymentMethod swallow their errors and resolve (store
  // swallow-and-resolve contract), so the catch above is effectively dead — read the store's error
  // keys instead. A failed config refetch must re-raise stripeUnavailable (and NOT fall through to
  // mountCardElement, which would call loadStripe with a stale publishableKey left over from an
  // earlier success).
  //
  // skillars-deferred-109 code review: gate on error.stripeConfig ONLY. A failed
  // fetchSavedPaymentMethod says nothing about whether Stripe is usable — the two endpoints even
  // carry different authorities (GET /stripe/config is IS_AUTHENTICATED, GET /payment/payment-method
  // is HAS_PARENT_PLAYER_OR_COACH_ROLE), so they can legitimately disagree per role. ORing it in
  // rendered payment.card.unavailable and withheld the add-card form from users whose card payments
  // were perfectly available. A missing saved card is already the correct "no card yet" state:
  // savedCard?.hasCard stays falsy and showForm shows the entry form.
  if (paymentStore.error.stripeConfig) {
    stripeUnavailable.value = true
    return
  }
  stripeUnavailable.value = false
  if (showForm.value) await mountCardElement()
}

async function submit() {
  if (!cardElement) return
  cardError.value = null
  saving.value = true
  try {
    const { clientSecret } = await createSetupIntent()
    const publishableKey = paymentStore.stripeConfig?.publishableKey
    const { setupIntent, error } = await confirmCardSetup(publishableKey, clientSecret, cardElement)
    if (error || setupIntent?.status !== 'succeeded') {
      cardError.value = error?.message || t('payment.card.saveError')
      return
    }
    await savePaymentMethod(setupIntent.payment_method)
    try {
      await paymentStore.fetchSavedPaymentMethod()
    } catch {
      // Card was already saved server-side; a refresh failure here is not a save failure.
    }
    // skillars-deferred-109 AC1.2: fetchSavedPaymentMethod swallows and resolves, so the catch
    // above never fires. If the post-save refresh failed, savedPaymentMethod stays stale/null and
    // showForm keeps the entry form mounted even though the card saved. Surface a non-blocking
    // notice (the save DID succeed) rather than silently leaving the form up.
    if (paymentStore.error.savedPaymentMethod) {
      $q.notify({ type: 'warning', message: t('payment.card.savedRefreshFailed') })
    }
    editing.value = false
    emit('saved')
  } catch {
    cardError.value = t('payment.card.saveError')
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  await loadStripeConfig()
})

onBeforeUnmount(() => {
  unmountCardElement()
})
</script>

<style lang="scss" scoped>
.payment-method-card {
  border: 1px solid var(--border-subtle);
}

.card-element {
  border: 1px solid var(--border-subtle);
  border-radius: 4px;
  background: var(--input-bg);
}
</style>
