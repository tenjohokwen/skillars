<template>
  <q-page class="q-pa-md">
    <div class="payment-settings glass-card q-pa-lg">
      <div class="text-h6 q-mb-md">{{ t('payment.stripe.title') }}</div>

      <q-banner
        v-if="stripeStatus && stripeStatus.onboardingStatus !== 'COMPLETE'"
        class="q-mb-md"
        rounded
        inline-actions
        style="background: rgba(var(--color-accent-primary-rgb, 255, 165, 0), 0.15)"
      >
        <template #avatar>
          <q-icon name="mdi-alert-circle" color="warning" />
        </template>
        {{ t('payment.stripe.bannerWarning') }}
      </q-banner>

      <q-banner
        v-if="paymentStore.error.stripeStatus"
        class="q-mb-md"
        rounded
        style="background: rgba(var(--color-error-rgb, 200, 0, 0), 0.12)"
      >
        <template #avatar>
          <q-icon name="mdi-alert" color="negative" />
        </template>
        {{ t('payment.error.statusLoadFailed') }}
      </q-banner>

      <!-- Stripe OAuth callback outcome: StripeOnboardingResource redirects back here with
           ?stripe_error=<code> (inside the hash, so it is a normal route query). -->
      <q-banner
        v-if="stripeCallbackErrorKey"
        class="q-mb-md stripe-callback-error"
        rounded
        inline-actions
        style="background: rgba(var(--color-error-rgb, 200, 0, 0), 0.12)"
      >
        <template #avatar>
          <q-icon name="mdi-alert" color="negative" />
        </template>
        {{ t(stripeCallbackErrorKey) }}
        <template #action>
          <q-btn flat dense :label="t('common.close')" @click="dismissStripeCallbackError" />
        </template>
      </q-banner>

      <q-banner
        v-if="connectError"
        class="q-mb-md"
        rounded
        style="background: rgba(var(--color-error-rgb, 200, 0, 0), 0.12)"
      >
        <template #avatar>
          <q-icon name="mdi-alert" color="negative" />
        </template>
        {{ t('payment.error.connectFailed') }}
      </q-banner>

      <div v-if="paymentStore.loading.stripeStatus" class="flex flex-center q-py-xl">
        <q-spinner size="48px" />
      </div>

      <div v-else-if="stripeStatus">
        <!-- Connected state -->
        <div v-if="stripeStatus.onboardingStatus === 'COMPLETE'" class="column q-gutter-sm">
          <div class="row items-center q-gutter-sm">
            <q-icon name="mdi-check-circle" color="positive" size="sm" />
            <span class="text-positive">{{ t('payment.stripe.connected') }}</span>
          </div>
          <div class="row items-center q-gutter-sm">
            <q-icon
              :name="stripeStatus.chargesEnabled ? 'mdi-check-circle' : 'mdi-close-circle'"
              :color="stripeStatus.chargesEnabled ? 'positive' : 'negative'"
              size="xs"
            />
            <span>{{ t('payment.stripe.chargesEnabled') }}</span>
          </div>
          <div class="row items-center q-gutter-sm">
            <q-icon
              :name="stripeStatus.payoutsEnabled ? 'mdi-check-circle' : 'mdi-close-circle'"
              :color="stripeStatus.payoutsEnabled ? 'positive' : 'negative'"
              size="xs"
            />
            <span>{{ t('payment.stripe.payoutsEnabled') }}</span>
          </div>
        </div>

        <!-- Restricted state -->
        <div
          v-else-if="stripeStatus.onboardingStatus === 'RESTRICTED'"
          class="row items-center q-gutter-sm"
        >
          <q-icon name="mdi-alert-circle" color="negative" size="sm" />
          <span class="text-negative">{{ t('payment.stripe.setupRestricted') }}</span>
        </div>

        <!-- Not connected / pending state -->
        <div v-else class="column q-gutter-md">
          <div class="row items-center q-gutter-sm">
            <q-icon name="mdi-alert-circle" color="warning" size="sm" />
            <span>{{ t('payment.stripe.setupPending') }}</span>
          </div>
          <q-btn
            :label="t('payment.stripe.connectCta')"
            style="background: var(--color-accent-primary, #f5a623); color: white"
            :loading="connectingStripe"
            @click="connectStripe"
          />
        </div>
      </div>

      <!-- Not yet connected -->
      <div v-else class="column q-gutter-md">
        <span class="text-body2" style="color: var(--text-secondary)">{{
          t('payment.stripe.notConnected')
        }}</span>
        <q-btn
          :label="t('payment.stripe.connectCta')"
          style="background: var(--color-accent-primary, #f5a623); color: white"
          :loading="connectingStripe"
          @click="connectStripe"
        />
      </div>
    </div>
  </q-page>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { usePaymentStore } from 'src/stores/payment.store'
import { getStripeOnboardingUrl } from 'src/api/payment.api'

const { t } = useI18n()
const paymentStore = usePaymentStore()
const connectingStripe = ref(false)
const connectError = ref(false)

const stripeStatus = computed(() => paymentStore.stripeStatus)

const route = useRoute()
const router = useRouter()

// stripe_error values: Stripe's own OAuth error code (access_denied when the coach declines; others
// are rare) or the backend's missing_code / state_mismatch. Anything unrecognised → generic message.
const STRIPE_CALLBACK_ERROR_KEYS = {
  access_denied: 'payment.error.stripeCallback.accessDenied',
  state_mismatch: 'payment.error.stripeCallback.stateMismatch',
}
const stripeCallbackErrorKey = computed(() => {
  const raw = route.query.stripe_error
  const code = Array.isArray(raw) ? raw[0] : raw
  if (!code) return null
  return STRIPE_CALLBACK_ERROR_KEYS[code] || 'payment.error.stripeCallback.generic'
})

function dismissStripeCallbackError() {
  const query = { ...route.query }
  delete query.stripe_error
  router.replace({ query })
}

onMounted(async () => {
  await paymentStore.fetchStripeStatus()
})

async function connectStripe() {
  connectingStripe.value = true
  connectError.value = false
  try {
    const data = await getStripeOnboardingUrl()
    // P22: guard against null/undefined URL before navigating
    if (data?.onboardingUrl) {
      window.location.href = data.onboardingUrl
    } else {
      connectError.value = true
    }
  } catch {
    // P21: surface API failures — store error drives the banner
    connectError.value = true
  } finally {
    connectingStripe.value = false
  }
}
</script>

<style scoped>
.payment-settings {
  max-width: 560px;
}
</style>
