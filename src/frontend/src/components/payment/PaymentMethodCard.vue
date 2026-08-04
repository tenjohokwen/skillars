<template>
  <div class="payment-method-card glass-card q-pa-md">
    <div v-if="loadingInitial" class="flex flex-center q-py-md">
      <q-spinner size="32px" />
    </div>

    <div v-else-if="stripeUnavailable" class="text-body2 text-secondary">
      {{ t('payment.card.unavailable') }}
    </div>

    <template v-else>
      <div v-if="savedCard?.hasCard && !editing" class="row items-center q-gutter-sm">
        <q-icon name="mdi-credit-card-outline" size="sm" color="primary" />
        <span class="text-body2">
          {{ savedCard.brand
            ? t('payment.card.savedLabel', {
                brand: savedCard.brand,
                last4: savedCard.last4,
                expMonth: savedCard.expMonth,
                expYear: savedCard.expYear,
              })
            : t('payment.card.detailsUnavailable') }}
        </span>
        <q-btn
          flat dense size="sm" color="primary"
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
            unelevated color="primary"
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
import { loadStripe } from '@stripe/stripe-js'
import { usePaymentStore } from 'src/stores/payment.store'
import { createSetupIntent, savePaymentMethod, confirmCardSetup } from 'src/api/payment.api'

const { t } = useI18n()
const paymentStore = usePaymentStore()
const emit = defineEmits(['saved'])

const cardElementRef = ref(null)
const loadingInitial = ref(true)
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
const showForm = computed(() => !stripeUnavailable.value && (editing.value || !savedCard.value?.hasCard))

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

async function mountCardElement() {
  const ready = await ensureStripeReady()
  if (!ready) return
  await nextTick()
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
    editing.value = false
    emit('saved')
  } catch {
    cardError.value = t('payment.card.saveError')
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  try {
    await Promise.all([
      paymentStore.fetchStripeConfig(),
      paymentStore.fetchSavedPaymentMethod(),
    ])
  } finally {
    loadingInitial.value = false
  }
  if (showForm.value) await mountCardElement()
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
